package dafeiyu.agent.qq;

import com.google.gson.JsonObject;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.NapCatService;
import dafeiyu.util.Clock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static dafeiyu.agent.qq.QqMessages.unforgeable;
import static dafeiyu.util.Jsons.optString;

final class QqContacts {

    private static final long TTL_MS = 10 * 60_000;

    private final ChatAgent agent;
    private final NapCatService napcat;
    private final MessageArchive archive;
    private final QqOutbox outbox;
    private final LogBuffer log;
    private volatile Map<String, String> groups = Map.of();
    private volatile long groupsAt;
    private volatile Map<String, String> friends = Map.of();
    private volatile long friendsAt;

    QqContacts(ChatAgent agent, NapCatService napcat, MessageArchive archive, QqOutbox outbox, LogBuffer log) {
        this.agent = agent;
        this.napcat = napcat;
        this.archive = archive;
        this.outbox = outbox;
        this.log = log;
    }

    Map<String, String> groups() {
        long now = System.currentTimeMillis();
        if (now - groupsAt > TTL_MS && napcat.status().connected()) {
            groupsAt = now;
            Map<String, String> fresh = fetch("get_group_list", (o, names) -> names.put(optString(o, "group_id"), optString(o, "group_name")));
            if (fresh != null) {
                groups = fresh;
            }
        }
        return groups;
    }

    String groupName(String groupId) {
        String name = groups().get(groupId);
        return name == null || name.isBlank() ? "群 " + groupId : name;
    }

    Map<String, String> friends() {
        long now = System.currentTimeMillis();
        if (now - friendsAt > TTL_MS && napcat.status().connected()) {
            friendsAt = now;
            Map<String, String> fresh = fetch("get_friend_list", (o, names) -> {
                String remark = optString(o, "remark");
                names.put(optString(o, "user_id"), unforgeable(remark.isBlank() ? optString(o, "nickname") : remark));
            });
            if (fresh != null) {
                friends = fresh;
            }
        }
        return friends;
    }

    boolean canMessage(String userId) {
        return friends().containsKey(userId) || agent.session("private:" + userId, 0).totalTurns() > 0;
    }

    String findPerson(String query) {
        String q = query.strip();
        if (q.isEmpty()) {
            return "要找谁？写名字或者 QQ 号";
        }
        Map<String, List<String>> places = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Long> lastAt = new HashMap<>();
        for (MessageArchive.Seen s : archive.findPeople(q, 30)) {
            names.putIfAbsent(s.userId(), unforgeable(s.name()));
            lastAt.merge(s.userId(), s.lastAt(), Math::max);
            if (s.groupId() != null && !s.groupId().isBlank()) {
                places.computeIfAbsent(s.userId(), k -> new ArrayList<>()).add("「" + groupName(s.groupId()) + "」（群号 " + s.groupId() + "）");
            }
        }
        friends().forEach((id, name) -> {
            if (name.contains(q) || id.equals(q)) {
                names.putIfAbsent(id, name);
            }
        });
        if (names.isEmpty()) {
            return "没找到叫「" + q + "」的人：你见过的群里没人用过这个名字，好友里也没有。可以问问托你办事的人他的 QQ 号或者在哪个群。";
        }
        List<String> lines = new ArrayList<>();
        Set<String> owners = Set.copyOf(agent.personas().owners());
        names.entrySet().stream().limit(8).forEach(e -> {
            String id = e.getKey();
            List<String> in = places.getOrDefault(id, List.of());
            lines.add("- " + e.getValue() + "（QQ " + id + (owners.contains(id) ? "，你的主人" : "") + "）"
                    + (in.isEmpty() ? "" : "：在" + String.join("、", in.stream().distinct().limit(3).toList()) + "说过话")
                    + (lastAt.containsKey(id) ? "，最近一次是 " + Clock.ago(lastAt.get(id)) : "")
                    + (canMessage(id) ? "；可以私聊（say_private）" : "；不是好友，只能去你们都在的群里 @ 他"));
        });
        return String.join("\n", lines);
    }

    private Map<String, String> fetch(String action, BiConsumer<JsonObject, Map<String, String>> read) {
        try {
            JsonObject response = outbox.call(action, new JsonObject(), Duration.ofSeconds(10));
            Map<String, String> names = new LinkedHashMap<>();
            if (response.has("data") && response.get("data").isJsonArray()) {
                response.getAsJsonArray("data").forEach(e -> {
                    if (e.isJsonObject()) {
                        read.accept(e.getAsJsonObject(), names);
                    }
                });
            }
            return Map.copyOf(names);
        } catch (ToolFailure | RuntimeException e) {
            log.warn("agent: " + action + " failed: " + e.getMessage());
            return null;
        }
    }
}
