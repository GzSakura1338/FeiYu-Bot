package dafeiyu.agent.context;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.SessionDetail;
import dafeiyu.agent.SessionSummary;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.reply.Markup;
import dafeiyu.db.Database;

import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static dafeiyu.util.Texts.abbreviate;

public final class ConversationMemory {

    public record Conversation(String summary, String recent, List<ChatMessage> turns, int measuredTokens, String measuredBy, long lastId) {

        static final Conversation EMPTY = new Conversation(null, null, List.of(), 0, "", 0);

        public boolean hasCheckpoint() {
            return summary != null && !summary.isBlank();
        }

        public boolean isEmpty() {
            return !hasCheckpoint() && turns.isEmpty();
        }

        public boolean keptRecent() {
            return hasCheckpoint() && recent != null && !recent.isBlank();
        }
    }

    private record State(String summary, String recent, int measuredTokens, String measuredBy) {}

    private static final Gson GSON = new Gson();
    private static final Type CALLS = new TypeToken<List<ToolCall>>() {}.getType();
    private static final String INSERT = """
            INSERT INTO conversation_turns (session, role, content, reasoning, tool_calls, tool_call_id, tool_name, tool_error, created_at, attachments)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final Database db;

    public ConversationMemory(Database db) {
        this.db = db;
    }

    public Conversation load(String session) {
        State state = db.one("SELECT summary, recent, measured_tokens, measured_by FROM conversation_sessions WHERE session = ?",
                rs -> new State(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), session);
        long[] lastId = {0};
        List<ChatMessage> turns = db.list(
                "SELECT id, role, content, reasoning, tool_calls, tool_call_id, tool_name, tool_error FROM conversation_turns WHERE session = ? ORDER BY id",
                rs -> {
                    lastId[0] = rs.getLong("id");
                    return map(rs);
                }, session);
        return state == null
                ? new Conversation(null, null, turns, 0, "", lastId[0])
                : new Conversation(state.summary(), state.recent(), turns, state.measuredTokens(), state.measuredBy(), lastId[0]);
    }

    public record Heard(long id, String content) {}

    public record Appended(List<Heard> heard, long lastId) {}

    public void append(String session, List<ChatMessage> messages, int measuredTokens, String measuredBy) {
        append(session, List.of(), 0, "", messages, measuredTokens, measuredBy, Long.MAX_VALUE);
    }

    public Appended append(String session, List<ChatMessage> asked, long askedAt, String askedAttachments, List<ChatMessage> messages,
                           int measuredTokens, String measuredBy, long heardAfter) {
        long now = System.currentTimeMillis();
        return db.transaction(c -> {
            List<Heard> heard = heardSince(c, session, heardAfter);
            insertTurns(c, session, asked, askedAt > 0 ? Math.min(askedAt, now) : now, askedAttachments);
            insertTurns(c, session, messages, now, "");
            Database.update(c, """
                    INSERT INTO conversation_sessions (session, measured_tokens, measured_by, updated_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (session) DO UPDATE SET measured_tokens = excluded.measured_tokens,
                        measured_by = excluded.measured_by, updated_at = excluded.updated_at
                    """, session, measuredTokens, measuredBy, now);
            return new Appended(heard, lastId(c, session));
        });
    }

    public List<Heard> heardSince(String session, long afterId) {
        return db.transaction(c -> heardSince(c, session, afterId));
    }

    private static List<Heard> heardSince(java.sql.Connection c, String session, long afterId) throws SQLException {
        return Database.list(c, "SELECT id, content FROM conversation_turns WHERE session = ? AND id > ? AND role = 'user' ORDER BY id",
                rs -> new Heard(rs.getLong(1), rs.getString(2)), session, afterId);
    }

    private static long lastId(java.sql.Connection c, String session) throws SQLException {
        List<Long> ids = Database.list(c, "SELECT COALESCE(MAX(id), 0) FROM conversation_turns WHERE session = ?", rs -> rs.getLong(1), session);
        return ids.isEmpty() ? 0 : ids.getFirst();
    }

    public void appendKeepingMeasure(String session, List<ChatMessage> messages) {
        long now = System.currentTimeMillis();
        db.transaction(c -> {
            insertTurns(c, session, messages, now, "");
            Database.update(c, """
                    INSERT INTO conversation_sessions (session, measured_tokens, measured_by, updated_at) VALUES (?, 0, '', ?)
                    ON CONFLICT (session) DO UPDATE SET updated_at = excluded.updated_at
                    """, session, now);
            return null;
        });
    }

    public long hear(String session, ChatMessage message, long tokens, String attachments) {
        long now = System.currentTimeMillis();
        return db.transaction(c -> {
            insertTurns(c, session, List.of(message), now, attachments);
            Database.update(c, """
                    INSERT INTO conversation_sessions (session, measured_tokens, measured_by, updated_at) VALUES (?, 0, '', ?)
                    ON CONFLICT (session) DO UPDATE SET updated_at = excluded.updated_at,
                        measured_tokens = CASE WHEN measured_tokens > 0 THEN measured_tokens + ? ELSE 0 END
                    """, session, now, tokens);
            return lastId(c, session);
        });
    }

    private static void insertTurns(java.sql.Connection c, String session, List<ChatMessage> messages, long now, String lastAttachments)
            throws SQLException {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            Database.update(c, INSERT, session, m.role(), m.content(), m.reasoning(),
                    m.toolCalls().isEmpty() ? "" : GSON.toJson(m.toolCalls()), m.toolCallId(), m.toolName(), m.toolError() ? 1 : 0, now,
                    i == messages.size() - 1 && lastAttachments != null ? lastAttachments : "");
        }
    }

    void checkpoint(String session, String summary, String recent, long upToId) {
        long now = System.currentTimeMillis();
        db.transaction(c -> {
            Database.update(c, "DELETE FROM conversation_turns WHERE session = ? AND id <= ?", session, upToId);
            Database.update(c, """
                    INSERT INTO conversation_sessions (session, summary, recent, measured_tokens, measured_by, compactions, updated_at)
                    VALUES (?, ?, ?, 0, '', 1, ?)
                    ON CONFLICT (session) DO UPDATE SET summary = excluded.summary, recent = excluded.recent,
                        measured_tokens = 0, measured_by = '', compactions = compactions + 1, updated_at = excluded.updated_at
                    """, session, summary, recent, now);
            return null;
        });
    }

    public long lastAt(String session) {
        Long at = db.one("""
                SELECT MAX(COALESCE((SELECT MAX(created_at) FROM conversation_turns WHERE session = ?), 0),
                           COALESCE((SELECT updated_at FROM conversation_sessions WHERE session = ?), 0))
                """, rs -> rs.getLong(1), session, session);
        return at == null ? 0 : at;
    }

    public long lastSpoke(String session) {
        Long at = db.one("SELECT MAX(created_at) FROM conversation_turns WHERE session = ? AND role = 'assistant' AND content <> ''",
                rs -> rs.getLong(1), session);
        return at == null ? 0 : at;
    }

    public void clear(String session) {
        db.transaction(c -> {
            Database.update(c, "DELETE FROM conversation_turns WHERE session = ?", session);
            Database.update(c, "DELETE FROM conversation_sessions WHERE session = ?", session);
            return null;
        });
    }

    public List<SessionSummary> sessions() {
        return db.list("""
                WITH keys AS (SELECT session FROM conversation_turns UNION SELECT session FROM conversation_sessions),
                stats AS (SELECT session, COUNT(*) AS turns, MAX(created_at) AS last_turn, MAX(id) AS last_id
                          FROM conversation_turns GROUP BY session)
                SELECT k.session, COALESCE(t.turns, 0) AS turns,
                       MAX(COALESCE(t.last_turn, 0), COALESCE(s.updated_at, 0)) AS last_active,
                       COALESCE(s.compactions, 0) AS compactions, COALESCE(s.measured_tokens, 0) AS measured,
                       COALESCE(s.summary, '') <> '' AS has_summary,
                       (SELECT content FROM conversation_turns c WHERE c.session = k.session AND c.role <> 'tool' AND c.content <> ''
                        AND c.content <> '（以上是你刚才看过的）' ORDER BY c.id DESC LIMIT 1) AS preview,
                       (SELECT role FROM conversation_turns c WHERE c.session = k.session AND c.role <> 'tool' AND c.content <> ''
                        AND c.content <> '（以上是你刚才看过的）' ORDER BY c.id DESC LIMIT 1) AS preview_role,
                       (SELECT content FROM conversation_turns c WHERE c.session = k.session AND c.role = 'user' AND c.content <> ''
                        AND NOT (c.content LIKE '【%】' AND instr(c.content, char(10)) = 0) ORDER BY c.id LIMIT 1) AS title
                FROM keys k
                LEFT JOIN stats t ON t.session = k.session
                LEFT JOIN conversation_sessions s ON s.session = k.session
                ORDER BY last_active DESC
                """, rs -> new SessionSummary(rs.getString("session"), rs.getInt("turns"), rs.getLong("last_active"),
                rs.getInt("compactions"), rs.getInt("measured"), rs.getInt("has_summary") == 1,
                abbreviate(Markup.saidOf(Objects.requireNonNullElse(rs.getString("preview"), "")), 120),
                Objects.requireNonNullElse(rs.getString("preview_role"), ""),
                abbreviate(Objects.requireNonNullElse(rs.getString("title"), "").replaceAll("\\s+", " ").strip(), 40)));
    }

    public SessionDetail detail(String session, int limit) {
        return detail(session, limit, Long.MAX_VALUE);
    }

    public SessionDetail detail(String session, int limit, long before) {
        State state = db.one("SELECT summary, recent, measured_tokens, measured_by FROM conversation_sessions WHERE session = ?",
                rs -> new State(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), session);
        Integer compactions = db.one("SELECT compactions FROM conversation_sessions WHERE session = ?", rs -> rs.getInt(1), session);
        Integer total = db.one("SELECT COUNT(*) FROM conversation_turns WHERE session = ?", rs -> rs.getInt(1), session);
        List<SessionDetail.Entry> turns = new ArrayList<>(db.list("""
                SELECT id, role, content, tool_calls, tool_call_id, tool_name, tool_error, reasoning, created_at, attachments
                FROM conversation_turns WHERE session = ? AND id < ? ORDER BY id DESC LIMIT ?
                """, rs -> {
            ChatMessage m = map(rs);
            return new SessionDetail.Entry(rs.getLong("id"), m.role(), m.content(), m.reasoning() == null ? "" : m.reasoning(), m.toolCalls(),
                    m.toolCallId(), m.toolError(), rs.getLong("created_at"), Objects.requireNonNullElse(rs.getString("attachments"), ""));
        }, session, before, limit));
        Collections.reverse(turns);
        return new SessionDetail(session, state == null ? null : state.summary(), state == null ? null : state.recent(),
                compactions == null ? 0 : compactions, state == null ? 0 : state.measuredTokens(), total == null ? 0 : total, turns);
    }

    private static ChatMessage map(ResultSet rs) throws SQLException {
        List<ToolCall> calls = List.of();
        String json = rs.getString("tool_calls");
        if (json != null && !json.isEmpty()) {
            try {
                List<ToolCall> parsed = GSON.fromJson(json, CALLS);
                calls = parsed == null ? List.of() : parsed;
            } catch (JsonParseException ignored) {
            }
        }
        return new ChatMessage(rs.getString("role"), rs.getString("content"), rs.getString("reasoning"), null, calls,
                rs.getString("tool_call_id"), rs.getString("tool_name"), rs.getInt("tool_error") == 1);
    }
}
