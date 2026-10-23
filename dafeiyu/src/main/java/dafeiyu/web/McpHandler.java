package dafeiyu.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.log.LogBuffer;
import dafeiyu.plugin.mcp.McpManager;

import java.io.IOException;

final class McpHandler {

    private static final String PREFIX = "/api/mcp";

    private final McpManager mcp;
    private final LogBuffer log;

    McpHandler(McpManager mcp, LogBuffer log) {
        this.mcp = mcp;
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        String[] parts = rest.isEmpty() || rest.equals("/") ? new String[0] : rest.substring(1).split("/");
        try {
            if (!method.equals("GET") && !Requests.isJson(ex)) {
                HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/json"));
            } else if (parts.length == 0 && method.equals("GET")) {
                HttpResponses.json(ex, list());
            } else if (parts.length == 0 && method.equals("POST")) {
                mcp.save(Requests.readJson(ex, JsonObject.class), null);
                HttpResponses.json(ex, list());
            } else if (parts.length == 1 && method.equals("PUT")) {
                mcp.save(Requests.readJson(ex, JsonObject.class), parts[0]);
                HttpResponses.json(ex, list());
            } else if (parts.length == 1 && method.equals("DELETE")) {
                mcp.delete(parts[0]);
                HttpResponses.json(ex, list());
            } else if (parts.length == 2 && method.equals("POST")) {
                switch (parts[1]) {
                    case "enable" -> mcp.setEnabled(parts[0], true);
                    case "disable" -> mcp.setEnabled(parts[0], false);
                    case "reconnect" -> mcp.reconnect(parts[0]);
                    default -> throw new IllegalArgumentException("没有这个操作：" + parts[1]);
                }
                HttpResponses.json(ex, list());
            } else {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        }
    }

    private String list() {
        JsonArray servers = new JsonArray();
        for (McpManager.ServerView v : mcp.views()) {
            JsonObject o = v.config().toPublic();
            o.addProperty("status", v.status());
            o.addProperty("error", v.error());
            o.addProperty("serverName", v.serverName());
            o.addProperty("connectedAt", v.connectedAt());
            JsonArray tools = new JsonArray();
            for (McpManager.ToolView t : v.tools()) {
                JsonObject item = new JsonObject();
                item.addProperty("name", t.name());
                item.addProperty("original", t.original());
                item.addProperty("description", t.description());
                tools.add(item);
            }
            o.add("tools", tools);
            o.add("resources", entries(v.resources()));
            o.add("prompts", entries(v.prompts()));
            JsonArray stderr = new JsonArray();
            v.stderr().forEach(stderr::add);
            o.add("stderr", stderr);
            servers.add(o);
        }
        JsonObject body = new JsonObject();
        body.add("servers", servers);
        return body.toString();
    }

    private static JsonArray entries(java.util.List<McpManager.EntryView> list) {
        JsonArray out = new JsonArray();
        for (McpManager.EntryView e : list) {
            JsonObject item = new JsonObject();
            item.addProperty("name", e.name());
            item.addProperty("target", e.target());
            item.addProperty("description", e.description());
            out.add(item);
        }
        return out;
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body.toString();
    }
}
