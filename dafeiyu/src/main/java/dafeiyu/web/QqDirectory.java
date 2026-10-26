package dafeiyu.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.qq.QqMessages;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.OneBotService;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;

final class QqDirectory {

    private static final long REFRESH_MS = 60_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final OneBotService onebot;
    private final MessageArchive archive;
    private volatile Map<String, String> groups = Map.of();
    private volatile Map<String, String> friends = Map.of();
    private volatile long loadedAt;
    private final Map<String, Card> cards = new ConcurrentHashMap<>();
    private final Map<String, String> strangers = new ConcurrentHashMap<>();
    private final Map<String, Long> misses = new ConcurrentHashMap<>();

    QqDirectory(OneBotService onebot, MessageArchive archive) {
        this.onebot = onebot;
        this.archive = archive;
    }

    Map<String, String> speakers(String groupId) {
        Map<String, String> labels = new HashMap<>();
        archive.groupSenders(groupId).forEach((qq, name) -> {
            String label = QqMessages.displayName(name);
            labels.putIfAbsent(label, qq);
            labels.put(label + "#" + (qq.length() <= 4 ? qq : qq.substring(qq.length() - 4)), qq);
        });
        return labels;
    }

    Map<String, String> self(String session) {
        String qq = onebot.status().selfId() == null ? "" : onebot.status().selfId();
        String nickname = onebot.status().nickname() == null ? "" : onebot.status().nickname();
        String name = session.startsWith("group:") ? groupCard(session.substring(6), qq) : "";
        return Map.of("qq", qq, "name", name.isBlank() ? nickname : name);
    }

    private String groupCard(String groupId, String selfId) {
        long now = System.currentTimeMillis();
        Card cached = cards.get(groupId);
        if (cached != null && now - cached.at() < REFRESH_MS) {
            return cached.name();
        }
        String name = cached == null ? "" : cached.name();
        if (onebot.status().connected() && groupId.matches("\\d+") && selfId.matches("\\d+")) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("group_id", Long.parseLong(groupId));
                params.addProperty("user_id", Long.parseLong(selfId));
                params.addProperty("no_cache", true);
                JsonObject data = optObject(onebot.callAction("get_group_member_info", params, TIMEOUT)
                        .get(TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS), "data");
                if (data != null) {
                    String card = optString(data, "card");
                    name = card.isBlank() ? optString(data, "nickname") : card;
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        cards.put(groupId, new Card(name, now));
        return name;
    }

    private record Card(String name, long at) {}

    Names names() {
        refresh();
        return new Names(groups, friends, archive.privateSenderNames(), this::stranger);
    }

    record Names(Map<String, String> groups, Map<String, String> friends, Map<String, String> senders,
                 java.util.function.UnaryOperator<String> stranger) {

        String of(String session) {
            if (session.startsWith("mind:")) {
                return "内心";
            }
            if (session.startsWith("group:")) {
                String id = session.substring(6);
                return groups.getOrDefault(id, "群 " + id);
            }
            if (session.startsWith("private:")) {
                String id = session.substring(8);
                String name = friends.get(id);
                if (name == null || name.isBlank()) {
                    name = senders.get(id);
                }
                if (name == null || name.isBlank()) {
                    name = stranger.apply(id);
                }
                return name == null || name.isBlank() ? "QQ " + id : name;
            }
            return "网页测试 " + session.substring(session.indexOf(':') + 1);
        }
    }

    private String stranger(String qq) {
        String known = strangers.get(qq);
        if (known != null) {
            return known;
        }
        Long missed = misses.get(qq);
        if (missed != null && System.currentTimeMillis() - missed < REFRESH_MS * 10) {
            return "";
        }
        String name = QqMessages.displayName(java.util.Objects.requireNonNullElse(archive.lastKnownName(qq), ""));
        if (name.isBlank() && onebot.status().connected() && qq.matches("\\d{5,12}")) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("user_id", Long.parseLong(qq));
                JsonObject data = optObject(onebot.callAction("get_stranger_info", params, Duration.ofSeconds(2))
                        .get(2_500, TimeUnit.MILLISECONDS), "data");
                name = data == null ? "" : optString(data, "nickname");
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (name.isBlank()) {
            misses.put(qq, System.currentTimeMillis());
            return "";
        }
        strangers.put(qq, name);
        return name;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < REFRESH_MS || !onebot.status().connected()) {
            return;
        }
        loadedAt = now;
        Map<String, String> g = load("get_group_list", "group_id", "group_name", null);
        if (g != null) {
            groups = g;
        }
        Map<String, String> f = load("get_friend_list", "user_id", "nickname", "remark");
        if (f != null) {
            friends = f;
        }
    }

    private Map<String, String> load(String action, String idKey, String nameKey, String preferredKey) {
        try {
            JsonObject response = onebot.callAction(action, new JsonObject(), TIMEOUT).get(TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
            JsonElement data = response.get("data");
            if (data == null || !data.isJsonArray()) {
                return null;
            }
            Map<String, String> names = new HashMap<>();
            for (JsonElement item : data.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject o = item.getAsJsonObject();
                String name = preferredKey == null ? "" : optString(o, preferredKey);
                names.put(optString(o, idKey), name.isBlank() ? optString(o, nameKey) : name);
            }
            return names;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
