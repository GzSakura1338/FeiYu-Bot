package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.OneBotService;

import java.io.IOException;

final class ApiHandler {

    private final Gson gson = new Gson();
    private final OneBotService onebot;
    private final MessageArchive archive;
    private final LogBuffer log;

    ApiHandler(OneBotService onebot, MessageArchive archive, LogBuffer log) {
        this.onebot = onebot;
        this.archive = archive;
        this.log = log;
    }

    void messages(HttpExchange ex) throws IOException {
        int limit = Math.clamp(Requests.intParam(ex, "limit", 6), 1, 50);
        JsonObject body = new JsonObject();
        body.add("stats", gson.toJsonTree(archive.stats()));
        body.add("messages", gson.toJsonTree(archive.recent(limit)));
        HttpResponses.json(ex, body.toString());
    }

    void status(HttpExchange ex) throws IOException {
        HttpResponses.json(ex, gson.toJson(onebot.status()));
    }

    void logs(HttpExchange ex) throws IOException {
        long after = 0;
        String query = ex.getRequestURI().getQuery();
        if (query != null && query.startsWith("after=")) {
            try {
                after = Long.parseLong(query.substring("after=".length()));
            } catch (NumberFormatException ignored) {
            }
        }
        JsonObject body = new JsonObject();
        body.addProperty("latestId", log.latestId());
        body.add("entries", gson.toJsonTree(log.since(after)));
        HttpResponses.json(ex, body.toString());
    }
}
