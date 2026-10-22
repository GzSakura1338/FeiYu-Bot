package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Workspace;
import dafeiyu.log.LogBuffer;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class FilesHandler {

    private static final String PREFIX = "/api/agent/files";
    private static final long MAX_DOWNLOAD = 200L * 1024 * 1024;
    private static final long MAX_PREVIEW = 20L * 1024 * 1024;

    private final Gson gson = new Gson();
    private final ChatAgent agent;
    private final LogBuffer log;

    FilesHandler(ChatAgent agent, LogBuffer log) {
        this.agent = agent;
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        Workspace ws = agent.workspaces().workspace();
        if (ws == null) {
            HttpResponses.json(ex, 409, error("还没有设置工作目录"));
            return;
        }
        String path = query(ex, "path");
        try {
            if ((rest.isEmpty() || rest.equals("/")) && method.equals("GET")) {
                Path dir = ws.existingDirectory(path);
                JsonObject body = new JsonObject();
                body.addProperty("root", ws.name());
                body.addProperty("path", ws.relative(dir));
                body.add("entries", gson.toJsonTree(ws.entries(dir)));
                HttpResponses.json(ex, body.toString());
            } else if (rest.equals("/download") && method.equals("GET")) {
                download(ex, ws, ws.existingFile(path));
            } else if (rest.equals("/raw") && method.equals("GET")) {
                raw(ex, ws.existingFile(path));
            } else if ((rest.isEmpty() || rest.equals("/")) && method.equals("DELETE")) {
                Path target = ws.resolve(path);
                if (!Files.exists(target)) {
                    throw new ToolFailure("找不到 " + path);
                }
                JsonObject body = new JsonObject();
                if (target.startsWith(ws.root().resolve(Workspace.TRASH))) {
                    ws.purge(target);
                    body.addProperty("message", "已彻底删除");
                } else {
                    body.addProperty("message", "已移到回收站：" + ws.trash(target));
                }
                log.info("agent: workspace item removed from the console: " + ws.relative(target));
                HttpResponses.json(ex, body.toString());
            } else {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            }
        } catch (ToolFailure e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        }
    }

    private static void download(HttpExchange ex, Workspace ws, Path file) throws IOException, ToolFailure {
        if (Files.size(file) > MAX_DOWNLOAD) {
            throw new ToolFailure("文件太大，超过 200 MB");
        }
        String type = Files.probeContentType(file);
        String name = file.getFileName().toString();
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        HttpResponses.send(ex, 200, type == null ? "application/octet-stream" : type, Files.readAllBytes(file));
    }

    private static void raw(HttpExchange ex, Path file) throws IOException, ToolFailure {
        if (Files.size(file) > MAX_PREVIEW) {
            throw new ToolFailure("文件太大，超过 20 MB，没法预览");
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String type = Files.probeContentType(file);
        if (name.endsWith(".svg")) {
            type = "image/svg+xml";
        } else if (name.endsWith(".html") || name.endsWith(".htm")) {
            type = "text/html; charset=utf-8";
        } else if (type == null || !(type.startsWith("image/") || type.equals("application/pdf"))) {
            type = "text/plain; charset=utf-8";
        }
        ex.getResponseHeaders().set("Content-Security-Policy", "sandbox allow-scripts allow-popups allow-forms allow-modals");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        HttpResponses.send(ex, 200, type, Files.readAllBytes(file));
    }

    private static String query(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith(key + "=")) {
                    return URLDecoder.decode(pair.substring(key.length() + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body.toString();
    }
}
