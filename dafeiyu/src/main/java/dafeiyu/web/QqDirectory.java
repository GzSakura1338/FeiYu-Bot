package dafeiyu.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.qq.QqMessages;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.NapCatService;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dafeiyu.util.Jsons.optString;

final class QqDirectory {

    private static final long REFRESH_MS = 5 * 60 * 1000;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final NapCatService napcat;
    private final MessageArchive archive;
    private volatile Map<String, String> groups = Map.of();
    private volatile Map<String, String> friends = Map.of();
    private volatile long loadedAt;

    QqDirectory(NapCatService napcat, MessageArchive archive) {
        this.napcat = napcat;
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

    Names names() {
        refresh();
        return new Names(groups, friends, archive.privateSenderNames());
    }

    record Names(Map<String, String> groups, Map<String, String> friends, Map<String, String> senders) {

        String of(String session) {
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
                return name == null || name.isBlank() ? "QQ " + id : name;
            }
            return "网页测试 " + session.substring(session.indexOf(':') + 1);
        }
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < REFRESH_MS || !napcat.status().connected()) {
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
            JsonObject response = napcat.callAction(action, new JsonObject(), TIMEOUT).get(TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
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
