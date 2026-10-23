package dafeiyu.plugin.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.tool.AppContainerSandbox;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.plugin.rpc.RpcChannel;
import dafeiyu.plugin.rpc.RpcChannel.RpcError;

import java.io.BufferedReader;
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

    private final AppContainerSandbox.Spawned process;
    private final RpcChannel channel;

    private StdioMcpClient(AppContainerSandbox.Spawned process, RpcChannel channel) {
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
        List<String> argv = new ArrayList<>();
        argv.add(exe.toString());
        String name = exe.getFileName().toString().toLowerCase(Locale.ROOT);
        if (config.sandbox() && name.equals("node.exe")) {
            argv.addAll(List.of("--preserve-symlinks", "--preserve-symlinks-main"));
        }
        argv.addAll(config.args());
        String windows = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        String line = name.endsWith(".cmd") || name.endsWith(".bat")
                ? "\"" + windows + "\\System32\\cmd.exe\" /d /s /c \"" + AppContainerSandbox.quote(argv) + "\""
                : AppContainerSandbox.quote(argv);
        Map<String, String> env;
        String container = null;
        if (config.sandbox()) {
            env = AppContainerSandbox.baseEnvironment(data, List.of(exe.getParent()));
            container = "dafeiyu.mcp." + config.name();
            String sid = AppContainerSandbox.containerSid(container);
            AppContainerSandbox.grantTo(data, true, sid);
            AppContainerSandbox.grantTo(exe.getParent(), false, AppContainerSandbox.ALL_APP_PACKAGES);
            for (Path folder : scriptFolders(config.args())) {
                AppContainerSandbox.grantTo(folder, false, sid);
            }
            env.put("no_proxy", "*");
            env.put("NO_PROXY", "*");
        } else {
            env = new HashMap<>(System.getenv());
        }
        config.env().forEach((k, v) -> env.put(k, v.value()));
        AppContainerSandbox.Spawned spawned = AppContainerSandbox.spawn(new AppContainerSandbox.Spec(line, data, env, container,
                !config.sandbox() || config.network(), 1024L << 20, 80, 64));
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
        List<String> exts = command.contains(".") ? List.of("") : List.of(".exe", ".cmd", ".bat", ".com", "");
        for (String dir : path.split(";")) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : exts) {
                try {
                    Path p = Path.of(dir.strip(), command + ext);
                    if (Files.isRegularFile(p)) {
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
