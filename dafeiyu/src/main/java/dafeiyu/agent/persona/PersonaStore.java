package dafeiyu.agent.persona;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PersonaStore {

    private static final String ACTIVE_KEY = "agent.persona";
    private static final String OWNERS_KEY = "agent.owners";
    private static final String COLUMNS = "id, name, avatar, prompt";
    private static final String BINDINGS_KEY = "agent.persona.bindings";
    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type BINDINGS = new TypeToken<Map<String, String>>() {}.getType();

    private final Database db;
    private final Settings settings;

    public PersonaStore(Database db) {
        this.db = db;
        this.settings = new Settings(db);
    }

    public synchronized List<Persona> list() {
        return db.list("SELECT " + COLUMNS + " FROM personas ORDER BY sort, created_at", PersonaStore::map);
    }

    public synchronized Persona get(String id) {
        return db.one("SELECT " + COLUMNS + " FROM personas WHERE id = ?", PersonaStore::map, id);
    }

    public synchronized String activeId() {
        String id = settings.get(ACTIVE_KEY);
        return id == null || id.isEmpty() || get(id) == null ? null : id;
    }

    public synchronized List<String> owners() {
        String value = settings.get(OWNERS_KEY);
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }

    public synchronized List<String> setOwners(List<String> owners) {
        List<String> clean = owners == null ? List.of() : owners.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        for (String qq : clean) {
            if (!qq.matches("\\d{5,12}")) {
                throw new IllegalArgumentException("QQ 号格式不对：" + qq);
            }
        }
        if (clean.size() > 20) {
            throw new IllegalArgumentException("主人最多 20 个");
        }
        settings.put(OWNERS_KEY, String.join(",", clean));
        return clean;
    }

    public boolean isOwner(String qq) {
        return qq != null && owners().contains(qq);
    }

    public synchronized boolean hasChoice() {
        return settings.get(ACTIVE_KEY) != null;
    }

    public synchronized Persona active() {
        String id = activeId();
        return id == null ? null : get(id);
    }

    public synchronized Persona add(Persona persona) {
        Persona created = persona.withId(UUID.randomUUID().toString().substring(0, 8));
        db.update("""
                INSERT INTO personas (id, name, avatar, prompt, sort, created_at)
                VALUES (?, ?, ?, ?, (SELECT COALESCE(MAX(sort), 0) + 1 FROM personas), ?)
                """, created.id(), created.name(), created.avatar(), created.prompt(),
                System.currentTimeMillis());
        return created;
    }

    public synchronized boolean update(Persona persona) {
        return db.update("UPDATE personas SET name = ?, avatar = ?, prompt = ? WHERE id = ?",
                persona.name(), persona.avatar(), persona.prompt(), persona.id()) > 0;
    }

    public synchronized boolean remove(String id) {
        boolean removed = db.update("DELETE FROM personas WHERE id = ?", id) > 0;
        if (removed && id.equals(settings.get(ACTIVE_KEY))) {
            settings.put(ACTIVE_KEY, "");
        }
        if (removed) {
            Map<String, String> all = bindings();
            if (all.values().removeIf(id::equals)) {
                settings.put(BINDINGS_KEY, GSON.toJson(all));
            }
        }
        return removed;
    }

    public synchronized void setActive(String id) {
        settings.put(ACTIVE_KEY, id == null ? "" : id);
    }

    public synchronized Map<String, String> bindings() {
        try {
            Map<String, String> saved = GSON.fromJson(settings.get(BINDINGS_KEY), BINDINGS);
            return saved == null ? new LinkedHashMap<>() : new LinkedHashMap<>(saved);
        } catch (JsonParseException e) {
            return new LinkedHashMap<>();
        }
    }

    public synchronized String boundTo(String session) {
        String id = session == null ? null : bindings().get(session);
        return id != null && get(id) != null ? id : null;
    }

    public synchronized void bind(String session, String personaId) {
        if (session == null || !(session.startsWith("group:") || session.startsWith("private:"))) {
            throw new IllegalArgumentException("只有群聊和私聊能单独选人格");
        }
        Map<String, String> all = bindings();
        if (personaId == null || personaId.isBlank()) {
            all.remove(session);
        } else if (get(personaId) == null) {
            throw new IllegalArgumentException("没有这个人格");
        } else {
            all.put(session, personaId);
        }
        settings.put(BINDINGS_KEY, GSON.toJson(all));
    }

    public synchronized Persona forSession(String session) {
        String id = boundTo(session);
        return id == null ? active() : get(id);
    }

    private static Persona map(ResultSet rs) throws SQLException {
        return new Persona(rs.getString("id"), rs.getString("name"), rs.getString("avatar"), rs.getString("prompt"));
    }
}
