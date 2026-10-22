package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.memory.Mood;
import dafeiyu.agent.mind.Mind;
import dafeiyu.agent.persona.Persona;
import dafeiyu.log.LogBuffer;

import java.io.IOException;

final class MindHandler {

    private static final String PREFIX = "/api/agent/mind";

    private record SwitchRequest(Boolean enabled) {}

    private record PaceRequest(String pace) {}

    private final Gson gson = new Gson();
    private final ChatAgent agent;
    private final Mind mind;
    private final LogBuffer log;

    MindHandler(ChatAgent agent, Mind mind, LogBuffer log) {
        this.agent = agent;
        this.mind = mind;
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        try {
            if (!method.equals("GET") && !Requests.isJson(ex)) {
                HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/json"));
            } else if ((rest.isEmpty() || rest.equals("/")) && method.equals("GET")) {
                HttpResponses.json(ex, state());
            } else if ((rest.isEmpty() || rest.equals("/")) && method.equals("PUT")) {
                Boolean enabled = Requests.readJson(ex, SwitchRequest.class).enabled();
                if (enabled == null) {
                    throw new IllegalArgumentException("缺少 enabled");
                }
                agent.store().setFreeWill(enabled);
                log.info("mind: free will " + (enabled ? "ON" : "OFF"));
                if (enabled) {
                    mind.wakeUp();
                }
                HttpResponses.json(ex, state());
            } else if (rest.equals("/pace") && method.equals("PUT")) {
                agent.store().setMindPace(String.valueOf(Requests.readJson(ex, PaceRequest.class).pace()));
                mind.wakeUp();
                HttpResponses.json(ex, state());
            } else if (rest.equals("/think") && method.equals("POST")) {
                if (!mind.wakeUp()) {
                    throw new IllegalArgumentException("现在没法想：" + mind.status().why());
                }
                HttpResponses.json(ex, state());
            } else {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            }
        } catch (IllegalArgumentException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        }
    }

    private String state() {
        JsonObject body = gson.toJsonTree(mind.status()).getAsJsonObject();
        body.addProperty("session", Mind.SESSION);
        Persona persona = agent.personas().active();
        body.addProperty("name", persona == null ? "" : persona.name());
        Mood.State mood = agent.mood().current(persona == null ? null : persona.id());
        body.add("mood", gson.toJsonTree(mood));
        return body.toString();
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body.toString();
    }
}
