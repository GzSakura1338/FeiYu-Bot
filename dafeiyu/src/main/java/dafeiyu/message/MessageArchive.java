package dafeiyu.message;

import com.google.gson.JsonObject;
import dafeiyu.db.Database;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;

public final class MessageArchive {

    public record Stats(int today, int total) {}

    public record GroupActivity(String groupId, int lastHalfHour, int lastTwoHours, long lastMessageAt, List<String> speakers) {}

    public Map<String, String> recentSenders(String groupId, long since, int limit) {
        Map<String, String> senders = new LinkedHashMap<>();
        db.list("""
                SELECT m.user_id, m.sender_name FROM messages m JOIN (
                    SELECT user_id, MAX(id) AS last FROM messages
                    WHERE direction = 'in' AND message_type = 'group' AND group_id = ? AND time >= ? GROUP BY user_id) t ON m.id = t.last
                ORDER BY m.id DESC LIMIT ?
                """, rs -> senders.put(rs.getString(1), rs.getString(2)), groupId, since, limit);
        return senders;
    }

    public Map<String, String> groupSenders(String groupId) {
        Map<String, String> senders = new HashMap<>();
        db.list("""
                SELECT user_id, sender_name FROM messages WHERE id IN (
                    SELECT MAX(id) FROM messages WHERE direction = 'in' AND message_type = 'group' AND group_id = ? GROUP BY user_id)
                """, rs -> senders.put(rs.getString(1), rs.getString(2)), groupId);
        return senders;
    }

    public record Seen(String userId, String name, String groupId, long lastAt) {}

    public List<Seen> findPeople(String query, int limit) {
        String like = "%" + query.replace("%", "").replace("_", "") + "%";
        return db.list("""
                SELECT m.user_id, m.sender_name, m.group_id, m.time FROM messages m JOIN (
                    SELECT user_id, group_id, MAX(id) AS last FROM messages
                    WHERE direction = 'in' AND (sender_name LIKE ? OR user_id = ?) GROUP BY user_id, group_id) t ON m.id = t.last
                ORDER BY m.time DESC LIMIT ?
                """, rs -> new Seen(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)), like, query, limit);
    }

    public List<GroupActivity> groupActivity(long since) {
        long now = System.currentTimeMillis();
        List<GroupActivity> list = db.list("""
                SELECT group_id, COUNT(*) FILTER (WHERE time >= ?) AS half, COUNT(*) FILTER (WHERE time >= ?) AS two, MAX(time) AS last
                FROM messages WHERE direction = 'in' AND message_type = 'group' AND time >= ? GROUP BY group_id ORDER BY last DESC
                """, rs -> new GroupActivity(rs.getString("group_id"), rs.getInt("half"), rs.getInt("two"), rs.getLong("last"), List.of()),
                now - 30 * 60_000, now - 2 * 3_600_000, since);
        return list.stream().map(a -> new GroupActivity(a.groupId(), a.lastHalfHour(), a.lastTwoHours(), a.lastMessageAt(),
                db.list("""
                        SELECT sender_name FROM messages WHERE direction = 'in' AND message_type = 'group' AND group_id = ?
                        GROUP BY user_id ORDER BY MAX(id) DESC LIMIT 4
                        """, rs -> rs.getString(1), a.groupId()))).toList();
    }

    private final Database db;

    public MessageArchive(Database db) {
        this.db = db;
    }

    public void onEvent(JsonObject event) {
        if (!optString(event, "post_type").equals("message")) {
            return;
        }
        JsonObject sender = optObject(event, "sender");
        String card = sender == null ? "" : optString(sender, "card");
        String name = !card.isEmpty() ? card : sender == null ? "" : optString(sender, "nickname");
        String groupId = optString(event, "group_id");
        insert(eventTime(event), "in", optString(event, "message_type"), groupId.isEmpty() ? null : groupId,
                optString(event, "user_id"), name, optString(event, "message_id"), optString(event, "raw_message"));
    }

    public java.util.Set<String> messageIdsSince(long since) {
        return new java.util.HashSet<>(db.list("SELECT message_id FROM messages WHERE time >= ? AND message_id IS NOT NULL",
                rs -> rs.getString(1), since));
    }

    public long lastIncomingAt() {
        Long at = db.one("SELECT MAX(time) FROM messages WHERE direction = 'in'", rs -> rs.getLong(1));
        return at == null ? 0 : at;
    }

    public List<String> activeGroups(long since, int limit) {
        return db.list("""
                SELECT group_id FROM messages WHERE message_type = 'group' AND time >= ? AND group_id IS NOT NULL
                GROUP BY group_id ORDER BY MAX(time) DESC LIMIT ?
                """, rs -> rs.getString(1), since, limit);
    }

    public List<String> privatePeers(long since, int limit) {
        return db.list("""
                SELECT user_id FROM messages WHERE message_type = 'private' AND direction = 'in' AND time >= ?
                GROUP BY user_id ORDER BY MAX(time) DESC LIMIT ?
                """, rs -> rs.getString(1), since, limit);
    }

    public void recordOutgoing(String messageType, String groupId, String targetUserId, String selfId,
                               String selfName, String messageId, String content) {
        insert(System.currentTimeMillis(), "out", messageType,
                messageType.equals("group") ? groupId : targetUserId, selfId, selfName, messageId, content);
    }

    public List<StoredMessage> conversation(boolean group, String peer, int limit) {
        String where = group ? "message_type = 'group' AND group_id = ?"
                : "message_type = 'private' AND ((direction = 'in' AND user_id = ?1) OR (direction = 'out' AND group_id = ?1))";
        List<StoredMessage> rows = db.list("""
                SELECT id, time, direction, message_type, group_id, user_id, sender_name, message_id, content
                FROM messages WHERE %s ORDER BY id DESC LIMIT %d
                """.formatted(where, Math.max(1, Math.min(200, limit))), rs -> new StoredMessage(rs.getLong("id"), rs.getLong("time"),
                rs.getString("direction"), rs.getString("message_type"), rs.getString("group_id"), rs.getString("user_id"),
                rs.getString("sender_name"), rs.getString("message_id"), rs.getString("content")), peer);
        return rows.reversed();
    }

    public List<StoredMessage> recent(int limit) {
        return db.list("""
                SELECT id, time, direction, message_type, group_id, user_id, sender_name, message_id, content
                FROM messages ORDER BY id DESC LIMIT ?
                """, rs -> new StoredMessage(rs.getLong("id"), rs.getLong("time"), rs.getString("direction"),
                rs.getString("message_type"), rs.getString("group_id"), rs.getString("user_id"),
                rs.getString("sender_name"), rs.getString("message_id"), rs.getString("content")), limit);
    }

    public Map<String, String> privateSenderNames() {
        Map<String, String> names = new HashMap<>();
        db.list("""
                SELECT user_id, sender_name FROM messages WHERE id IN (
                    SELECT MAX(id) FROM messages WHERE direction = 'in' AND message_type = 'private' GROUP BY user_id)
                """, rs -> names.put(rs.getString(1), rs.getString(2)));
        return names;
    }

    public Stats stats() {
        long startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return db.one("""
                SELECT COUNT(*) FILTER (WHERE time >= ?), COUNT(*) FROM messages WHERE direction = 'in'
                """, rs -> new Stats(rs.getInt(1), rs.getInt(2)), startOfDay);
    }

    private void insert(long time, String direction, String messageType, String groupId, String userId,
                        String senderName, String messageId, String content) {
        db.update("""
                INSERT INTO messages (time, direction, message_type, group_id, user_id, sender_name, message_id, content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, time, direction, messageType, groupId, userId, senderName,
                messageId == null || messageId.isEmpty() ? null : messageId, content);
    }

    private static long eventTime(JsonObject event) {
        try {
            return event.has("time") ? event.get("time").getAsLong() * 1000 : System.currentTimeMillis();
        } catch (RuntimeException e) {
            return System.currentTimeMillis();
        }
    }
}
