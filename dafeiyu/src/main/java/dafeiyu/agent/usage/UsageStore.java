package dafeiyu.agent.usage;

import dafeiyu.agent.ChatResult;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.protocol.ChatRequest;
import dafeiyu.db.Database;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class UsageStore {

    public record Day(String day, long tokens, long calls) {}

    public record Share(String key, long tokens, long calls) {}

    public record Totals(long tokens, long prompt, long completion, long cached, long calls, long longestMs, long avgMs, String firstDay) {}

    private final Database db;

    public UsageStore(Database db) {
        this.db = db;
    }

    public void record(ModelProfile profile, ChatRequest request, ChatResult result) {
        String key = request.cacheKey() == null ? "" : request.cacheKey();
        String purpose = purpose(request.purpose(), key);
        String session = key.startsWith("reflect:") ? key.substring(8)
                : purpose.equals("chat") || purpose.equals("mind") || purpose.equals("compact") ? key : "";
        long now = System.currentTimeMillis();
        db.update("""
                INSERT INTO token_usage (at, day, purpose, session, model, effort, prompt_tokens, completion_tokens, cached_tokens, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, now, LocalDate.now().toString(), purpose, session, result.model() == null || result.model().isBlank() ? profile.model() : result.model(),
                request.effort(), Math.max(0, result.promptTokens()), Math.max(0, result.completionTokens()), Math.max(0, result.cachedTokens()),
                result.latencyMs());
    }

    private static String purpose(String explicit, String key) {
        if (!explicit.isEmpty()) {
            return explicit;
        }
        if (key.isEmpty()) {
            return "probe";
        }
        if (key.equals("guard") || key.equals("sticker")) {
            return key;
        }
        if (key.startsWith("reflect:")) {
            return "reflect";
        }
        return key.startsWith("mind:") ? "mind" : "chat";
    }

    public List<Day> days(String since) {
        return db.list("""
                SELECT day, SUM(prompt_tokens + completion_tokens), COUNT(*) FROM token_usage WHERE day >= ? GROUP BY day ORDER BY day
                """, rs -> new Day(rs.getString(1), rs.getLong(2), rs.getLong(3)), since);
    }

    public List<String> activeDays() {
        return db.list("SELECT DISTINCT day FROM token_usage ORDER BY day", rs -> rs.getString(1));
    }

    public Day peak() {
        return db.one("""
                SELECT day, SUM(prompt_tokens + completion_tokens) AS t, COUNT(*) FROM token_usage GROUP BY day ORDER BY t DESC LIMIT 1
                """, rs -> new Day(rs.getString(1), rs.getLong(2), rs.getLong(3)));
    }

    public Totals totals() {
        Totals totals = db.one("""
                SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0), COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0),
                       COALESCE(SUM(cached_tokens), 0), COUNT(*), COALESCE(MAX(latency_ms), 0), COALESCE(AVG(latency_ms), 0), COALESCE(MIN(day), '')
                FROM token_usage
                """, rs -> new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7),
                rs.getString(8)));
        return totals == null ? new Totals(0, 0, 0, 0, 0, 0, 0, "") : totals;
    }

    public List<Share> by(String column, String where, int limit) {
        if (!List.of("purpose", "session", "model", "effort").contains(column)) {
            throw new IllegalArgumentException("bad column");
        }
        return new ArrayList<>(db.list("SELECT " + column + ", SUM(prompt_tokens + completion_tokens) AS t, COUNT(*) FROM token_usage "
                + (where.isEmpty() ? "" : "WHERE " + where + " ") + "GROUP BY " + column + " ORDER BY t DESC LIMIT ?",
                rs -> new Share(rs.getString(1), rs.getLong(2), rs.getLong(3)), limit));
    }
}
