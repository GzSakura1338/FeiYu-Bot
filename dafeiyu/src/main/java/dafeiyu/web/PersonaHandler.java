package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.persona.Persona;
import dafeiyu.agent.persona.PersonaStore;
import dafeiyu.db.DatabaseException;
import dafeiyu.log.LogBuffer;

import java.io.IOException;
import java.util.List;

final class PersonaHandler {

    private static final String PREFIX = "/api/personas";

    private record OwnersRequest(List<String> owners) {}

    private record BindingRequest(String session, String persona) {}

    private record BlockRequest(Boolean enabled, Integer threshold, List<String> remove, String add, String reason) {}

    private final Gson gson = new Gson();
    private final PersonaStore store;
    private final dafeiyu.agent.model.AgentStore agentStore;
    private final java.util.function.Function<String, String> nickname;
    private final LogBuffer log;

    PersonaHandler(PersonaStore store, dafeiyu.agent.model.AgentStore agentStore, java.util.function.Function<String, String> nickname, LogBuffer log) {
        this.store = store;
        this.agentStore = agentStore;
        this.nickname = nickname;
        this.log = log;
    }

    private com.google.gson.JsonObject blockState() {
        var list = new java.util.LinkedHashMap<>(agentStore.blockList());
        boolean named = false;
        for (var e : list.entrySet()) {
            Object name = e.getValue() == null ? null : e.getValue().get("name");
            if (name == null || name.toString().isBlank() || name.toString().contains("（QQ ")) {
                String found = nickname.apply(e.getKey());
                if (!found.isBlank()) {
                    var entry = new java.util.LinkedHashMap<String, Object>(e.getValue() == null ? java.util.Map.of() : e.getValue());
                    entry.put("name", found);
                    e.setValue(entry);
                    named = true;
                }
            }
        }
        if (named) {
            agentStore.setBlockList(list);
        }
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.addProperty("enabled", agentStore.blockEnabled());
        out.addProperty("threshold", agentStore.blockThreshold());
        out.add("list", gson.toJsonTree(list));
        out.add("strikes", gson.toJsonTree(agentStore.blockStrikes()));
        return out;
    }

    private void block(String userId, String reason) {
        if (!userId.matches("\\d{5,12}")) {
            throw new IllegalArgumentException("QQ 号要是 5 到 12 位数字");
        }
        if (store.isOwner(userId)) {
            throw new IllegalArgumentException("那是主人，不能拉黑");
        }
        var list = new java.util.LinkedHashMap<>(agentStore.blockList());
        if (list.containsKey(userId)) {
            throw new IllegalArgumentException("QQ " + userId + " 已经在黑名单里了");
        }
        var entry = new java.util.LinkedHashMap<String, Object>();
        entry.put("name", nickname.apply(userId));
        entry.put("at", System.currentTimeMillis());
        entry.put("reason", reason == null || reason.isBlank() ? "主人在控制台拉黑" : reason.strip());
        list.put(userId, entry);
        var strikes = new java.util.LinkedHashMap<>(agentStore.blockStrikes());
        strikes.remove(userId);
        agentStore.setBlockList(list);
        agentStore.setBlockStrikes(strikes);
        log.info("agent: blocked QQ " + userId + " from the console");
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        try {
            if (!method.equals("GET") && !Requests.isJson(ex)) {
                HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/json"));
            } else if (rest.isEmpty() || rest.equals("/")) {
                switch (method) {
                    case "GET" -> HttpResponses.json(ex, state());
                    case "POST" -> create(ex);
                    default -> HttpResponses.json(ex, 405, error("不支持的请求方法"));
                }
            } else if (rest.equals("/blocklist") && method.equals("GET")) {
                HttpResponses.json(ex, blockState().toString());
            } else if (rest.equals("/blocklist") && method.equals("PUT")) {
                BlockRequest req = Requests.readJson(ex, BlockRequest.class);
                if (req.enabled() != null) {
                    agentStore.setBlockEnabled(req.enabled());
                }
                if (req.threshold() != null) {
                    agentStore.setBlockThreshold(req.threshold());
                }
                if (req.remove() != null) {
                    var list = new java.util.LinkedHashMap<>(agentStore.blockList());
                    var strikes = new java.util.LinkedHashMap<>(agentStore.blockStrikes());
                    for (String id : req.remove()) {
                        list.remove(id);
                        strikes.remove(id);
                    }
                    agentStore.setBlockList(list);
                    agentStore.setBlockStrikes(strikes);
                }
                if (req.add() != null && !req.add().isBlank()) {
                    block(req.add().strip(), req.reason());
                }
                log.info("agent: blocklist changed (enabled " + agentStore.blockEnabled()
                        + ", threshold " + agentStore.blockThreshold() + ", size " + agentStore.blockList().size() + ")");
                HttpResponses.json(ex, blockState().toString());
            } else if (rest.equals("/owners") && method.equals("PUT")) {
                List<String> owners = store.setOwners(Requests.readJson(ex, OwnersRequest.class).owners());
                log.info("agent: owner QQ set to " + (owners.isEmpty() ? "(none)" : String.join(",", owners)));
                HttpResponses.json(ex, state());
            } else if (rest.equals("/binding") && method.equals("PUT")) {
                BindingRequest body = Requests.readJson(ex, BindingRequest.class);
                store.bind(body.session(), body.persona());
                String bound = store.boundTo(body.session());
                log.info("agent: persona for " + body.session() + " " + (bound == null ? "follows the global one" : "set to " + bound));
                HttpResponses.json(ex, state());
            } else if (rest.equals("/none/activate") && method.equals("POST")) {
                store.setActive(null);
                log.info("agent: persona cleared");
                HttpResponses.json(ex, state());
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

    private void create(HttpExchange ex) throws IOException {
        Persona created = store.add(read(ex));
        if (!store.hasChoice()) {
            store.setActive(created.id());
        }
        log.info("agent: persona added: " + created.name());
        HttpResponses.json(ex, state());
    }

    private void update(HttpExchange ex, String id) throws IOException {
        require(id);
        Persona next = read(ex).withId(id);
        store.update(next);
        log.info("agent: persona updated: " + next.name());
        HttpResponses.json(ex, state());
    }

    private void delete(HttpExchange ex, String id) throws IOException {
        Persona persona = require(id);
        store.remove(id);
        log.info("agent: persona deleted: " + persona.name());
        HttpResponses.json(ex, state());
    }

    private void activate(HttpExchange ex, String id) throws IOException {
        Persona persona = require(id);
        if (!id.equals(store.activeId())) {
            store.setActive(id);
            log.info("agent: persona switched to " + persona.name());
        }
        HttpResponses.json(ex, state());
    }

    private Persona read(HttpExchange ex) throws IOException {
        Persona persona = Requests.readJson(ex, Persona.class);
        String problem = persona.validate();
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return persona;
    }

    private Persona require(String id) {
        Persona persona = store.get(id);
        if (persona == null) {
            throw new IllegalArgumentException("人格不存在，可能已被删除");
        }
        return persona;
    }

    private String state() {
        JsonObject body = new JsonObject();
        body.addProperty("active", store.activeId());
        body.add("personas", gson.toJsonTree(store.list()));
        body.add("owners", gson.toJsonTree(store.owners()));
        body.add("bindings", gson.toJsonTree(store.bindings()));
        return body.toString();
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body.toString();
    }
}
