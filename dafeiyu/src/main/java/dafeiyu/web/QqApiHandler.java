package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.qq.QqApi;
import dafeiyu.agent.qq.QqApiPolicy;
import dafeiyu.agent.qq.QqApiPolicy.Access;
import dafeiyu.agent.qq.QqApiTools;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.log.LogBuffer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class QqApiHandler {

    private static final String PREFIX = "/api/agent/qqapi";

    private record UpdateRequest(Boolean enabled, Map<String, Boolean> actions, Map<String, String> access) {}

    private record SelfTestRequest(String groupId) {}

    private final Gson gson = new Gson();
    private final QqChatBridge bridge;
    private final QqApiTools tools;
    private final LogBuffer log;

    QqApiHandler(QqChatBridge bridge, LogBuffer log) {
        this.bridge = bridge;
        this.tools = bridge.qqApi();
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        try {
            if (rest.equals("/selftest") && method.equals("POST") && Requests.isJson(ex)) {
                SelfTestRequest body = Requests.readJson(ex, SelfTestRequest.class);
                HttpResponses.json(ex, gson.toJson(Map.of("checks", bridge.qqToolsSelfTest(body == null ? null : body.groupId()))));
            } else if (!rest.isEmpty() && !rest.equals("/")) {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            } else if (method.equals("GET")) {
                HttpResponses.json(ex, state());
            } else if (method.equals("PUT") && Requests.isJson(ex)) {
                UpdateRequest body = Requests.readJson(ex, UpdateRequest.class);
                Map<String, Boolean> changes = new LinkedHashMap<>();
                if (body.actions() != null) {
                    body.actions().forEach((name, on) -> changes.put(known(name), on != null && on));
                }
                Map<String, Access> kinds = new LinkedHashMap<>();
                if (body.access() != null) {
                    body.access().forEach((name, kind) -> kinds.put(known(name), access(kind)));
                }
                boolean enabled = body.enabled() == null ? tools.policy().config().enabled() : body.enabled();
                tools.policy().update(enabled, changes, kinds);
                log.info("agent: QQ API tools " + (enabled ? "ON" : "OFF") + (changes.isEmpty() ? "" : ", switched " + changes)
                        + (kinds.isEmpty() ? "" : ", marked " + kinds));
                HttpResponses.json(ex, state());
            } else {
                HttpResponses.json(ex, 405, error("不支持这个请求"));
            }
        } catch (IllegalArgumentException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        }
    }

    private String known(String name) {
        if (tools.api().get(name) == null) {
            throw new IllegalArgumentException("没有这个接口：" + name);
        }
        return name;
    }

    private static Access access(String kind) {
        try {
            return Access.valueOf(String.valueOf(kind));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("access 只能是 READ 或 WRITE");
        }
    }

    private String state() {
        QqApi api = tools.api();
        QqApiPolicy.Config config = tools.policy().config();
        JsonObject body = new JsonObject();
        body.addProperty("enabled", config.enabled());
        body.addProperty("version", api.version());
        body.add("categories", gson.toJsonTree(api.categories()));
        JsonArray actions = new JsonArray();
        for (QqApi.Action a : api.all()) {
            JsonObject o = gson.toJsonTree(a).getAsJsonObject();
            o.addProperty("access", config.access().getOrDefault(a.name(), QqApiPolicy.defaultAccess(a.name())).name());
            o.addProperty("locked", QqApiPolicy.locked(a.name()));
            o.addProperty("lockReason", QqApiPolicy.lockReason(a.name()));
            o.addProperty("on", config.overrides().getOrDefault(a.name(), QqApiPolicy.defaultAllowed(a.name())));
            actions.add(o);
        }
        body.add("actions", actions);
        return body.toString();
    }

    private static String error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o.toString();
    }
}
