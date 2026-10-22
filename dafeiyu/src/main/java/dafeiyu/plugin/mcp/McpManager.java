package dafeiyu.plugin.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.agent.Extensions;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.log.LogBuffer;
import dafeiyu.plugin.manifest.ArgsCheck;
import dafeiyu.plugin.rpc.ToolResults;
import dafeiyu.plugin.store.PluginStore;
import dafeiyu.util.Jsons;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class McpManager {

    public static final String PREFIX = "mcp__";
    private static final long MAX_BACKOFF_MS = 5 * 60_000;

    public record ToolView(String name, String original, String description, JsonObject schema) {}

    public record ServerView(McpServerConfig config, String status, String error, String serverName, long connectedAt, List<ToolView> tools,
                             List<String> stderr) {}

    private static final class Server {
        volatile McpServerConfig config;
        volatile McpClient client;
        volatile String status = "stopped";
        volatile String error = "";
        volatile long connectedAt;
        volatile List<ToolView> tools = List.of();
        final Deque<String> stderr = new ArrayDeque<>();
        int backoff;
        ScheduledFuture<?> retry;
        long generation;
    }

    private final PluginStore store;
    private final Path dataDir;
    private final LogBuffer log;
    private final Runnable changed;
    private final Map<String, Server> servers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcp");
        t.setDaemon(true);
        return t;
    });

    public McpManager(PluginStore store, Path dataDir, LogBuffer log, Runnable changed) {
        this.store = store;
        this.dataDir = dataDir;
        this.log = log;
        this.changed = changed;
    }

    public void start() {
        for (McpServerConfig config : stored()) {
            Server s = new Server();
            s.config = config;
            servers.put(config.name(), s);
            if (config.enabled()) {
                connect(s);
            }
        }
        timers.scheduleAtFixedRate(this::watch, 5, 5, TimeUnit.SECONDS);
    }

    private List<McpServerConfig> stored() {
        List<McpServerConfig> out = new ArrayList<>();
        try {
            JsonElement raw = JsonParser.parseString(store.mcpServers());
            if (raw.isJsonArray()) {
                for (JsonElement e : raw.getAsJsonArray()) {
                    if (e.isJsonObject()) {
                        out.add(McpServerConfig.fromStored(e.getAsJsonObject()));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("mcp: stored server list is unreadable: " + e.getMessage());
        }
        return out;
    }

    private synchronized void persist() {
        JsonArray all = new JsonArray();
        servers.values().stream().map(s -> s.config).sorted((a, b) -> a.name().compareTo(b.name())).forEach(c -> all.add(c.toStored()));
        store.setMcpServers(all.toString());
    }

    public synchronized McpServerConfig save(JsonObject body, String originalName) {
        Server old = originalName == null ? null : servers.get(originalName);
        McpServerConfig config = McpServerConfig.fromRequest(body, old == null ? null : old.config);
        List<String> problems = config.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", problems));
        }
        if ((old == null || !originalName.equals(config.name())) && servers.containsKey(config.name())) {
            throw new IllegalArgumentException("已经有一个叫「" + config.name() + "」的 MCP 服务了");
        }
        if (old != null) {
            disconnect(old);
            servers.remove(originalName);
        }
        Server s = new Server();
        s.config = config;
        servers.put(config.name(), s);
        persist();
        if (config.enabled()) {
            connect(s);
        }
        changed.run();
        log.info("mcp: server " + config.name() + " saved (" + config.type() + ")");
        return config;
    }

    public synchronized void delete(String name) {
        Server s = servers.remove(name);
        if (s == null) {
            throw new IllegalArgumentException("没有这个 MCP 服务：" + name);
        }
        disconnect(s);
        persist();
        changed.run();
        log.info("mcp: server " + name + " removed");
    }

    public synchronized void setEnabled(String name, boolean on) {
        Server s = require(name);
        McpServerConfig c = s.config;
        s.config = new McpServerConfig(c.name(), c.type(), c.command(), c.args(), c.env(), c.url(), c.headers(), on, c.sandbox(), c.network(),
                c.timeout(), c.qq(), c.mind());
        persist();
        if (on) {
            s.backoff = 0;
            connect(s);
        } else {
            disconnect(s);
            s.status = "stopped";
        }
        changed.run();
    }

    public synchronized void reconnect(String name) {
        Server s = require(name);
        disconnect(s);
        s.backoff = 0;
        if (s.config.enabled()) {
            connect(s);
        }
    }

    private Server require(String name) {
        Server s = servers.get(name);
        if (s == null) {
            throw new IllegalArgumentException("没有这个 MCP 服务：" + name);
        }
        return s;
    }

    private void connect(Server s) {
        long generation = ++s.generation;
        s.status = "connecting";
        s.error = "";
        Thread.ofVirtual().name("mcp-connect-" + s.config.name()).start(() -> {
            McpServerConfig config = s.config;
            McpClient client = null;
            try {
                client = config.type().equals("http")
                        ? new HttpMcpClient(config, () -> refresh(s), p -> stderr(s, p))
                        : StdioMcpClient.start(config, dataDir.resolve(config.name()), () -> refresh(s), line -> stderr(s, line));
                client.initialize();
                List<ToolView> tools = views(config.name(), client.listTools());
                synchronized (this) {
                    if (s.generation != generation) {
                        client.close();
                        return;
                    }
                    s.client = client;
                    s.tools = tools;
                    s.status = "connected";
                    s.connectedAt = System.currentTimeMillis();
                    s.backoff = 0;
                }
                log.info("mcp: " + config.name() + " connected (" + client.serverName + ", protocol " + client.negotiated + ", "
                        + tools.size() + " tools)");
                changed.run();
            } catch (Exception e) {
                if (client != null) {
                    client.close();
                }
                failed(s, generation, e);
            }
        });
    }

    private synchronized void failed(Server s, long generation, Exception e) {
        if (s.generation != generation) {
            return;
        }
        Throwable cause = e.getCause() != null && e.getMessage() == null ? e.getCause() : e;
        String message = cause instanceof TimeoutException ? "连接超时"
                : cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        if (message.startsWith("protocol version not supported")) {
            message = "协议版本不兼容：" + message.substring(message.indexOf(':') + 1).strip();
        } else if (message.contains("plugin stopped") || message.contains("not running")) {
            String tail;
            synchronized (s.stderr) {
                tail = s.stderr.isEmpty() ? "" : s.stderr.getLast();
            }
            message = "服务启动后立刻退出了" + (tail.isBlank() ? "" : "：" + tail);
        }
        s.client = null;
        s.tools = List.of();
        s.status = "error";
        s.error = message;
        log.warn("mcp: " + s.config.name() + " failed: " + message);
        changed.run();
        if (!s.config.enabled()) {
            return;
        }
        long delay = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(20, s.backoff));
        s.backoff++;
        if (s.retry != null) {
            s.retry.cancel(false);
        }
        s.retry = timers.schedule(() -> {
            synchronized (McpManager.this) {
                if (s.generation == generation && s.config.enabled() && servers.get(s.config.name()) == s) {
                    connect(s);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void disconnect(Server s) {
        s.generation++;
        if (s.retry != null) {
            s.retry.cancel(false);
            s.retry = null;
        }
        McpClient c = s.client;
        s.client = null;
        s.tools = List.of();
        if (c != null) {
            c.close();
        }
    }

    private void watch() {
        for (Server s : servers.values()) {
            McpClient c = s.client;
            if (c != null && "connected".equals(s.status) && !c.alive()) {
                failed(s, s.generation, new IllegalStateException("服务断开了"));
            }
        }
    }

    private void refresh(Server s) {
        Thread.ofVirtual().start(() -> {
            McpClient c = s.client;
            if (c == null) {
                return;
            }
            try {
                s.tools = views(s.config.name(), c.listTools());
                log.info("mcp: " + s.config.name() + " tool list changed, now " + s.tools.size() + " tools");
                changed.run();
            } catch (Exception e) {
                log.warn("mcp: " + s.config.name() + " tools/list failed: " + e.getMessage());
            }
        });
    }

    private void stderr(Server s, String line) {
        synchronized (s.stderr) {
            s.stderr.addLast(line.length() > 500 ? line.substring(0, 500) + "…" : line);
            while (s.stderr.size() > 50) {
                s.stderr.removeFirst();
            }
        }
    }

    static List<ToolView> views(String server, List<JsonObject> tools) {
        List<ToolView> out = new ArrayList<>();
        for (JsonObject t : tools) {
            String original = Jsons.optString(t, "name");
            if (original.isEmpty()) {
                continue;
            }
            JsonObject schema = t.has("inputSchema") && t.get("inputSchema").isJsonObject() ? t.getAsJsonObject("inputSchema").deepCopy() : new JsonObject();
            if (!schema.has("type")) {
                schema.addProperty("type", "object");
            }
            String description = Jsons.optString(t, "description");
            if (description.length() > 1000) {
                description = description.substring(0, 1000) + "…";
            }
            out.add(new ToolView(name(server, original), original, description, schema));
        }
        return out;
    }

    static String name(String server, String tool) {
        String clean = tool.replaceAll("[^A-Za-z0-9_-]", "_");
        String full = PREFIX + server + "__" + clean;
        if (full.length() <= 64) {
            return full;
        }
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tool.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
            return full.substring(0, 55) + "_" + hash;
        } catch (NoSuchAlgorithmException e) {
            return full.substring(0, 64);
        }
    }

    public List<Extensions.ToolInfo> catalog() {
        List<Extensions.ToolInfo> out = new ArrayList<>();
        for (Server s : sortedServers()) {
            for (ToolView t : s.tools) {
                out.add(new Extensions.ToolInfo(t.name(), "MCP " + s.config.name() + " · " + t.original(), t.description(), "mcp:" + s.config.name(),
                        s.config.qq()));
            }
        }
        return out;
    }

    public List<ToolSpec> tools(boolean qq, boolean mind, Set<String> enabled) {
        List<ToolSpec> out = new ArrayList<>();
        for (Server s : sortedServers()) {
            if (!"connected".equals(s.status) || (qq && !s.config.qq()) || (mind && !s.config.mind())) {
                continue;
            }
            for (ToolView t : s.tools) {
                if (enabled.contains(t.name())) {
                    out.add(new ToolSpec(t.name(), "[MCP " + s.config.name() + "] " + t.description(), t.schema().deepCopy()));
                }
            }
        }
        return out;
    }

    public Toolbox.Outcome run(ToolCall call) {
        for (Server s : servers.values()) {
            for (ToolView t : s.tools) {
                if (!t.name().equals(call.name())) {
                    continue;
                }
                McpClient c = s.client;
                if (c == null || !"connected".equals(s.status)) {
                    return new Toolbox.Outcome("MCP 服务「" + s.config.name() + "」现在没连上，这个工具用不了", true);
                }
                JsonObject args = Jsons.parseObject(call.arguments());
                if (args == null) {
                    return new Toolbox.Outcome("Invalid arguments for " + call.name() + ": expected a JSON object", true);
                }
                String problem = ArgsCheck.check(t.schema(), args);
                if (problem != null) {
                    return new Toolbox.Outcome("Invalid arguments for " + call.name() + ": " + problem, true);
                }
                long timeout = s.config.timeout() * 1000L;
                try {
                    return ToolResults.outcome(c.callTool(t.original(), args, timeout));
                } catch (TimeoutException e) {
                    return new Toolbox.Outcome("MCP tool " + call.name() + " timed out after " + s.config.timeout() + " s", true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Toolbox.Outcome("interrupted", true);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return new Toolbox.Outcome("MCP tool " + call.name() + " failed: " + cause.getMessage(), true);
                }
            }
        }
        return new Toolbox.Outcome("Unknown MCP tool: " + call.name(), true);
    }

    private List<Server> sortedServers() {
        return servers.values().stream().sorted((a, b) -> a.config.name().compareTo(b.config.name())).toList();
    }

    public List<ServerView> views() {
        List<ServerView> out = new ArrayList<>();
        for (Server s : sortedServers()) {
            List<String> err;
            synchronized (s.stderr) {
                err = List.copyOf(s.stderr);
            }
            out.add(new ServerView(s.config, s.status, s.error, s.client == null ? "" : s.client.serverName, s.connectedAt, s.tools, err));
        }
        return out;
    }

    public Map<String, String> statuses() {
        Map<String, String> out = new LinkedHashMap<>();
        sortedServers().forEach(s -> out.put(s.config.name(), s.status));
        return out;
    }

    public void shutdown() {
        servers.values().forEach(this::disconnect);
        timers.shutdownNow();
    }
}
