package dafeiyu.web;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.plugin.PluginHost;
import dafeiyu.plugin.PluginView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static dafeiyu.util.Jsons.optString;

final class PluginPageHandler {

    private static final String PREFIX = "/plugins/";
    private static final String SANDBOX = "sandbox allow-scripts allow-forms; default-src 'self' 'unsafe-inline' data: blob:; connect-src 'self'";
    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/json", "png", "image/png", "svg", "image/svg+xml", "jpg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp");

    private final PluginHost host;

    PluginPageHandler(PluginHost host) {
        this.host = host;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            ex.getResponseHeaders().set("Location", PREFIX + rest + "/");
            HttpResponses.send(ex, 301, "text/plain", new byte[0]);
            return;
        }
        String id = rest.substring(0, slash);
        String path = rest.substring(slash + 1);
        PluginView view;
        try {
            view = host.view(id);
        } catch (IllegalArgumentException e) {
            HttpResponses.notFound(ex);
            return;
        }
        if (path.startsWith("api/") || path.equals("api")) {
            api(ex, id, path.substring(Math.min(path.length(), 3)));
            return;
        }
        Path web = view.folder().resolve("web").normalize();
        Path file = web.resolve(path.isEmpty() ? "index.html" : path).normalize();
        if (!file.startsWith(web) || !Files.isRegularFile(file) || Files.size(file) > 10L * 1024 * 1024) {
            HttpResponses.notFound(ex);
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        ex.getResponseHeaders().set("Content-Security-Policy", SANDBOX);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        HttpResponses.send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file));
    }

    private void api(HttpExchange ex, String id, String path) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE");
        if (ex.getRequestMethod().equals("OPTIONS")) {
            HttpResponses.send(ex, 204, "text/plain", new byte[0]);
            return;
        }
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
        }
        try {
            JsonObject r = host.webRequest(id, ex.getRequestMethod(), path.isEmpty() ? "/" : path, ex.getRequestURI().getRawQuery(), body);
            int status = r.has("status") ? r.get("status").getAsInt() : 200;
            String type = optString(r, "contentType").isEmpty() ? "text/plain; charset=utf-8" : optString(r, "contentType");
            HttpResponses.send(ex, Math.max(100, Math.min(599, status)), type, optString(r, "body").getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | IllegalStateException e) {
            HttpResponses.send(ex, 502, "text/plain; charset=utf-8", e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }
}
