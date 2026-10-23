package dafeiyu.plugin.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.agent.Extensions;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.tool.Args;
import dafeiyu.agent.tool.Tool;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.ToolResult;
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
    public static final String LIST_RESOURCES = "mcp_list_resources";
    public static final String READ_RESOURCE = "mcp_read_resource";
    public static final String GET_PROMPT = "mcp_get_prompt";
    private static final long MAX_BACKOFF_MS = 5 * 60_000;
    private static final long READ_TIMEOUT_MS = 60_000;
    private static final int MAX_LISTED = 100;

    public record ToolView(String name, String original, String description, JsonObject schema) {}

    public record EntryView(String name, String target, String description) {}

    public record ServerView(McpServerConfig config, String status, String error, String serverName, long connectedAt, List<ToolView> tools,
                             List<EntryView> resources, List<EntryView> prompts, List<String> stderr) {}

    private static final class Server {
        volatile McpServerConfig config;
        volatile McpClient client;
        volatile String status = "stopped";
        volatile String error = "";
        volatile long connectedAt;
        volatile List<ToolView> tools = List.of();
        volatile List<EntryView> resources = List.of();
        volatile List<EntryView> prompts = List.of();
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
                List<EntryView> resources = resources(s, client);
                List<EntryView> prompts = prompts(s, client);
                synchronized (this) {
                    if (s.generation != generation) {
                        client.close();
                        return;
                    }
                    s.client = client;
                    s.tools = tools;
                    s.resources = resources;
                    s.prompts = prompts;
                    s.status = "connected";
                    s.connectedAt = System.currentTimeMillis();
                    s.backoff = 0;
                }
                log.info("mcp: " + config.name() + " connected (" + client.serverName + ", protocol " + client.negotiated + ", "
                        + tools.size() + " tools, " + resources.size() + " resources, " + prompts.size() + " prompts)");
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
        s.resources = List.of();
        s.prompts = List.of();
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
        s.resources = List.of();
        s.prompts = List.of();
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
                s.resources = resources(s, c);
                s.prompts = prompts(s, c);
                log.info("mcp: " + s.config.name() + " lists changed, now " + s.tools.size() + " tools, " + s.resources.size() + " resources, "
                        + s.prompts.size() + " prompts");
                changed.run();
            } catch (Exception e) {
                log.warn("mcp: " + s.config.name() + " tools/list failed: " + e.getMessage());
            }
        });
    }

    private List<EntryView> resources(Server s, McpClient c) {
        List<EntryView> out = new ArrayList<>();
        try {
            for (JsonObject r : c.listResources()) {
                out.add(new EntryView(title(r), Jsons.optString(r, "uri"), Jsons.optString(r, "description")));
            }
            for (JsonObject t : c.listResourceTemplates()) {
                out.add(new EntryView(title(t), Jsons.optString(t, "uriTemplate"), Jsons.optString(t, "description")));
            }
        } catch (Exception e) {
            stderr(s, "resources/list failed: " + e.getMessage());
        }
        return List.copyOf(out);
    }

    private List<EntryView> prompts(Server s, McpClient c) {
        List<EntryView> out = new ArrayList<>();
        try {
            for (JsonObject p : c.listPrompts()) {
                out.add(new EntryView(Jsons.optString(p, "name"), arguments(p), Jsons.optString(p, "description")));
            }
        } catch (Exception e) {
            stderr(s, "prompts/list failed: " + e.getMessage());
        }
        return List.copyOf(out);
    }

    private static String title(JsonObject o) {
        String title = Jsons.optString(o, "title");
        return title.isEmpty() ? Jsons.optString(o, "name") : title;
    }

    private static String arguments(JsonObject prompt) {
        List<String> out = new ArrayList<>();
        JsonElement args = prompt.get("arguments");
        if (args != null && args.isJsonArray()) {
            for (JsonElement a : args.getAsJsonArray()) {
                JsonObject arg = a.isJsonObject() ? a.getAsJsonObject() : new JsonObject();
                boolean required = arg.has("required") && arg.get("required").isJsonPrimitive() && arg.get("required").getAsBoolean();
                String description = Jsons.optString(arg, "description");
                out.add(Jsons.optString(arg, "name") + (required ? "（必填）" : "（可选）") + (description.isEmpty() ? "" : description));
            }
        }
        return String.join("；", out);
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
        List<Server> withResources = sortedServers().stream().filter(s -> supports(s, McpClient.RESOURCES)).toList();
        if (!withResources.isEmpty()) {
            boolean qq = withResources.stream().anyMatch(s -> s.config.qq());
            out.add(new Extensions.ToolInfo(LIST_RESOURCES, "MCP · 列出资源", "列出 MCP 服务提供的资源和资源模板", "mcp", qq));
            out.add(new Extensions.ToolInfo(READ_RESOURCE, "MCP · 读资源", "按 URI 读一个 MCP 资源，图片会直接给她看", "mcp", qq));
        }
        List<Server> withPrompts = sortedServers().stream().filter(s -> supports(s, McpClient.PROMPTS)).toList();
        if (!withPrompts.isEmpty()) {
            out.add(new Extensions.ToolInfo(GET_PROMPT, "MCP · 提示词", "列出或取用 MCP 服务提供的提示词模板", "mcp", withPrompts.stream().anyMatch(s -> s.config.qq())));
        }
        return out;
    }

    public List<Tool> tools(boolean qq, boolean mind, Set<String> enabled) {
        List<Tool> out = new ArrayList<>();
        for (Server s : eligible(qq, mind, null)) {
            for (ToolView t : s.tools) {
                if (enabled.contains(t.name())) {
                    out.add(Tool.of(new ToolSpec(t.name(), "[MCP " + s.config.name() + "] " + t.description(), t.schema().deepCopy()),
                            (args, context) -> call(t.name(), args)));
                }
            }
        }
        if (!eligible(qq, mind, McpClient.RESOURCES).isEmpty()) {
            if (enabled.contains(LIST_RESOURCES)) {
                out.add(Tool.text(LIST_RESOURCES, "列出 MCP 服务提供的资源（文档、记录、文件等数据）和资源模板。用户提到的东西不是本地文件、而是带自定义前缀的 URI，"
                                + "或者想看某个 MCP 服务有哪些数据时用它，找到后用 " + READ_RESOURCE + " 读。模板要把 {占位} 换成具体值再读。", """
                                {"type": "object", "properties": {
                                  "server": {"type": "string", "description": "MCP 服务名；不写就列出所有服务的"}
                                }}""",
                        (args, context) -> listResources(eligible(qq, mind, McpClient.RESOURCES), args.text("server"))));
            }
            if (enabled.contains(READ_RESOURCE)) {
                out.add(Tool.of(new ToolSpec(READ_RESOURCE, "读一个 MCP 资源，返回它的文字内容；是图片时直接附给你看。URI 用 " + LIST_RESOURCES
                                + " 列出来的、模板填好占位后的，或者工具结果里资源链接给的。", Args.schema("""
                                {"type": "object", "properties": {
                                  "server": {"type": "string", "description": "资源所在的 MCP 服务名"},
                                  "uri": {"type": "string", "description": "资源的 URI"}
                                }, "required": ["server", "uri"]}""")),
                        (args, context) -> readResource(eligible(qq, mind, McpClient.RESOURCES), args.text("server"), args.string("uri"))));
            }
        }
        if (!eligible(qq, mind, McpClient.PROMPTS).isEmpty() && enabled.contains(GET_PROMPT)) {
            out.add(Tool.text(GET_PROMPT, "MCP 服务提供的提示词模板：不写 name 就列出有哪些、各要什么参数；写了 name 就按 arguments 填好参数，取回整理好的提示内容，照着做。", """
                            {"type": "object", "properties": {
                              "server": {"type": "string", "description": "MCP 服务名；只有一个服务有这个提示词时可以不写"},
                              "name": {"type": "string", "description": "提示词的名字；不写就是列出全部"},
                              "arguments": {"type": "object", "description": "提示词要的参数，值都写成字符串"}
                            }}""",
                    (args, context) -> prompt(eligible(qq, mind, McpClient.PROMPTS), args)));
        }
        return out;
    }

    private static boolean supports(Server s, String feature) {
        McpClient c = s.client;
        return "connected".equals(s.status) && c != null && (feature == null || c.supports(feature));
    }

    private List<Server> eligible(boolean qq, boolean mind, String feature) {
        return sortedServers().stream().filter(s -> supports(s, feature) && (!qq || s.config.qq()) && (!mind || s.config.mind())).toList();
    }

    private static Server pick(List<Server> servers, String name) throws ToolFailure {
        if (!name.isEmpty()) {
            return servers.stream().filter(s -> s.config.name().equals(name)).findFirst()
                    .orElseThrow(() -> new ToolFailure("没有叫「" + name + "」的 MCP 服务，可用的：" + names(servers)));
        }
        if (servers.size() == 1) {
            return servers.getFirst();
        }
        throw new ToolFailure("有好几个 MCP 服务，写上 server：" + names(servers));
    }

    private static String names(List<Server> servers) {
        return String.join("、", servers.stream().map(s -> s.config.name()).toList());
    }

    private String listResources(List<Server> servers, String name) throws ToolFailure {
        List<Server> chosen = name.isEmpty() ? servers : List.of(pick(servers, name));
        StringBuilder out = new StringBuilder();
        for (Server s : chosen) {
            McpClient c = s.client;
            List<JsonObject> resources;
            List<JsonObject> templates;
            try {
                resources = c.listResources();
                templates = c.listResourceTemplates();
            } catch (Exception e) {
                out.append("「").append(s.config.name()).append("」列不出来：").append(reason(e)).append("\n\n");
                continue;
            }
            out.append("「").append(s.config.name()).append("」").append(resources.size()).append(" 个资源")
                    .append(templates.isEmpty() ? "" : "、" + templates.size() + " 个模板").append("：\n");
            resources.stream().limit(MAX_LISTED).forEach(r -> out.append("- ").append(title(r)).append("：").append(Jsons.optString(r, "uri"))
                    .append(mime(r)).append(describe(r)).append('\n'));
            templates.stream().limit(MAX_LISTED).forEach(t -> out.append("- 模板 ").append(title(t)).append("：").append(Jsons.optString(t, "uriTemplate"))
                    .append(mime(t)).append(describe(t)).append('\n'));
            if (resources.size() > MAX_LISTED || templates.size() > MAX_LISTED) {
                out.append("（太多了，每类只列了前 ").append(MAX_LISTED).append(" 个）\n");
            }
            out.append('\n');
        }
        return out.toString().strip();
    }

    private ToolResult readResource(List<Server> servers, String name, String uri) throws ToolFailure {
        Server s = pick(servers, name);
        JsonElement result;
        try {
            result = s.client.readResource(uri.strip(), READ_TIMEOUT_MS);
        } catch (Exception e) {
            throw new ToolFailure("读不了「" + uri + "」：" + reason(e));
        }
        JsonElement contents = result != null && result.isJsonObject() ? result.getAsJsonObject().get("contents") : null;
        List<ImagePart> images = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        if (contents != null && contents.isJsonArray()) {
            for (JsonElement e : contents.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    parts.add(ToolResults.resource(e.getAsJsonObject(), images));
                }
            }
        }
        if (parts.isEmpty()) {
            throw new ToolFailure("「" + uri + "」是空的");
        }
        return ToolResult.withImages(String.join("\n\n", parts), images);
    }

    private String prompt(List<Server> servers, Args args) throws ToolFailure {
        String name = args.text("name");
        String serverName = args.text("server");
        if (name.isEmpty()) {
            StringBuilder out = new StringBuilder();
            for (Server s : serverName.isEmpty() ? servers : List.of(pick(servers, serverName))) {
                out.append("「").append(s.config.name()).append("」：\n");
                s.prompts.forEach(p -> out.append("- ").append(p.name()).append(p.description().isEmpty() ? "" : "：" + p.description())
                        .append(p.target().isEmpty() ? "" : "（参数：" + p.target() + "）").append('\n'));
                out.append('\n');
            }
            return out.toString().strip();
        }
        List<Server> owners = servers.stream().filter(s -> s.prompts.stream().anyMatch(p -> p.name().equals(name))).toList();
        Server s = serverName.isEmpty() ? pick(owners.isEmpty() ? servers : owners, "") : pick(servers, serverName);
        JsonObject arguments = new JsonObject();
        JsonElement given = args.get("arguments");
        if (given != null && given.isJsonObject()) {
            given.getAsJsonObject().entrySet().forEach(e -> arguments.addProperty(e.getKey(),
                    e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString()));
        }
        JsonElement result;
        try {
            result = s.client.getPrompt(name, arguments, READ_TIMEOUT_MS);
        } catch (Exception e) {
            throw new ToolFailure("取不到提示词「" + name + "」：" + reason(e));
        }
        JsonObject r = result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
        StringBuilder out = new StringBuilder();
        String description = Jsons.optString(r, "description");
        if (!description.isEmpty()) {
            out.append(description).append("\n\n");
        }
        JsonElement messages = r.get("messages");
        for (JsonElement m : messages != null && messages.isJsonArray() ? messages.getAsJsonArray() : new JsonArray()) {
            JsonObject message = m.isJsonObject() ? m.getAsJsonObject() : new JsonObject();
            JsonArray content = new JsonArray();
            if (message.has("content")) {
                content.add(message.get("content"));
            }
            out.append("【").append(Jsons.optString(message, "role")).append("】").append(ToolResults.parts(content, new ArrayList<>())).append("\n\n");
        }
        return out.isEmpty() ? "这个提示词是空的" : out.toString().strip();
    }

    private static String mime(JsonObject o) {
        String mime = Jsons.optString(o, "mimeType");
        return mime.isEmpty() ? "" : "（" + mime + "）";
    }

    private static String describe(JsonObject o) {
        String description = Jsons.optString(o, "description");
        return description.isEmpty() ? "" : " " + (description.length() > 200 ? description.substring(0, 200) + "…" : description);
    }

    private static String reason(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause instanceof TimeoutException ? "超时了" : String.valueOf(cause.getMessage());
    }

    private ToolResult call(String name, Args args) {
        for (Server s : servers.values()) {
            for (ToolView t : s.tools) {
                if (!t.name().equals(name)) {
                    continue;
                }
                McpClient c = s.client;
                if (c == null || !"connected".equals(s.status)) {
                    return ToolResult.error("MCP 服务「" + s.config.name() + "」现在没连上，这个工具用不了");
                }
                String problem = ArgsCheck.check(t.schema(), args.json());
                if (problem != null) {
                    return ToolResult.error("Invalid arguments for " + name + ": " + problem);
                }
                long timeout = s.config.timeout() * 1000L;
                try {
                    return ToolResults.outcome(c.callTool(t.original(), args.json(), timeout));
                } catch (TimeoutException e) {
                    return ToolResult.error("MCP tool " + name + " timed out after " + s.config.timeout() + " s");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("interrupted");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return ToolResult.error("MCP tool " + name + " failed: " + cause.getMessage());
                }
            }
        }
        return ToolResult.error("Unknown MCP tool: " + name);
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
            out.add(new ServerView(s.config, s.status, s.error, s.client == null ? "" : s.client.serverName, s.connectedAt, s.tools, s.resources,
                    s.prompts, err));
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
