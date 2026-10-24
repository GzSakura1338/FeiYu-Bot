package dafeiyu.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.plugin.rpc.RpcChannel;
import dafeiyu.sandbox.Sandbox;
import dafeiyu.sandbox.Sandboxes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class PluginProcess {

    private static final long SHUTDOWN_GRACE_MS = 3_000;

    private final String id;
    private final Sandbox.Running process;
    private final RpcChannel channel;
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean stopping;

    private PluginProcess(String id, Sandbox.Running process, RpcChannel channel) {
        this.id = id;
        this.process = process;
        this.channel = channel;
    }

    static PluginProcess start(String id, Sandbox.Spec launch, RpcChannel.Handler handler, BiConsumer<String, String> log,
                               Consumer<PluginProcess> onExit) throws ToolFailure {
        Sandbox.Running spawned = Sandboxes.current().spawn(launch);
        PluginProcess[] self = new PluginProcess[1];
        RpcChannel channel = new RpcChannel(spawned.stdout(), spawned.stdin(), handler,
                problem -> log.accept("warn", "protocol: " + problem), () -> {
                    PluginProcess p = self[0];
                    if (p != null) {
                        p.exited(onExit);
                    }
                });
        PluginProcess process = new PluginProcess(id, spawned, channel);
        self[0] = process;
        channel.start(id);
        Thread.ofVirtual().name("plugin-stderr-" + id).start(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(spawned.stderr(), StandardCharsets.UTF_8))) {
                String text;
                while ((text = err.readLine()) != null) {
                    if (!text.isBlank()) {
                        log.accept("stderr", text);
                    }
                }
            } catch (IOException ignored) {
            }
        });
        return process;
    }

    private void exited(Consumer<PluginProcess> onExit) {
        process.waitFor(1_000);
        onExit.accept(this);
    }

    String id() {
        return id;
    }

    long pid() {
        return process.pid();
    }

    long startedAt() {
        return startedAt;
    }

    boolean alive() {
        return !channel.closed() && process.alive();
    }

    boolean stopping() {
        return stopping;
    }

    int exitCode() {
        return process.exitCode();
    }

    CompletableFuture<JsonElement> call(String method, JsonObject params, long timeoutMs) {
        return channel.call(method, params, timeoutMs);
    }

    void notify(String method, JsonObject params) {
        channel.notify(method, params);
    }

    void stop() {
        stopping = true;
        if (alive()) {
            channel.notify("shutdown", new JsonObject());
            process.waitFor(SHUTDOWN_GRACE_MS);
        }
        process.close();
        channel.close();
    }

    void kill() {
        stopping = true;
        process.close();
        channel.close();
    }
}
