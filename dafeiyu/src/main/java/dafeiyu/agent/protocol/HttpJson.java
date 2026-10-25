package dafeiyu.agent.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.util.HttpCalls;
import dafeiyu.util.Jsons;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

final class HttpJson {

    record SseEvent(String event, String data) {}

    @FunctionalInterface
    interface SseHandler {
        boolean accept(SseEvent event) throws AgentException;
    }

    static final Duration HEADER_TIMEOUT = Duration.ofSeconds(90);
    static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);
    static final Duration CHUNK_TIMEOUT = Duration.ofSeconds(180);

    private static final int ERROR_BODY_MAX = 64 * 1024;
    private static final HttpCalls.Shared CLIENT = new HttpCalls.Shared(() -> HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-watchdog");
        t.setDaemon(true);
        return t;
    });

    private HttpJson() {}

    static URI uri(String baseUrl, String path) throws AgentException {
        try {
            URI uri = URI.create(baseUrl.replaceAll("/+$", "") + path);
            if (uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new AgentException("invalid Base URL: " + baseUrl);
        }
    }

    static String withDefaultVersion(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        try {
            String path = URI.create(url).getPath();
            return path == null || path.isEmpty() ? url + "/v1" : url;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    static HttpRequest.Builder post(URI uri, JsonObject body) {
        return HttpRequest.newBuilder(uri)
                .timeout(HEADER_TIMEOUT)
                .header("Accept", "text/event-stream, application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
    }

    static HttpRequest.Builder get(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(LIST_TIMEOUT).header("Accept", "application/json").GET();
    }

    static JsonObject send(HttpRequest request) throws AgentException {
        HttpResponse<String> response;
        HttpClient client = CLIENT.get();
        try {
            response = HttpCalls.send(client, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), LIST_TIMEOUT.multipliedBy(2));
        } catch (IOException | InterruptedException e) {
            throw transportError(request, client, e);
        }
        if (response.statusCode() / 100 != 2) {
            throw httpError(response.statusCode(), response.body(), request.uri(), response.headers().map());
        }
        JsonObject json = parseObject(response.body());
        if (json == null) {
            throw notJson(response.body(), request.uri());
        }
        return json;
    }

    static void stream(HttpRequest request, SseHandler handler) throws AgentException {
        HttpResponse<InputStream> response;
        HttpClient client = CLIENT.get();
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            throw transportError(request, client, e);
        }
        Thread reader = Thread.currentThread();
        AtomicLong lastRead = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean();
        InputStream body = response.body();
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() - lastRead.get() > CHUNK_TIMEOUT.toNanos() && stalled.compareAndSet(false, true)) {
                try {
                    body.close();
                } catch (IOException ignored) {
                }
                reader.interrupt();
            }
        }, 5, 5, TimeUnit.SECONDS);
        try (body) {
            String type = response.headers().firstValue("content-type").orElse("").toLowerCase();
            if (response.statusCode() / 100 != 2) {
                throw httpError(response.statusCode(), readLimited(body), request.uri(), response.headers().map());
            }
            if (!type.contains("event-stream")) {
                String text = readLimited(body);
                JsonObject json = parseObject(text);
                String message = ProviderErrors.message(json);
                if (!message.isEmpty()) {
                    throw ProviderErrors.classify(null, message, text, response.headers().map());
                }
                throw json == null ? notJson(text, request.uri())
                        : new AgentException(Kind.INVALID_REQUEST, "provider did not return a stream");
            }
            readEvents(new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)), lastRead, handler);
        } catch (IOException e) {
            if (stalled.get()) {
                Thread.interrupted();
                throw new AgentException(Kind.TIMEOUT, "stream stalled: no data for " + CHUNK_TIMEOUT.toSeconds() + "s");
            }
            throw new AgentException(Kind.TRANSPORT, "stream interrupted: " + e.getMessage());
        } finally {
            watchdog.cancel(false);
            if (stalled.get()) {
                Thread.interrupted();
            }
        }
    }

    private static void readEvents(BufferedReader reader, AtomicLong lastRead, SseHandler handler) throws IOException, AgentException {
        String event = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            lastRead.set(System.nanoTime());
            if (line.isEmpty()) {
                if (!data.isEmpty() && !handler.accept(new SseEvent(event, data.toString()))) {
                    return;
                }
                event = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            }
        }
        if (!data.isEmpty()) {
            handler.accept(new SseEvent(event, data.toString()));
        }
    }

    static JsonObject parseObject(String text) {
        return Jsons.parseObject(text);
    }

    static AgentException streamError(JsonObject chunk, String raw) {
        String message = ProviderErrors.message(chunk);
        return ProviderErrors.classify(null, message.isEmpty() ? "provider reported an error" : message, raw, null);
    }

    static List<String> modelIds(JsonObject json) {
        List<String> models = new ArrayList<>();
        for (JsonElement item : array(json, "data")) {
            if (item.isJsonObject() && !optString(item.getAsJsonObject(), "id").isEmpty()) {
                models.add(optString(item.getAsJsonObject(), "id"));
            }
        }
        models.sort(String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    static JsonArray array(JsonObject json, String key) {
        JsonElement e = json == null ? null : json.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    static int intOr(JsonObject json, String key, int fallback) {
        JsonElement e = json == null ? null : json.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : fallback;
    }

    static int firstInt(JsonObject json, String... paths) {
        for (String path : paths) {
            JsonObject node = json;
            String[] parts = path.split("\\.");
            for (int i = 0; i < parts.length - 1 && node != null; i++) {
                JsonElement next = node.get(parts[i]);
                node = next != null && next.isJsonObject() ? next.getAsJsonObject() : null;
            }
            int value = intOr(node, parts[parts.length - 1], -1);
            if (value >= 0) {
                return value;
            }
        }
        return -1;
    }

    private static String readLimited(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(ERROR_BODY_MAX);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static AgentException transportError(HttpRequest request, HttpClient client, Exception e) {
        if (CLIENT.renewIfDead(client, e)) {
            return new AgentException(Kind.TRANSPORT, "the HTTP client had stopped working and was replaced, try again");
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new AgentException(Kind.LOCAL, "request interrupted");
        }
        if (e instanceof HttpTimeoutException) {
            return new AgentException(Kind.TIMEOUT, "no response within " + request.timeout().orElse(HEADER_TIMEOUT).toSeconds() + "s");
        }
        String host = request.uri().getHost();
        return new AgentException(Kind.TRANSPORT, e instanceof java.net.ConnectException
                ? "cannot connect to " + host + ": " + e.getMessage()
                : "connection to " + host + " dropped: " + e.getMessage());
    }

    private static AgentException httpError(int code, String body, URI uri, java.util.Map<String, List<String>> headers) {
        String detail = ProviderErrors.message(parseObject(body));
        if (detail.isEmpty()) {
            String text = body.strip();
            if (!text.startsWith("<")) {
                detail = abbreviate(text, 200);
            } else if (code >= 500) {
                java.util.regex.Matcher title = java.util.regex.Pattern.compile("(?is)<title>(.*?)</title>").matcher(text);
                String server = headers == null ? "" : headers.entrySet().stream().filter(h -> h.getKey().equalsIgnoreCase("server"))
                        .flatMap(h -> h.getValue().stream()).findFirst().orElse("");
                detail = "the gateway or CDN in front of " + uri.getHost() + " returned an error page"
                        + (title.find() ? " (" + abbreviate(title.group(1).strip(), 60) + ")" : "") + (server.isEmpty() ? "" : ", server: " + server);
            } else {
                detail = "got a web page from " + uri + ", check the Base URL";
            }
        }
        return ProviderErrors.classify(code, "HTTP " + code + (detail.isEmpty() ? "" : ": " + detail), body, headers);
    }

    private static AgentException notJson(String body, URI uri) {
        String text = body.strip();
        return new AgentException(Kind.INVALID_REQUEST, text.startsWith("<")
                ? "got a web page instead of an API response from " + uri + ", check the Base URL"
                : "response is not JSON: " + abbreviate(text, 120));
    }
}
