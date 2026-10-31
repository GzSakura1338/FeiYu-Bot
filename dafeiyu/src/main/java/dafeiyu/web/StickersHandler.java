package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.sticker.StickerStore.Sticker;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.db.DatabaseException;
import dafeiyu.log.LogBuffer;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class StickersHandler {

    private static final String PREFIX = "/api/agent/stickers";
    private static final int MAX_PAGE = 200;
    private static final List<String> UNUSED = List.of("hash", "url", "firstSeen", "lastSent");

    private record UpdateRequest(String description, Boolean hidden) {}

    private record SendRequest(String session) {}

    private final Gson gson = new Gson();
    private final ChatAgent agent;
    private final QqChatBridge bridge;
    private final LogBuffer log;

    StickersHandler(ChatAgent agent, QqChatBridge bridge, LogBuffer log) {
        this.agent = agent;
        this.bridge = bridge;
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        StickerStore store = agent.stickers().store();
        try {
            if (!method.equals("GET") && !Requests.isJson(ex)) {
                HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/json"));
            } else if ((rest.isEmpty() || rest.equals("/")) && method.equals("GET")) {
                String filter = query(ex, "filter");
                int offset = Math.max(0, number(query(ex, "offset"), 0));
                int limit = Math.clamp(number(query(ex, "limit"), MAX_PAGE), 1, MAX_PAGE);
                List<Sticker> page = store.list(filter.isEmpty() ? "all" : filter, offset, limit + 1);
                JsonArray list = new JsonArray();
                for (Sticker s : page.subList(0, Math.min(limit, page.size()))) {
                    JsonObject item = gson.toJsonTree(s).getAsJsonObject();
                    for (String unused : UNUSED) {
                        item.remove(unused);
                    }
                    list.add(item);
                }
                JsonObject body = new JsonObject();
                body.add("stickers", list);
                body.addProperty("more", page.size() > limit);
                body.addProperty("vision", agent.vision());
                HttpResponses.json(ex, body.toString());
            } else if (rest.equals("/sync") && method.equals("POST")) {
                JsonObject body = new JsonObject();
                body.addProperty("count", bridge.syncFavorites());
                HttpResponses.json(ex, body.toString());
            } else if (rest.endsWith("/thumb") && method.equals("GET")) {
                Sticker sticker = require(rest.substring(1, rest.length() - "/thumb".length()));
                Path thumb = store.thumbnail(sticker);
                Path file = thumb != null ? thumb : store.path(sticker);
                if (file == null || !Files.isRegularFile(file)) {
                    HttpResponses.notFound(ex);
                    return;
                }
                byte[] bytes = Files.readAllBytes(file);
                ex.getResponseHeaders().set("Cache-Control", "max-age=604800");
                HttpResponses.send(ex, 200, thumb != null ? dafeiyu.agent.model.ImageLoader.mime(bytes) : sticker.mime().isEmpty() ? "image/png" : sticker.mime(), bytes);
            } else if (rest.endsWith("/image") && method.equals("GET")) {
                Sticker sticker = require(rest.substring(1, rest.length() - "/image".length()));
                Path file = store.path(sticker);
                if (file == null || !Files.isRegularFile(file)) {
                    HttpResponses.notFound(ex);
                    return;
                }
                ex.getResponseHeaders().set("Cache-Control", "max-age=86400");
                HttpResponses.send(ex, 200, sticker.mime().isEmpty() ? "image/png" : sticker.mime(), Files.readAllBytes(file));
            } else if (rest.endsWith("/send") && method.equals("POST")) {
                Sticker sticker = require(rest.substring(1, rest.length() - "/send".length()));
                String session = Requests.readJson(ex, SendRequest.class).session();
                if (session == null || !session.matches("(group|private):\\d{1,15}")) {
                    throw new IllegalArgumentException("选一个 QQ 会话");
                }
                bridge.sendSticker(session, sticker.id());
                HttpResponses.json(ex, "{}");
            } else if (rest.length() > 1 && method.equals("PUT")) {
                Sticker sticker = require(rest.substring(1));
                UpdateRequest req = Requests.readJson(ex, UpdateRequest.class);
                if (req.description() != null) {
                    store.setDescription(sticker.id(), req.description());
                }
                if (req.hidden() != null) {
                    store.setHidden(sticker.id(), req.hidden());
                }
                HttpResponses.json(ex, gson.toJson(store.get(sticker.id())));
            } else if (rest.length() > 1 && method.equals("DELETE")) {
                Sticker sticker = require(rest.substring(1));
                store.delete(sticker.id());
                log.info("stickers: sticker #" + sticker.id() + " deleted from the console");
                HttpResponses.json(ex, "{}");
            } else {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            }
        } catch (IllegalArgumentException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        } catch (ToolFailure e) {
            HttpResponses.json(ex, 502, error(e.getMessage()));
        } catch (DatabaseException e) {
            log.error(e.getMessage());
            HttpResponses.json(ex, 500, error(e.getMessage()));
        }
    }

    private Sticker require(String raw) {
        long id;
        try {
            id = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("表情包编号不对");
        }
        Sticker sticker = agent.stickers().store().get(id);
        if (sticker == null) {
            throw new IllegalArgumentException("这张表情包不存在");
        }
        return sticker;
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

    private static int number(String raw, int fallback) {
        try {
            return raw.isEmpty() ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body.toString();
    }
}
