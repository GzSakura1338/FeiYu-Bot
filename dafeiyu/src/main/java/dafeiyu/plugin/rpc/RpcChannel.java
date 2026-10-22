package dafeiyu.plugin.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RpcChannel {

    public interface Handler {
        JsonElement handle(String method, JsonObject params) throws RpcError;
    }

    public static final class RpcError extends Exception {
        public static final int PARSE = -32700;
        public static final int INVALID = -32600;
        public static final int NO_METHOD = -32601;
        public static final int BAD_PARAMS = -32602;
        public static final int FAILED = -32000;
        public static final int DENIED = -32001;
        public static final int LIMITED = -32002;

        private final int code;

        public RpcError(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public static final int MAX_LINE = 10 * 1024 * 1024;
    private static final ScheduledExecutorService TIMERS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "plugin-rpc-timer");
        t.setDaemon(true);
        return t;
    });

    private final InputStream in;
    private final OutputStream out;
    private final Handler handler;
    private final Consumer<String> problems;
    private final Runnable onClosed;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private volatile boolean closed;

    public RpcChannel(InputStream in, OutputStream out, Handler handler, Consumer<String> problems, Runnable onClosed) {
        this.in = in;
        this.out = out;
        this.handler = handler;
        this.problems = problems;
        this.onClosed = onClosed;
    }

    public void start(String name) {
        Thread.ofVirtual().name("plugin-rpc-" + name).start(this::readLoop);
    }

    public boolean closed() {
        return closed;
    }

    public int pendingCount() {
        return pending.size();
    }

    public CompletableFuture<JsonElement> call(String method, JsonObject params, long timeoutMs) {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IOException("plugin is not running"));
            return future;
        }
        String id = String.valueOf(ids.incrementAndGet());
        pending.put(id, future);
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("id", Long.parseLong(id));
        message.addProperty("method", method);
        message.add("params", params == null ? new JsonObject() : params);
        TIMERS.schedule(() -> {
            CompletableFuture<JsonElement> f = pending.remove(id);
            if (f != null) {
                f.completeExceptionally(new TimeoutException(method + " timed out after " + timeoutMs + " ms"));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            write(message);
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    public void notify(String method, JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("method", method);
        message.add("params", params == null ? new JsonObject() : params);
        try {
            write(message);
        } catch (IOException ignored) {
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        IOException gone = new IOException("plugin stopped");
        pending.values().forEach(f -> f.completeExceptionally(gone));
        pending.clear();
        workers.shutdownNow();
    }

    private void write(JsonObject message) throws IOException {
        byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            if (closed) {
                throw new IOException("plugin is not running");
            }
            out.write(bytes);
            out.flush();
        }
    }

    private void readLoop() {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        boolean dropping = false;
        try {
            while (true) {
                int n = in.read(buffer);
                if (n < 0) {
                    break;
                }
                int start = 0;
                for (int i = 0; i < n; i++) {
                    if (buffer[i] != '\n') {
                        continue;
                    }
                    if (!dropping) {
                        line.write(buffer, start, i - start);
                        dispatch(line.toString(StandardCharsets.UTF_8));
                    }
                    line.reset();
                    dropping = false;
                    start = i + 1;
                }
                if (!dropping) {
                    line.write(buffer, start, n - start);
                    if (line.size() > MAX_LINE) {
                        problems.accept("dropped an oversized line (over " + MAX_LINE / 1024 / 1024 + " MB)");
                        line.reset();
                        dropping = true;
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) {
                problems.accept("read failed: " + e.getMessage());
            }
        } finally {
            close();
            onClosed.run();
        }
    }

    private void dispatch(String raw) {
        String text = raw.strip();
        if (text.isEmpty()) {
            return;
        }
        JsonObject message;
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                problems.accept("ignored a line that is not a JSON-RPC object: " + preview(text));
                return;
            }
            message = element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            problems.accept("ignored a line that is not JSON: " + preview(text));
            return;
        }
        if (message.has("method") && message.get("method").isJsonPrimitive()) {
            String method = message.get("method").getAsString();
            JsonElement id = message.get("id");
            JsonObject params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : new JsonObject();
            try {
                workers.submit(() -> serve(method, params, id));
            } catch (RuntimeException ignored) {
            }
            return;
        }
        if (message.has("id") && (message.has("result") || message.has("error"))) {
            JsonElement id = message.get("id");
            String key = id != null && id.isJsonPrimitive() ? id.getAsString() : "";
            CompletableFuture<JsonElement> f = pending.remove(key);
            if (f == null) {
                problems.accept("ignored a response with an unknown id " + key);
                return;
            }
            if (message.has("error") && message.get("error").isJsonObject()) {
                JsonObject error = message.getAsJsonObject("error");
                int code = error.has("code") && error.get("code").isJsonPrimitive() ? error.get("code").getAsInt() : RpcError.FAILED;
                String msg = error.has("message") && error.get("message").isJsonPrimitive() ? error.get("message").getAsString() : "plugin error";
                f.completeExceptionally(new RpcError(code, msg));
            } else {
                f.complete(message.has("result") ? message.get("result") : JsonNull.INSTANCE);
            }
            return;
        }
        problems.accept("ignored a malformed JSON-RPC message: " + preview(text));
    }

    private void serve(String method, JsonObject params, JsonElement id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        try {
            JsonElement result = handler.handle(method, params);
            response.add("result", result == null ? JsonNull.INSTANCE : result);
        } catch (RpcError e) {
            JsonObject error = new JsonObject();
            error.addProperty("code", e.code());
            error.addProperty("message", e.getMessage());
            response.add("error", error);
        } catch (RuntimeException e) {
            JsonObject error = new JsonObject();
            error.addProperty("code", RpcError.FAILED);
            error.addProperty("message", "host error: " + e.getMessage());
            response.add("error", error);
        }
        if (id == null || id.isJsonNull()) {
            return;
        }
        response.add("id", id);
        try {
            write(response);
        } catch (IOException ignored) {
        }
    }

    private static String preview(String text) {
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
