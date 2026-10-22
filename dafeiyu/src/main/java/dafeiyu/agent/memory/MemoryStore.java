package dafeiyu.agent.memory;

import dafeiyu.agent.qq.QqMessages;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static dafeiyu.util.Texts.abbreviate;

public final class MemoryStore {

    public record Memory(long id, String scope, String label, String content, String source, long createdAt, long updatedAt) {}

    public record Scope(String scope, String label, int count, long updatedAt) {}

    public record Turn(long id, String role, String content, long createdAt) {}

    public static final String SELF = "self";
    public static final String DIARY = "diary";
    public static final int MAX_CONTENT = 200;
    private static final Pattern SCOPE = Pattern.compile("self|diary|(user|group):\\d{5,12}");
    private static final String AUTO_KEY = "agent.memory.auto";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);
    private static final String COLUMNS = "id, scope, label, content, source, created_at, updated_at";

    private static final long LATELY_MS = 6 * 3_600_000L;

    private final Database db;
    private final Settings settings;
    private final ContentGuard guard;

    public MemoryStore(Database db, ContentGuard guard) {
        this.db = db;
        this.settings = new Settings(db);
        this.guard = guard;
    }

    public static boolean validScope(String scope) {
        return scope != null && SCOPE.matcher(scope).matches();
    }

    public boolean auto() {
        return !"false".equals(settings.get(AUTO_KEY));
    }

    public void setAuto(boolean enabled) {
        settings.put(AUTO_KEY, String.valueOf(enabled));
    }

    public List<Scope> scopes(String personaId) {
        return db.list("""
                SELECT scope, COUNT(*) AS n, MAX(updated_at) AS latest,
                       (SELECT label FROM memories l WHERE l.persona_id = m.persona_id AND l.scope = m.scope AND l.label <> ''
                        ORDER BY l.updated_at DESC LIMIT 1) AS label
                FROM memories m WHERE persona_id = ? GROUP BY scope ORDER BY latest DESC
                """, rs -> new Scope(rs.getString("scope"), nonNull(rs.getString("label")), rs.getInt("n"), rs.getLong("latest")), personaId);
    }

    public List<Memory> list(String personaId, String scope) {
        return db.list("SELECT " + COLUMNS + " FROM memories WHERE persona_id = ? AND scope = ? ORDER BY updated_at DESC, id DESC",
                MemoryStore::map, personaId, scope);
    }

    List<Memory> recent(String personaId, String scope, int limit) {
        return db.list("SELECT " + COLUMNS + " FROM memories WHERE persona_id = ? AND scope = ? ORDER BY updated_at DESC, id DESC LIMIT ?",
                MemoryStore::map, personaId, scope, limit);
    }

    public Memory get(String personaId, long id) {
        return db.one("SELECT " + COLUMNS + " FROM memories WHERE persona_id = ? AND id = ?", MemoryStore::map, personaId, id);
    }

    public Memory add(String personaId, String scope, String label, String content, String source) {
        if (!validScope(scope)) {
            throw new IllegalArgumentException("记忆分区不对：" + scope);
        }
        String text = clean(content);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        long now = System.currentTimeMillis();
        String name = abbreviate(QqMessages.unforgeable(nonNull(label)).replaceAll("\\s+", " ").strip(), 60);
        long id = db.transaction(c -> {
            Database.update(c, "INSERT INTO memories (persona_id, scope, label, content, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    personaId, scope, name, text, nonNull(source), now, now);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        });
        prune(personaId, scope);
        return get(personaId, id);
    }

    public boolean knows(String personaId, String scope, String content) {
        String text = ContentGuard.normalize(content);
        return recent(personaId, scope, 40).stream().anyMatch(m -> ContentGuard.normalize(m.content()).equals(text));
    }

    public boolean update(String personaId, long id, String content) {
        String text = clean(content);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        return db.update("UPDATE memories SET content = ?, updated_at = ? WHERE persona_id = ? AND id = ?",
                text, System.currentTimeMillis(), personaId, id) > 0;
    }

    public boolean delete(String personaId, long id) {
        return db.update("DELETE FROM memories WHERE persona_id = ? AND id = ?", personaId, id) > 0;
    }

    public static boolean visible(Memory m, String session, Set<String> owners) {
        String source = m.source();
        return source.isEmpty() || source.equals("manual") || source.startsWith("group:") || source.startsWith("mind:") || source.equals(session)
                || owners.stream().anyMatch(o -> session.equals("private:" + o));
    }

    List<Memory> recall(String personaId, String scope, String session, Set<String> owners, int limit) {
        return recent(personaId, scope, 40).stream().filter(m -> visible(m, session, owners)).limit(limit).toList();
    }

    public String recall(String personaId, String session, List<String> people, Set<String> owners) {
        StringBuilder out = new StringBuilder();
        section(out, "关于你自己", recall(personaId, SELF, session, owners, 6));
        if (session.startsWith("group:")) {
            section(out, "关于这个群", recall(personaId, session, session, owners, 5));
        }
        for (String qq : people.stream().distinct().limit(6).toList()) {
            List<Memory> about = recall(personaId, "user:" + qq, session, owners, 4);
            if (!about.isEmpty()) {
                String name = about.stream().map(Memory::label).filter(l -> !l.isBlank()).findFirst().orElse("QQ " + qq);
                section(out, "关于" + name + "（QQ " + qq + (owners.contains(qq) ? "，你的主人" : "") + "）", about);
            }
        }
        List<Memory> diary = recall(personaId, DIARY, session, owners, 2);
        if (!diary.isEmpty()) {
            out.append("你最近的日记：\n");
            for (Memory m : diary.reversed()) {
                out.append("- ").append(day(m.createdAt())).append("：").append(m.content()).append('\n');
            }
        }
        lately(out, session, people);
        return out.isEmpty() ? "" : "<你记得>\n" + out + "</你记得>\n\n";
    }

    private record Seen(long time, String who, String text) {}

    private void lately(StringBuilder out, String session, List<String> people) {
        boolean group = session.startsWith("group:");
        String here = group ? session.substring(6) : "";
        long now = System.currentTimeMillis();
        List<Seen> seen = new ArrayList<>();
        for (String qq : people.stream().distinct().limit(4).toList()) {
            seen.addAll(db.list("""
                    SELECT time, sender_name, content FROM messages
                    WHERE direction = 'in' AND message_type = 'group' AND user_id = ? AND group_id <> ? AND time >= ?
                    ORDER BY id DESC LIMIT ?
                    """, rs -> new Seen(rs.getLong(1), QqMessages.unforgeable(nonNull(rs.getString(2))), rs.getString(3)),
                    qq, here, now - LATELY_MS, group ? 2 : 3));
        }
        if (!group) {
            seen.addAll(db.list("""
                    SELECT time, '', content FROM messages WHERE direction = 'out' AND message_type = 'group' AND time >= ?
                    ORDER BY id DESC LIMIT 3
                    """, rs -> new Seen(rs.getLong(1), "", rs.getString(3)), now - LATELY_MS / 2));
        }
        List<String> lines = seen.stream().sorted(Comparator.comparingLong(Seen::time))
                .map(s -> new Seen(s.time(), s.who(), abbreviate(QqMessages.plain(s.text()), 80)))
                .filter(s -> !s.text().isBlank() && guard.scan(s.text()) == null)
                .map(s -> "- " + minutes(now - s.time()) + "，在" + (group ? "另一个群" : "群") + "里，"
                        + (s.who().isEmpty() ? "你说" : s.who() + "说") + "：" + s.text())
                .toList();
        if (!lines.isEmpty()) {
            out.append("最近在别处（群里大家都看得到的）：\n").append(String.join("\n", lines)).append('\n');
        }
    }

    private static String minutes(long ms) {
        long m = ms / 60_000;
        return m < 1 ? "刚刚" : m < 60 ? m + " 分钟前" : m / 60 + " 小时前";
    }

    long checkpoint(String session) {
        Long id = db.one("SELECT last_turn_id FROM memory_checkpoints WHERE session = ?", rs -> rs.getLong(1), session);
        return id == null ? 0 : id;
    }

    void setCheckpoint(String session, long lastTurnId) {
        db.update("""
                INSERT INTO memory_checkpoints (session, last_turn_id, reflected_at) VALUES (?, ?, ?)
                ON CONFLICT (session) DO UPDATE SET last_turn_id = MAX(last_turn_id, excluded.last_turn_id), reflected_at = excluded.reflected_at
                """, session, lastTurnId, System.currentTimeMillis());
    }

    void forget(String session) {
        db.update("DELETE FROM memory_checkpoints WHERE session = ?", session);
    }

    List<Turn> unreflected(String session) {
        return db.list("""
                SELECT id, role, content, created_at FROM conversation_turns
                WHERE session = ? AND id > ? AND role <> 'tool' AND content <> '' ORDER BY id
                """, rs -> new Turn(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)), session, checkpoint(session));
    }

    public int pending(String session) {
        Integer n = db.one("SELECT COUNT(*) FROM conversation_turns WHERE session = ? AND id > ? AND role <> 'tool' AND content <> ''",
                rs -> rs.getInt(1), session, checkpoint(session));
        return n == null ? 0 : n;
    }

    long lastTurnId(String session) {
        Long id = db.one("SELECT MAX(id) FROM conversation_turns WHERE session = ?", rs -> rs.getLong(1), session);
        return id == null ? 0 : id;
    }

    public List<String> pendingSessions() {
        return db.list("""
                SELECT t.session FROM conversation_turns t LEFT JOIN memory_checkpoints m ON m.session = t.session
                WHERE (t.session LIKE 'group:%' OR t.session LIKE 'private:%') AND t.role <> 'tool' AND t.content <> ''
                  AND t.id > COALESCE(m.last_turn_id, 0)
                GROUP BY t.session ORDER BY MAX(t.id) DESC
                """, rs -> rs.getString(1));
    }

    List<String[]> roster(String session, long since) {
        if (session.startsWith("group:")) {
            return db.list("""
                    SELECT user_id, sender_name FROM messages WHERE id IN (
                        SELECT MAX(id) FROM messages WHERE direction = 'in' AND message_type = 'group' AND group_id = ? AND time >= ?
                        GROUP BY user_id) ORDER BY id DESC LIMIT 40
                    """, rs -> new String[] {rs.getString(1), QqMessages.unforgeable(nonNull(rs.getString(2)))}, session.substring(6), since);
        }
        String qq = session.substring(session.indexOf(':') + 1);
        String name = db.one("SELECT sender_name FROM messages WHERE direction = 'in' AND message_type = 'private' AND user_id = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.getString(1), qq);
        return List.<String[]>of(new String[] {qq, QqMessages.unforgeable(nonNull(name))});
    }

    static String day(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DAY);
    }

    private void prune(String personaId, String scope) {
        int keep = scope.equals(DIARY) ? 120 : scope.equals(SELF) ? 40 : 30;
        db.update("""
                DELETE FROM memories WHERE persona_id = ? AND scope = ? AND id NOT IN (
                    SELECT id FROM memories WHERE persona_id = ? AND scope = ? ORDER BY updated_at DESC, id DESC LIMIT ?)
                """, personaId, scope, personaId, scope, keep);
    }

    private static void section(StringBuilder out, String title, List<Memory> memories) {
        if (memories.isEmpty()) {
            return;
        }
        out.append(title).append("：\n");
        for (Memory m : memories) {
            out.append("- ").append(m.content()).append("（").append(day(m.updatedAt())).append("）\n");
        }
    }

    private static String clean(String content) {
        return abbreviate(nonNull(content).replaceAll("\\s+", " ").strip(), MAX_CONTENT);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static Memory map(ResultSet rs) throws SQLException {
        return new Memory(rs.getLong("id"), rs.getString("scope"), rs.getString("label"), rs.getString("content"),
                rs.getString("source"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
