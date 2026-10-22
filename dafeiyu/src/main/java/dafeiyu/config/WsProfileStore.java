package dafeiyu.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class WsProfileStore {

    public record Snapshot(String active, List<WsProfile> profiles) {}

    private static final String ACTIVE_KEY = "ws.active";

    private final Database db;
    private final Settings settings;

    private WsProfileStore(Database db) {
        this.db = db;
        this.settings = new Settings(db);
    }

    public static WsProfileStore open(Database db, Path legacyJson, WsSettings fallback) throws IOException {
        WsProfileStore store = new WsProfileStore(db);
        if (store.count() == 0) {
            if (Files.exists(legacyJson)) {
                store.importLegacy(legacyJson);
            } else {
                store.add(new WsProfile(null, "默认", fallback.host(), fallback.port(), fallback.path(), fallback.token()));
            }
        }
        if (store.active() == null) {
            store.setActive(store.list().getFirst().id());
        }
        return store;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(settings.get(ACTIVE_KEY), list());
    }

    public synchronized WsProfile active() {
        String id = settings.get(ACTIVE_KEY);
        return id == null ? null : get(id);
    }

    public synchronized WsProfile get(String id) {
        return db.one("SELECT id, name, host, port, path, token FROM ws_profiles WHERE id = ?", WsProfileStore::map, id);
    }

    public synchronized WsProfile add(WsProfile profile) {
        WsProfile created = profile.withId(UUID.randomUUID().toString().substring(0, 8));
        db.update("""
                INSERT INTO ws_profiles (id, name, host, port, path, token, sort, created_at)
                VALUES (?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort), 0) + 1 FROM ws_profiles), ?)
                """, created.id(), created.name(), created.host(), created.port(), created.path(), created.token(),
                System.currentTimeMillis());
        return created;
    }

    public synchronized boolean update(WsProfile profile) {
        return db.update("UPDATE ws_profiles SET name = ?, host = ?, port = ?, path = ?, token = ? WHERE id = ?",
                profile.name(), profile.host(), profile.port(), profile.path(), profile.token(), profile.id()) > 0;
    }

    public synchronized boolean remove(String id) {
        return db.update("DELETE FROM ws_profiles WHERE id = ?", id) > 0;
    }

    public synchronized void setActive(String id) {
        settings.put(ACTIVE_KEY, id);
    }

    private List<WsProfile> list() {
        return db.list("SELECT id, name, host, port, path, token FROM ws_profiles ORDER BY sort, created_at", WsProfileStore::map);
    }

    private int count() {
        return db.one("SELECT COUNT(*) FROM ws_profiles", rs -> rs.getInt(1));
    }

    private static WsProfile map(ResultSet rs) throws SQLException {
        return new WsProfile(rs.getString("id"), rs.getString("name"), rs.getString("host"),
                rs.getInt("port"), rs.getString("path"), rs.getString("token"));
    }

    private void importLegacy(Path file) throws IOException {
        Snapshot legacy;
        try {
            legacy = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), Snapshot.class);
        } catch (JsonParseException e) {
            throw new IOException(file + " 格式不对：" + e.getMessage(), e);
        }
        if (legacy == null || legacy.profiles() == null || legacy.profiles().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        db.transaction(c -> {
            int sort = 1;
            for (WsProfile p : legacy.profiles()) {
                Database.update(c, """
                        INSERT INTO ws_profiles (id, name, host, port, path, token, sort, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, p.id(), p.name(), p.host(), p.port(), p.path(), p.token() == null ? "" : p.token(), sort++, now);
            }
            return null;
        });
        if (legacy.active() != null && get(legacy.active()) != null) {
            setActive(legacy.active());
        }
        Files.move(file, file.resolveSibling(file.getFileName() + ".imported"), StandardCopyOption.REPLACE_EXISTING);
    }
}
