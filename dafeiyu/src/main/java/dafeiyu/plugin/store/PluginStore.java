package dafeiyu.plugin.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;
import dafeiyu.plugin.manifest.Permission;
import dafeiyu.plugin.manifest.SettingsSchema;
import dafeiyu.plugin.rpc.RpcChannel;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class PluginStore {

    public record LogEntry(long id, long at, String level, String text) {}

    public static final int MAX_VALUE_BYTES = 1024 * 1024;
    public static final long MAX_KV_BYTES = 10L * 1024 * 1024;
    public static final int KEEP_LOGS = 2000;
    public static final String KEEP_SECRET = "__keep__";
    private static final String ENABLED_KEY = "plugin.enabled";
    private static final String PREFIX_KEY = "plugin.prefix";
    private static final String DEV_KEY = "plugin.dev";
    private static final String MCP_KEY = "mcp.servers";

    private final Database db;
    private final Settings settings;
    private final ConcurrentHashMap<String, AtomicInteger> inserts = new ConcurrentHashMap<>();

    public PluginStore(Database db) {
        this.db = db;
        this.settings = new Settings(db);
    }

    public synchronized Set<String> enabled() {
        return split(settings.get(ENABLED_KEY));
    }

    public synchronized void setEnabled(String id, boolean on) {
        Set<String> all = enabled();
        if (on) {
            all.add(id);
        } else {
            all.remove(id);
        }
        settings.put(ENABLED_KEY, String.join(",", all));
    }

    public Set<Permission> approved(String id) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        for (String key : split(settings.get(key(id, "approved")))) {
            Permission.of(key).ifPresent(out::add);
        }
        return out;
    }

    public void approve(String id, Set<Permission> permissions) {
        settings.put(key(id, "approved"), permissions.stream().map(Permission::key).sorted().collect(Collectors.joining(",")));
    }

    public boolean sandbox(String id) {
        return !"false".equals(settings.get(key(id, "sandbox")));
    }

    public void setSandbox(String id, boolean on) {
        settings.put(key(id, "sandbox"), String.valueOf(on));
    }

    public boolean qqTools(String id) {
        return "true".equals(settings.get(key(id, "qqTools")));
    }

    public void setQqTools(String id, boolean on) {
        settings.put(key(id, "qqTools"), String.valueOf(on));
    }

    public List<String> groups(String id) {
        return List.copyOf(split(settings.get(key(id, "groups"))));
    }

    public void setGroups(String id, List<String> groups) {
        for (String g : groups) {
            if (!g.matches("\\d{5,12}")) {
                throw new IllegalArgumentException("群号格式不对：" + g);
            }
        }
        settings.put(key(id, "groups"), String.join(",", groups));
    }

    public String prefix() {
        String v = settings.get(PREFIX_KEY);
        return v == null || v.isBlank() ? "#" : v;
    }

    public void setPrefix(String prefix) {
        String p = prefix == null ? "" : prefix.strip();
        if (p.isEmpty() || p.length() > 3 || p.chars().anyMatch(Character::isLetterOrDigit)) {
            throw new IllegalArgumentException("命令前缀要是 1 到 3 个符号，比如 #");
        }
        settings.put(PREFIX_KEY, p);
    }

    public boolean devMode() {
        return "true".equals(settings.get(DEV_KEY));
    }

    public void setDevMode(boolean on) {
        settings.put(DEV_KEY, String.valueOf(on));
    }

    public boolean granted(String key) {
        return split(settings.get("plugin.grants")).contains(key.replace(",", ";"));
    }

    public synchronized void markGranted(String key) {
        Set<String> all = split(settings.get("plugin.grants"));
        all.add(key.replace(",", ";"));
        settings.put("plugin.grants", String.join(",", all));
    }

    public String mcpServers() {
        String v = settings.get(MCP_KEY);
        return v == null || v.isBlank() ? "[]" : v;
    }

    public void setMcpServers(String json) {
        settings.put(MCP_KEY, json);
    }

    public JsonObject values(String id, JsonObject schema) {
        JsonObject stored = rawValues(id);
        for (String secret : SettingsSchema.secrets(schema)) {
            if (stored.has(secret) && stored.get(secret).isJsonPrimitive()) {
                try {
                    stored.addProperty(secret, Secrets.decrypt(stored.get(secret).getAsString()));
                } catch (IllegalStateException e) {
                    stored.remove(secret);
                }
            }
        }
        return SettingsSchema.withDefaults(schema, stored);
    }

    public JsonObject publicValues(String id, JsonObject schema) {
        JsonObject out = SettingsSchema.withDefaults(schema, rawValues(id));
        for (String secret : SettingsSchema.secrets(schema)) {
            boolean set = out.has(secret) && out.get(secret).isJsonPrimitive() && !out.get(secret).getAsString().isEmpty();
            out.add(secret, new JsonPrimitive(set ? KEEP_SECRET : ""));
        }
        return out;
    }

    public List<String> saveValues(String id, JsonObject schema, JsonObject incoming) {
        JsonObject old = rawValues(id);
        JsonObject next = incoming.deepCopy();
        Set<String> secrets = SettingsSchema.secrets(schema);
        JsonObject check = next.deepCopy();
        for (String secret : secrets) {
            JsonElement v = next.get(secret);
            if (v != null && v.isJsonPrimitive() && v.getAsString().equals(KEEP_SECRET)) {
                if (old.has(secret)) {
                    next.add(secret, old.get(secret));
                    check.addProperty(secret, "x");
                } else {
                    next.remove(secret);
                    check.remove(secret);
                }
            }
        }
        List<String> problems = SettingsSchema.validate(schema, check);
        if (!problems.isEmpty()) {
            return problems;
        }
        for (String secret : secrets) {
            JsonElement v = next.get(secret);
            if (v != null && v.isJsonPrimitive() && !v.getAsString().isEmpty() && !Secrets.sealed(v.getAsString())) {
                next.addProperty(secret, Secrets.encrypt(v.getAsString()));
            }
        }
        settings.put(key(id, "settings"), next.toString());
        return List.of();
    }

    private JsonObject rawValues(String id) {
        String v = settings.get(key(id, "settings"));
        if (v == null || v.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement e = JsonParser.parseString(v);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    public String kvGet(String id, String key) {
        return db.one("SELECT value FROM plugin_kv WHERE plugin_id = ? AND key = ?", rs -> rs.getString(1), id, key);
    }

    public void kvSet(String id, String key, String value) throws RpcChannel.RpcError {
        if (key.isEmpty() || key.length() > 200) {
            throw new RpcChannel.RpcError(RpcChannel.RpcError.BAD_PARAMS, "key must be 1-200 characters");
        }
        int size = value.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_VALUE_BYTES) {
            throw new RpcChannel.RpcError(RpcChannel.RpcError.LIMITED, "value is larger than 1 MB");
        }
        Long used = db.one("SELECT COALESCE(SUM(LENGTH(CAST(value AS BLOB))), 0) FROM plugin_kv WHERE plugin_id = ? AND key <> ?",
                rs -> rs.getLong(1), id, key);
        if ((used == null ? 0 : used) + size > MAX_KV_BYTES) {
            throw new RpcChannel.RpcError(RpcChannel.RpcError.LIMITED, "kv storage is full (10 MB per plugin)");
        }
        db.update("""
                INSERT INTO plugin_kv (plugin_id, key, value, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (plugin_id, key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                """, id, key, value, System.currentTimeMillis());
    }

    public boolean kvDelete(String id, String key) {
        return db.update("DELETE FROM plugin_kv WHERE plugin_id = ? AND key = ?", id, key) > 0;
    }

    public List<String> kvKeys(String id, String prefix) {
        return db.list("SELECT key FROM plugin_kv WHERE plugin_id = ? AND key LIKE ? ESCAPE '\\' ORDER BY key LIMIT 1000",
                rs -> rs.getString(1), id, prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    }

    public long kvBytes(String id) {
        Long used = db.one("SELECT COALESCE(SUM(LENGTH(CAST(value AS BLOB))), 0) FROM plugin_kv WHERE plugin_id = ?", rs -> rs.getLong(1), id);
        return used == null ? 0 : used;
    }

    public void log(String id, String level, String text) {
        db.update("INSERT INTO plugin_logs (plugin_id, at, level, text) VALUES (?, ?, ?, ?)", id, System.currentTimeMillis(), level,
                text.length() > 4000 ? text.substring(0, 4000) + "…" : text);
        if (inserts.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet() % 200 == 0) {
            db.update("""
                    DELETE FROM plugin_logs WHERE plugin_id = ? AND id NOT IN (
                        SELECT id FROM plugin_logs WHERE plugin_id = ? ORDER BY id DESC LIMIT ?)
                    """, id, id, KEEP_LOGS);
        }
    }

    public List<LogEntry> logs(String id, long after, int limit) {
        List<LogEntry> rows = db.list("SELECT id, at, level, text FROM plugin_logs WHERE plugin_id = ? AND id > ? ORDER BY id DESC LIMIT ?",
                rs -> new LogEntry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)), id, after, limit);
        return rows.reversed();
    }

    public void forget(String id, boolean data) {
        db.update("DELETE FROM plugin_logs WHERE plugin_id = ?", id);
        setEnabled(id, false);
        if (data) {
            db.update("DELETE FROM plugin_kv WHERE plugin_id = ?", id);
            db.update("DELETE FROM settings WHERE key GLOB ?", "plugin." + id + ".*");
        }
    }

    private static String key(String id, String name) {
        return "plugin." + id + "." + name;
    }

    private static Set<String> split(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
