package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.config.WsProfile;
import dafeiyu.config.WsProfileStore;
import dafeiyu.db.DatabaseException;
import dafeiyu.log.LogBuffer;
import dafeiyu.onebot.NapCatService;

import java.io.IOException;

final class WsProfileHandler {

    private static final String PREFIX = "/api/ws-profiles";

    private final Gson gson = new Gson();
    private final WsProfileStore store;
    private final NapCatService napcat;
    private final LogBuffer log;

    WsProfileHandler(WsProfileStore store, NapCatService napcat, LogBuffer log) {
        this.store = store;
        this.napcat = napcat;
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        try {
            if (!method.equals("GET") && !Requests.isJson(ex)) {
                HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/json"));
            } else if (rest.isEmpty() || rest.equals("/")) {
                switch (method) {
                    case "GET" -> list(ex);
                    case "POST" -> create(ex);
                    default -> HttpResponses.json(ex, 405, error("不支持的请求方法"));
                }
            } else if (rest.endsWith("/activate") && method.equals("POST")) {
                activate(ex, rest.substring(1, rest.length() - "/activate".length()));
            } else {
                String id = rest.substring(1);
                switch (method) {
                    case "PUT" -> update(ex, id);
                    case "DELETE" -> delete(ex, id);
                    default -> HttpResponses.json(ex, 405, error("不支持的请求方法"));
                }
            }
        } catch (IllegalArgumentException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        } catch (DatabaseException e) {
            log.error(e.getMessage());
            HttpResponses.json(ex, 500, error(e.getMessage()));
        }
    }

    private void list(HttpExchange ex) throws IOException {
        HttpResponses.json(ex, gson.toJson(store.snapshot()));
    }

    private void create(HttpExchange ex) throws IOException {
        WsProfile profile = readProfile(ex);
        WsProfile created = store.add(profile);
        log.info("ws profile added: " + created.name() + " " + created.settings().url());
        HttpResponses.json(ex, gson.toJson(store.snapshot()));
    }

    private void update(HttpExchange ex, String id) throws IOException {
        WsProfile old = require(id);
        WsProfile next = readProfile(ex).withId(id);
        boolean isActive = id.equals(store.active().id());
        if (isActive && !next.settings().equals(old.settings())) {
            napcat.reconfigure(next.settings());
        }
        store.update(next);
        log.info("ws profile updated: " + next.name() + " " + next.settings().url());
        HttpResponses.json(ex, gson.toJson(store.snapshot()));
    }

    private void delete(HttpExchange ex, String id) throws IOException {
        WsProfile profile = require(id);
        if (id.equals(store.active().id())) {
            throw new IllegalArgumentException("不能删除正在使用的配置，请先切换到其他配置");
        }
        store.remove(id);
        log.info("ws profile deleted: " + profile.name());
        HttpResponses.json(ex, gson.toJson(store.snapshot()));
    }

    private void activate(HttpExchange ex, String id) throws IOException {
        WsProfile profile = require(id);
        if (!id.equals(store.active().id())) {
            napcat.reconfigure(profile.settings());
            store.setActive(id);
            log.info("ws profile activated: " + profile.name());
        }
        HttpResponses.json(ex, gson.toJson(store.snapshot()));
    }

    private WsProfile require(String id) {
        WsProfile profile = store.get(id);
        if (profile == null) {
            throw new IllegalArgumentException("配置不存在，可能已被删除");
        }
        return profile;
    }

    private WsProfile readProfile(HttpExchange ex) throws IOException {
        WsProfile profile = Requests.readJson(ex, WsProfile.class);
        String problem = profile.validate();
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return profile;
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body.toString();
    }
}
