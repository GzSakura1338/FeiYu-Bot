package dafeiyu.agent.persona;

import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class PersonaStore {

    private static final String ACTIVE_KEY = "agent.persona";
    private static final String OWNERS_KEY = "agent.owners";
    private static final String COLUMNS = "id, name, avatar, prompt";

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
        return removed;
    }

    public synchronized void setActive(String id) {
        settings.put(ACTIVE_KEY, id == null ? "" : id);
    }

    private static Persona map(ResultSet rs) throws SQLException {
        return new Persona(rs.getString("id"), rs.getString("name"), rs.getString("avatar"), rs.getString("prompt"));
    }
}
