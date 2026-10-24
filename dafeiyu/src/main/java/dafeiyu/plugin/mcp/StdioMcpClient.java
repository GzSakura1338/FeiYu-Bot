package dafeiyu.plugin.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.plugin.rpc.RpcChannel;
import dafeiyu.plugin.rpc.RpcChannel.RpcError;
import dafeiyu.sandbox.Platform;
import dafeiyu.sandbox.Sandbox;
import dafeiyu.sandbox.Sandboxes;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class StdioMcpClient extends McpClient {

    private final Sandbox.Running process;
    private final RpcChannel channel;

    private StdioMcpClient(Sandbox.Running process, RpcChannel channel) {
        this.process = process;
        this.channel = channel;
    }

    static StdioMcpClient start(McpServerConfig config, Path data, Runnable listChanged, Consumer<String> stderr) throws ToolFailure {
        try {
            Files.createDirectories(data);
        } catch (IOException e) {
            throw new ToolFailure("cannot create " + data + ": " + e.getMessage());
        }
        Path exe = resolve(config.command());
        if (exe == null) {
            throw new ToolFailure("找不到命令：" + config.command());
        }
        Sandbox sandbox = Sandboxes.current();
        if (config.sandbox() && sandbox.missing() != null) {
            throw new ToolFailure(sandbox.missing() + "。也可以在这个 MCP 服务的设置里关掉沙盒");
        }
        List<String> argv = new ArrayList<>();
        argv.add(exe.toString());
        String name = exe.getFileName().toString().toLowerCase(Locale.ROOT);
        if (config.sandbox() && (name.equals("node.exe") || name.equals("node"))) {
            argv.addAll(List.of("--preserve-symlinks", "--preserve-symlinks-main"));
        }
        argv.addAll(config.args());
        Map<String, String> env;
        if (config.sandbox()) {
            env = sandbox.environment(data, List.of(exe.getParent()));
            env.put("no_proxy", "*");
            env.put("NO_PROXY", "*");
        } else {
            env = new HashMap<>(System.getenv());
        }
        config.env().forEach((k, v) -> env.put(k, v.value()));
        boolean script = Platform.windows() && (name.endsWith(".cmd") || name.endsWith(".bat"));
        boolean network = !config.sandbox() || config.network();
        if (config.sandbox() && network && sandbox.networkMissing() != null) {
            stderr.accept("这次断网启动：" + sandbox.networkMissing());
            network = false;
        }
        Sandbox.Spec spec = (script ? Sandbox.Spec.shell(sandbox.quote(argv), data, env) : Sandbox.Spec.exec(argv, data, env))
                .isolated(config.sandbox(), "dafeiyu.mcp." + config.name(), network)
                .reading(scriptFolders(config.args()), Sandboxes.runtimeRoots(exe))
                .writing(List.of(data))
                .limits(1024L << 20, 80, 64);
        Sandbox.Running spawned = sandbox.spawn(spec);
        RpcChannel channel = new RpcChannel(spawned.stdout(), spawned.stdin(), (method, params) -> switch (method) {
            case "ping" -> new JsonObject();
            case "roots/list" -> {
                JsonObject r = new JsonObject();
                r.add("roots", new JsonArray());
                yield r;
            }
            case "notifications/tools/list_changed", "notifications/resources/list_changed", "notifications/prompts/list_changed" -> {
                listChanged.run();
                yield null;
            }
            case "notifications/message" -> {
                stderr.accept(logLine(params));
                yield null;
            }
            default -> {
                if (method.startsWith("notifications/")) {
                    yield null;
                }
                throw new RpcError(RpcError.NO_METHOD, "client does not support " + method);
            }
        }, problem -> stderr.accept("protocol: " + problem), () -> { });
        StdioMcpClient client = new StdioMcpClient(spawned, channel);
        channel.start("mcp-" + config.name());
        Thread.ofVirtual().name("mcp-stderr-" + config.name()).start(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(spawned.stderr(), StandardCharsets.UTF_8))) {
                String text;
                while ((text = err.readLine()) != null) {
                    if (!text.isBlank()) {
                        stderr.accept(text);
                    }
                }
            } catch (IOException ignored) {
            }
        });
        return client;
    }

    static List<Path> scriptFolders(List<String> args) {
        List<Path> out = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("-")) {
                continue;
            }
            try {
                Path p = Path.of(arg).toAbsolutePath().normalize();
                Path folder = Files.isDirectory(p) ? p : Files.isRegularFile(p) ? p.getParent() : null;
                if (folder != null && folder.getParent() != null && !out.contains(folder)) {
                    out.add(folder);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    static Path resolve(String command) {
        Path direct = Path.of(command);
        if (direct.isAbsolute()) {
            return Files.isRegularFile(direct) ? direct : null;
        }
        String path = System.getenv().getOrDefault("PATH", System.getenv().getOrDefault("Path", ""));
        boolean windows = Platform.windows();
        List<String> exts = !windows || command.contains(".") ? List.of("") : List.of(".exe", ".cmd", ".bat", ".com", "");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : exts) {
                try {
                    Path p = Path.of(dir.strip(), command + ext);
                    if (Files.isRegularFile(p) && (windows || Files.isExecutable(p))) {
                        return p;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    @Override
    JsonElement request(String method, JsonObject params, long timeoutMs) throws Exception {
        return channel.call(method, params, timeoutMs, this::cancelled).get(timeoutMs + 500, TimeUnit.MILLISECONDS);
    }

    @Override
    void notification(String method, JsonObject params) {
        channel.notify(method, params);
    }

    @Override
    boolean alive() {
        return !channel.closed() && process.alive();
    }

    @Override
    public void close() {
        channel.close();
        process.close();
    }
}
