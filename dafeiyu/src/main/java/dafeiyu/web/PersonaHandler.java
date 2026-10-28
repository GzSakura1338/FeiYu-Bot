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

    private record BlockRequest(Boolean enabled, Integer threshold, List<String> remove) {}

    private final Gson gson = new Gson();
    private final PersonaStore store;
    private final dafeiyu.agent.model.AgentStore agentStore;
    private final LogBuffer log;

    PersonaHandler(PersonaStore store, dafeiyu.agent.model.AgentStore agentStore, LogBuffer log) {
        this.store = store;
        this.agentStore = agentStore;
        this.log = log;
    }

    private com.google.gson.JsonObject blockState() {
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.addProperty("enabled", agentStore.blockEnabled());
        out.addProperty("threshold", agentStore.blockThreshold());
        out.add("list", gson.toJsonTree(agentStore.blockList()));
        out.add("strikes", gson.toJsonTree(agentStore.blockStrikes()));
        return out;
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
                log.info("agent: blocklist changed (enabled " + agentStore.blockEnabled()
                        + ", threshold " + agentStore.blockThreshold() + ", size " + agentStore.blockList().size() + ")");
                HttpResponses.json(ex, blockState().toString());
            } else if (rest.equals("/owners") && method.equals("PUT")) {
                List<String> owners = store.setOwners(Requests.readJson(ex, OwnersRequest.class).owners());
                log.info("agent: owner QQ set to " + (owners.isEmpty() ? "(none)" : String.join(",", owners)));
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
        return body.toString();
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body.toString();
    }
}
