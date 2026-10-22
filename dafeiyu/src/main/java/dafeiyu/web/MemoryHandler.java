package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.memory.MemoryStore;
import dafeiyu.agent.memory.MemoryStore.Memory;
import dafeiyu.agent.memory.MemoryStore.Scope;
import dafeiyu.agent.memory.Reflection;
import dafeiyu.agent.memory.SkillPack;
import dafeiyu.agent.memory.SkillStore;
import dafeiyu.agent.persona.Persona;
import dafeiyu.db.DatabaseException;
import dafeiyu.log.LogBuffer;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MemoryHandler {

    private static final String PREFIX = "/api/agent/memory";

    private record ItemRequest(String scope, String content) {}

    private record SkillRequest(String name, String description, String content) {}

    private record ReflectRequest(String session) {}

    private record AutoRequest(Boolean enabled) {}

    private final Gson gson = new Gson();
    private final ChatAgent agent;
    private final MemoryStore memories;
    private final QqDirectory directory;
    private final LogBuffer log;

    MemoryHandler(ChatAgent agent, QqDirectory directory, LogBuffer log) {
        this.agent = agent;
        this.memories = agent.memories();
        this.directory = directory;
        this.log = log;
    }

    static String describe(Reflection.Outcome outcome) {
        if (outcome.added() + outcome.updated() + outcome.deleted() + outcome.skills() == 0 && outcome.diary().isEmpty()) {
            return "回顾完了，这段聊天没什么新的要记";
        }
        String changes = "新记 " + outcome.added() + " 条，改了 " + outcome.updated() + " 条，删了 " + outcome.deleted() + " 条"
                + (outcome.skills() > 0 ? "，学会/改进了 " + outcome.skills() + " 个技能" : "");
        return "回顾完了：" + changes + (outcome.diary().isEmpty() ? "" : "，今天的日记也更新了");
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        try {
            if (rest.equals("/skills/import") && method.equals("POST")) {
                if (!Requests.isUpload(ex)) {
                    HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/octet-stream"));
                } else {
                    importSkills(ex);
                }
            } else if (!method.equals("GET") && !Requests.isJson(ex)) {
                HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/json"));
            } else if ((rest.isEmpty() || rest.equals("/")) && method.equals("GET")) {
                HttpResponses.json(ex, state());
            } else if (rest.equals("/items") && method.equals("GET")) {
                HttpResponses.json(ex, items(query(ex, "scope")));
            } else if (rest.equals("/items") && method.equals("POST")) {
                ItemRequest req = Requests.readJson(ex, ItemRequest.class);
                Memory added = memories.add(persona().id(), req.scope(), label(req.scope()), req.content(), "manual");
                log.info("agent: memory added by hand to " + added.scope());
                HttpResponses.json(ex, items(added.scope()));
            } else if (rest.startsWith("/items/") && method.equals("PUT")) {
                Memory old = require(rest.substring("/items/".length()));
                memories.update(persona().id(), old.id(), Requests.readJson(ex, ItemRequest.class).content());
                HttpResponses.json(ex, items(old.scope()));
            } else if (rest.startsWith("/items/") && method.equals("DELETE")) {
                Memory old = require(rest.substring("/items/".length()));
                memories.delete(persona().id(), old.id());
                HttpResponses.json(ex, items(old.scope()));
            } else if (rest.equals("/skills") && method.equals("GET")) {
                HttpResponses.json(ex, skills());
            } else if (rest.equals("/skills") && method.equals("POST")) {
                SkillRequest req = Requests.readJson(ex, SkillRequest.class);
                SkillStore.Skill added = agent.skills().add(persona().id(), req.name(), req.description(), req.content(), "manual");
                log.info("agent: skill added by hand: " + added.name());
                HttpResponses.json(ex, skills());
            } else if (rest.equals("/skills/download") && method.equals("GET")) {
                download(ex, persona().name() + "-skills.zip", "application/zip", agent.skills().zip(persona().id()));
            } else if (rest.matches("/skills/\\d+/download") && method.equals("GET")) {
                SkillStore.Skill skill = requireSkill(rest.substring(8, rest.lastIndexOf('/')));
                download(ex, SkillStore.fileName(skill.name()) + ".md", "text/markdown; charset=utf-8", skill.markdown().getBytes(StandardCharsets.UTF_8));
            } else if (rest.matches("/skills/\\d+") && method.equals("PUT")) {
                SkillStore.Skill old = requireSkill(rest.substring(8));
                SkillRequest req = Requests.readJson(ex, SkillRequest.class);
                agent.skills().update(persona().id(), old.id(), req.name(), req.description(), req.content());
                HttpResponses.json(ex, skills());
            } else if (rest.matches("/skills/\\d+") && method.equals("DELETE")) {
                agent.skills().delete(persona().id(), requireSkill(rest.substring(8)).id());
                HttpResponses.json(ex, skills());
            } else if (rest.equals("/reflect") && method.equals("POST")) {
                reflect(ex, Requests.readJson(ex, ReflectRequest.class).session());
            } else if (rest.equals("/auto") && method.equals("PUT")) {
                Boolean enabled = Requests.readJson(ex, AutoRequest.class).enabled();
                if (enabled == null) {
                    throw new IllegalArgumentException("缺少 enabled");
                }
                memories.setAuto(enabled);
                log.info("agent: automatic reflection " + (enabled ? "ON" : "OFF"));
                HttpResponses.json(ex, state());
            } else {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            }
        } catch (IllegalArgumentException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        } catch (DatabaseException e) {
            log.error(e.getMessage());
            HttpResponses.json(ex, 500, error(e.getMessage()));
        }
    }

    private String state() {
        JsonObject body = new JsonObject();
        body.addProperty("auto", memories.auto());
        Persona persona = agent.personas().active();
        if (persona == null) {
            body.add("persona", null);
            return body.toString();
        }
        JsonObject who = new JsonObject();
        who.addProperty("id", persona.id());
        who.addProperty("name", persona.name());
        who.addProperty("avatar", persona.avatar());
        body.add("persona", who);
        QqDirectory.Names names = directory.names();
        List<String> owners = agent.personas().owners();
        Map<String, Scope> scopes = new LinkedHashMap<>();
        scopes.put(MemoryStore.SELF, new Scope(MemoryStore.SELF, "", 0, 0));
        scopes.put(MemoryStore.DIARY, new Scope(MemoryStore.DIARY, "", 0, 0));
        for (String qq : owners) {
            scopes.put("user:" + qq, new Scope("user:" + qq, "", 0, 0));
        }
        for (Scope scope : memories.scopes(persona.id())) {
            scopes.put(scope.scope(), scope);
        }
        JsonArray list = new JsonArray();
        for (Scope scope : scopes.values()) {
            JsonObject item = gson.toJsonTree(scope).getAsJsonObject();
            String kind = scope.scope().contains(":") ? scope.scope().substring(0, scope.scope().indexOf(':')) : scope.scope();
            String target = scope.scope().contains(":") ? scope.scope().substring(scope.scope().indexOf(':') + 1) : "";
            item.addProperty("kind", kind);
            item.addProperty("target", target);
            item.addProperty("owner", kind.equals("user") && owners.contains(target));
            item.addProperty("name", name(scope.scope(), scope.label(), names));
            list.add(item);
        }
        JsonObject skill = new JsonObject();
        skill.addProperty("scope", "skill");
        skill.addProperty("kind", "skill");
        skill.addProperty("target", "");
        skill.addProperty("owner", false);
        skill.addProperty("name", "技能");
        skill.addProperty("label", "");
        skill.addProperty("count", agent.skills().count(persona.id()));
        skill.addProperty("updatedAt", agent.skills().latest(persona.id()));
        list.add(skill);
        body.add("scopes", list);
        JsonArray pending = new JsonArray();
        Set<String> running = agent.reflection().running();
        for (String session : memories.pendingSessions()) {
            JsonObject item = new JsonObject();
            item.addProperty("session", session);
            item.addProperty("name", names.of(session));
            item.addProperty("count", memories.pending(session));
            item.addProperty("running", running.contains(session));
            pending.add(item);
        }
        body.add("pending", pending);
        return body.toString();
    }

    private String items(String scope) {
        if (!MemoryStore.validScope(scope)) {
            throw new IllegalArgumentException("记忆分区不对");
        }
        QqDirectory.Names names = directory.names();
        JsonArray list = new JsonArray();
        String personaId = persona().id();
        List<Memory> all = new ArrayList<>(scope.equals(MemoryStore.SELF) ? memories.selfTopics(personaId) : memories.list(personaId, scope));
        if (scope.equals(MemoryStore.SELF)) {
            all.addAll(memories.looseSelf(personaId));
        }
        for (Memory m : all) {
            JsonObject item = gson.toJsonTree(m).getAsJsonObject();
            item.addProperty("sourceName", m.source().equals("manual") ? "手动添加" : m.source().isEmpty() ? "" : names.of(m.source()));
            item.addProperty("title", m.scope().equals(MemoryStore.DIARY) ? MemoryStore.fullDay(m.label())
                    : m.scope().equals(MemoryStore.SELF) && MemoryStore.SELF_TOPICS.contains(m.label()) ? m.label() : "");
            list.add(item);
        }
        JsonObject body = new JsonObject();
        body.addProperty("scope", scope);
        body.add("items", list);
        return body.toString();
    }

    private String skills() {
        return skillList().toString();
    }

    private JsonObject skillList() {
        QqDirectory.Names names = directory.names();
        JsonArray list = new JsonArray();
        for (SkillStore.Skill s : agent.skills().list(persona().id())) {
            JsonObject item = gson.toJsonTree(s).getAsJsonObject();
            item.addProperty("sourceName", switch (s.source()) {
                case "manual" -> "手动添加";
                case "import" -> "导入";
                case "" -> "";
                default -> names.of(s.source());
            });
            list.add(item);
        }
        JsonObject body = new JsonObject();
        body.addProperty("scope", "skill");
        body.add("items", list);
        return body;
    }

    private void importSkills(HttpExchange ex) throws IOException {
        byte[] data;
        try (var in = ex.getRequestBody()) {
            data = in.readNBytes(SkillPack.MAX_BYTES + 1);
        }
        String personaId = persona().id();
        List<SkillPack.Entry> entries = SkillPack.read(data, query(ex, "name"));
        SkillPack.Mode mode = switch (query(ex, "mode")) {
            case "overwrite" -> SkillPack.Mode.OVERWRITE;
            case "skip" -> SkillPack.Mode.SKIP;
            default -> SkillPack.Mode.ASK;
        };
        List<String> conflicts = SkillPack.conflicts(agent.skills(), personaId, entries);
        JsonObject body = new JsonObject();
        if (mode == SkillPack.Mode.ASK && !conflicts.isEmpty()) {
            body.addProperty("needsConfirm", true);
            body.add("names", gson.toJsonTree(entries.stream().map(SkillPack.Entry::name).toList()));
            body.add("conflicts", gson.toJsonTree(conflicts));
            HttpResponses.json(ex, body.toString());
            return;
        }
        SkillPack.Outcome outcome = SkillPack.save(agent.skills(), personaId, entries, mode);
        log.info("agent: imported skills: " + outcome.added().size() + " added, " + outcome.overwritten().size() + " overwritten, "
                + outcome.skipped().size() + " skipped");
        body = skillList();
        body.add("imported", gson.toJsonTree(outcome));
        HttpResponses.json(ex, body.toString());
    }

    private SkillStore.Skill requireSkill(String raw) {
        SkillStore.Skill skill = agent.skills().get(persona().id(), Long.parseLong(raw));
        if (skill == null) {
            throw new IllegalArgumentException("这个技能不存在");
        }
        return skill;
    }

    private static void download(HttpExchange ex, String name, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        HttpResponses.send(ex, 200, type, body);
    }

    private void reflect(HttpExchange ex, String session) throws IOException {
        if (agent.store().active() == null) {
            throw new IllegalArgumentException("还没有选择模型");
        }
        persona();
        List<Reflection.Outcome> outcomes = new ArrayList<>();
        try {
            if (session == null || session.isBlank()) {
                outcomes.addAll(agent.reflection().reflectAll());
            } else {
                Reflection.Outcome outcome = agent.reflection().reflect(session);
                if (outcome != null) {
                    outcomes.add(outcome);
                }
            }
        } catch (AgentException e) {
            HttpResponses.json(ex, 502, error("回顾失败：" + e.getMessage()));
            return;
        }
        QqDirectory.Names names = directory.names();
        JsonArray list = new JsonArray();
        for (Reflection.Outcome outcome : outcomes) {
            JsonObject item = gson.toJsonTree(outcome).getAsJsonObject();
            item.addProperty("name", names.of(outcome.session()));
            item.addProperty("message", describe(outcome));
            list.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("outcomes", list);
        HttpResponses.json(ex, body.toString());
    }

    private Persona persona() {
        Persona persona = agent.personas().active();
        if (persona == null) {
            throw new IllegalArgumentException("记忆跟着人格走，先在「人格」里选一个人格");
        }
        return persona;
    }

    private Memory require(String raw) {
        long id;
        try {
            id = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("记忆编号不对");
        }
        Memory memory = memories.get(persona().id(), id);
        if (memory == null) {
            throw new IllegalArgumentException("这条记忆不存在");
        }
        return memory;
    }

    private String label(String scope) {
        if (scope == null || !scope.startsWith("user:")) {
            return "";
        }
        String name = directory.names().of("private:" + scope.substring(5));
        return name.startsWith("QQ ") ? "" : name;
    }

    private static String name(String scope, String label, QqDirectory.Names names) {
        if (scope.equals(MemoryStore.SELF)) {
            return "关于自己";
        }
        if (scope.equals(MemoryStore.DIARY)) {
            return "日记";
        }
        if (scope.startsWith("group:")) {
            return names.of(scope);
        }
        String name = names.of("private:" + scope.substring(5));
        return name.startsWith("QQ ") && !label.isBlank() ? label : name;
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
