package dafeiyu.plugin.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.plugin.rpc.RpcChannel.RpcError;
import dafeiyu.util.Jsons;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class HttpMcpClient extends McpClient {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private final URI url;
    private final Map<String, String> headers;
    private final Runnable listChanged;
    private final Consumer<String> problems;
    private final AtomicLong ids = new AtomicLong();
    private volatile String session;
    private volatile boolean closed;
    private volatile Thread listener;

    HttpMcpClient(McpServerConfig config, Runnable listChanged, Consumer<String> problems) {
        this.url = URI.create(config.url());
        this.headers = config.headers().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().value()));
        this.listChanged = listChanged;
        this.problems = problems;
    }

    @Override
    void initialize() throws Exception {
        super.initialize();
        listener = Thread.ofVirtual().name("mcp-http-listen").start(this::listen);
    }

    private HttpRequest.Builder base(long timeoutMs) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json, text/event-stream");
        headers.forEach(b::header);
        if (session != null) {
            b.header("Mcp-Session-Id", session);
        }
        b.header("MCP-Protocol-Version", negotiated);
        return b;
    }

    @Override
    JsonElement request(String method, JsonObject params, long timeoutMs) throws Exception {
        long id = ids.incrementAndGet();
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.add("params", params);
        HttpRequest request = base(timeoutMs).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message.toString())).build();
        HttpResponse<Stream<String>> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (HttpTimeoutException e) {
            cancelled(id);
            throw new TimeoutException(method + " timed out after " + timeoutMs + " ms");
        }
        response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
        if (response.statusCode() == 404 && session != null) {
            closed = true;
            throw new IOException("MCP session expired");
        }
        if (response.statusCode() >= 400) {
            String body = String.join("\n", response.body().limit(5).toList());
            throw new IOException("HTTP " + response.statusCode() + (body.isBlank() ? "" : ": " + body));
        }
        String type = response.headers().firstValue("Content-Type").orElse("");
        try (Stream<String> lines = response.body()) {
            if (type.startsWith("text/event-stream")) {
                Iterator<String> it = lines.iterator();
                StringBuilder data = new StringBuilder();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line.startsWith("data:")) {
                        data.append(line.substring(5).strip());
                    } else if (line.isBlank() && !data.isEmpty()) {
                        JsonElement result = match(data.toString(), id);
                        data.setLength(0);
                        if (result != null) {
                            return result == NO_RESULT ? JsonNull.INSTANCE : result;
                        }
                    }
                }
                if (!data.isEmpty()) {
                    JsonElement result = match(data.toString(), id);
                    if (result != null) {
                        return result == NO_RESULT ? JsonNull.INSTANCE : result;
                    }
                }
                throw new IOException("stream ended without a response to " + method);
            }
            JsonElement result = match(String.join("\n", lines.toList()), id);
            if (result == null) {
                throw new IOException("no response to " + method);
            }
            return result == NO_RESULT ? JsonNull.INSTANCE : result;
        }
    }

    private static final JsonElement NO_RESULT = new JsonObject();

    private JsonElement match(String raw, long id) throws RpcError {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(raw);
        } catch (RuntimeException e) {
            problems.accept("ignored a non-JSON message");
            return null;
        }
        if (!parsed.isJsonObject()) {
            return null;
        }
        JsonObject m = parsed.getAsJsonObject();
        if (m.has("method") && !m.has("id")) {
            String method = m.get("method").getAsString();
            if (method.endsWith("/list_changed")) {
                listChanged.run();
            } else if (method.equals("notifications/message")) {
                problems.accept(logLine(Jsons.optObject(m, "params")));
            }
            return null;
        }
        if (!m.has("id") || !m.get("id").isJsonPrimitive() || m.get("id").getAsLong() != id) {
            return null;
        }
        if (m.has("error") && m.get("error").isJsonObject()) {
            JsonObject error = m.getAsJsonObject("error");
            throw new RpcError(error.has("code") ? error.get("code").getAsInt() : RpcError.FAILED,
                    error.has("message") ? error.get("message").getAsString() : "MCP error");
        }
        return m.has("result") ? m.get("result") : NO_RESULT;
    }

    @Override
    void notification(String method, JsonObject params) throws Exception {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("method", method);
        message.add("params", params);
        HttpRequest request = base(15_000).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message.toString())).build();
        HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " for " + method);
        }
    }

    private void listen() {
        try {
            HttpRequest request = base(Long.MAX_VALUE / 4).header("Accept", "text/event-stream").GET().build();
            HttpResponse<Stream<String>> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                return;
            }
            try (Stream<String> lines = response.body()) {
                Iterator<String> it = lines.iterator();
                while (!closed && it.hasNext()) {
                    String line = it.next();
                    if (line.startsWith("data:")) {
                        try {
                            match(line.substring(5).strip(), -1);
                        } catch (RpcError ignored) {
                        }
                    }
                }
            }
        } catch (IOException | InterruptedException ignored) {
        }
    }

    @Override
    boolean alive() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
        Thread t = listener;
        if (t != null) {
            t.interrupt();
        }
        if (session != null) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).header("Mcp-Session-Id", session).DELETE();
                headers.forEach(b::header);
                CLIENT.send(b.build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException | InterruptedException ignored) {
            }
        }
    }
}
