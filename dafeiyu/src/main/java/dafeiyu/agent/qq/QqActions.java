package dafeiyu.agent.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.message.StoredMessage;
import dafeiyu.util.Clock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dafeiyu.agent.qq.QqMessages.unforgeable;
import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class QqActions {

    public record ForwardLine(String name, String userId, long time, String text, List<String> images, String attachments) {}

    private static final int MAX_FORWARD_LINES = 200;
    private static final int MAX_FORWARD_TEXT = 8000;
    private static final int MAX_FORWARD_CACHE = 500;
    private final Map<String, List<ForwardLine>> forwards = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_MUTE_MINUTES = 30 * 24 * 60;
    private static final int MAX_RECALL = 10;

    private final ChatAgent agent;
    private final MessageArchive archive;
    private final QqOutbox outbox;
    private final QqContacts contacts;
    private final QqImages images;
    private final LogBuffer log;

    QqActions(ChatAgent agent, MessageArchive archive, QqOutbox outbox, QqContacts contacts, QqImages images, LogBuffer log) {
        this.agent = agent;
        this.archive = archive;
        this.outbox = outbox;
        this.contacts = contacts;
        this.images = images;
        this.log = log;
    }

    public String recall(String groupId, String userId, int count) throws ToolFailure {
        String self = outbox.selfId();
        boolean mine = userId.isEmpty() || userId.equals(self);
        String who = mine ? self : userId;
        if (!mine) {
            requireManager(groupId);
            protect(groupId, who, "撤回");
        }
        int want = Math.max(1, Math.min(MAX_RECALL, count));
        List<StoredMessage> rows = archive.conversation(true, groupId, 200).reversed().stream()
                .filter(m -> who.equals(m.userId()) && m.messageId() != null && !m.messageId().isBlank())
                .limit(want).toList();
        if (rows.isEmpty()) {
            throw new ToolFailure(mine ? "你最近在这个群没说过话，没有能撤回的" : "这个人最近在这个群没说过话");
        }
        int done = 0;
        String error = "";
        for (StoredMessage m : rows) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("message_id", number(m.messageId()));
                outbox.call("delete_msg", params, TIMEOUT);
                done++;
            } catch (ToolFailure e) {
                error = e.getMessage();
            }
        }
        if (done == 0) {
            throw new ToolFailure("撤回失败：" + error + (mine ? "（自己的消息只能撤回 2 分钟内的）" : ""));
        }
        String name = mine ? "你自己" : name(groupId, who);
        log.info("agent [group " + groupId + "] recalled " + done + " message(s) of " + who);
        note(groupId, "（你撤回了" + name + "的 " + done + " 条消息）");
        return "撤回了" + name + "最近的 " + done + " 条消息" + (done < rows.size() ? "，另外 " + (rows.size() - done) + " 条没撤回成功：" + error : "");
    }

    public String mute(String groupId, String userId, int minutes) throws ToolFailure {
        requireManager(groupId);
        protect(groupId, userId, minutes > 0 ? "禁言" : "解除禁言");
        int duration = Math.max(0, Math.min(MAX_MUTE_MINUTES, minutes));
        JsonObject params = new JsonObject();
        params.addProperty("group_id", number(groupId));
        params.addProperty("user_id", number(userId));
        params.addProperty("duration", duration * 60);
        outbox.call("set_group_ban", params, TIMEOUT);
        String name = name(groupId, userId);
        log.info("agent [group " + groupId + "] " + (duration > 0 ? "muted " + userId + " for " + duration + " min" : "unmuted " + userId));
        note(groupId, duration > 0 ? "（你把" + name + "禁言了 " + minutesText(duration) + "）" : "（你解除了" + name + "的禁言）");
        return duration > 0 ? "把" + name + "禁言了 " + minutesText(duration) : "解除了" + name + "的禁言";
    }

    public String kick(String groupId, String userId, boolean block) throws ToolFailure {
        requireManager(groupId);
        protect(groupId, userId, "踢出群");
        String name = name(groupId, userId);
        JsonObject params = new JsonObject();
        params.addProperty("group_id", number(groupId));
        params.addProperty("user_id", number(userId));
        params.addProperty("reject_add_request", block);
        outbox.call("set_group_kick", params, TIMEOUT);
        log.info("agent [group " + groupId + "] kicked " + userId + (block ? " and blocked re-join" : ""));
        note(groupId, "（你把" + name + "踢出了群" + (block ? "，不让再加" : "") + "）");
        return "把" + name + "踢出了「" + contacts.groupName(groupId) + "」" + (block ? "，以后也加不进来了" : "");
    }

    public String deleteFriend(String userId) throws ToolFailure {
        if (agent.personas().owners().contains(userId)) {
            throw new ToolFailure("这是你的主人，不能删");
        }
        Map<String, String> friends = contacts.friends();
        if (!friends.containsKey(userId)) {
            throw new ToolFailure("QQ " + userId + " 不是你的好友");
        }
        JsonObject params = new JsonObject();
        params.addProperty("user_id", number(userId));
        params.addProperty("temp_block", false);
        params.addProperty("temp_both_del", false);
        outbox.call("delete_friend", params, TIMEOUT);
        contacts.forgetFriends();
        log.info("agent: deleted friend " + userId);
        return "删掉了好友 " + friends.get(userId) + "（QQ " + userId + "）";
    }

    public String messageStranger(String userId, String groupId, String text) throws ToolFailure {
        if (!userId.matches("\\d{5,12}") || userId.equals(outbox.selfId())) {
            throw new ToolFailure("QQ 号不对：" + userId);
        }
        String via = groupId.isEmpty() ? sharedGroup(userId) : groupId;
        if (via == null) {
            throw new ToolFailure("你和 QQ " + userId + " 没在同一个群里见过，发不了临时会话；去你们都在的群里 @ 他吧");
        }
        String formatted = outbox.prepare(text);
        if (formatted.isBlank()) {
            throw new ToolFailure("要说的话是空的");
        }
        outbox.review("", formatted);
        outbox.rememberTempGroup(userId, via);
        outbox.deliver(QqOutbox.Target.direct(userId, outbox.selfId(), ""), formatted, null);
        agent.record("private:" + userId, ChatMessage.assistant(formatted, ""));
        log.info("agent [private " + userId + " via group " + via + "] messaged a stranger: " + abbreviate(formatted.replace('\n', ' '), 120));
        return "通过「" + contacts.groupName(via) + "」的临时会话给 QQ " + userId + " 发了：" + formatted + "。TA 回你的话会在你们的私聊里。";
    }

    public List<ForwardLine> forward(String id) throws ToolFailure {
        String key = id == null ? "" : id.strip();
        if (key.isEmpty() || key.length() > 200) {
            throw new ToolFailure("合并转发的编号不对：" + id);
        }
        List<ForwardLine> cached = forwards.get(key);
        if (cached != null) {
            return cached;
        }
        JsonObject params = new JsonObject();
        params.addProperty("message_id", key);
        params.addProperty("id", key);
        JsonObject response;
        try {
            response = outbox.call("get_forward_msg", params, Duration.ofSeconds(20));
        } catch (ToolFailure e) {
            log.warn("agent: get_forward_msg " + key + " failed: " + e.getMessage());
            throw new ToolFailure("读不到这段合并转发（" + (e.getMessage().contains("内层") ? "它套在另一段合并转发里，先读外面那段" : "已经过期了，QQ 只能读刚发不久的") + "）");
        }
        JsonArray nodes = nodes(response == null ? null : response.get("data"));
        if (nodes == null || nodes.isEmpty()) {
            log.warn("agent: get_forward_msg " + key + " returned no messages: " + abbreviate(String.valueOf(response), 600));
            throw new ToolFailure("读不到这段合并转发（QQ 没把内容给过来）");
        }
        return remember(key, lines(nodes));
    }

    private static JsonArray nodes(JsonElement data) {
        if (data == null || data.isJsonNull()) {
            return null;
        }
        if (data.isJsonArray()) {
            return data.getAsJsonArray();
        }
        JsonObject object = data.getAsJsonObject();
        for (String name : List.of("messages", "message", "content", "nodes")) {
            if (object.has(name) && object.get(name).isJsonArray()) {
                return object.getAsJsonArray(name);
            }
        }
        return null;
    }

    private List<ForwardLine> remember(String key, List<ForwardLine> lines) {
        while (forwards.size() >= MAX_FORWARD_CACHE) {
            forwards.remove(forwards.keySet().iterator().next());
        }
        forwards.put(key, List.copyOf(lines));
        return lines;
    }

    public void prefetch(JsonObject event) {
        JsonElement message = event.get("message");
        if (message == null || !message.isJsonArray()) {
            return;
        }
        for (JsonElement segment : message.getAsJsonArray()) {
            JsonObject seg = segment.isJsonObject() ? segment.getAsJsonObject() : null;
            JsonObject data = seg == null ? null : optObject(seg, "data");
            String id = data == null ? "" : optString(data, "id");
            if (seg != null && "forward".equals(optString(seg, "type")) && !id.isBlank() && !forwards.containsKey(id)) {
                Thread.ofVirtual().start(() -> {
                    try {
                        forward(id);
                    } catch (ToolFailure ignored) {
                    }
                });
            }
        }
    }

    private void cacheNested(JsonObject event) {
        JsonElement message = event.get("message");
        if (message == null || !message.isJsonArray()) {
            return;
        }
        for (JsonElement segment : message.getAsJsonArray()) {
            JsonObject data = segment.isJsonObject() ? optObject(segment.getAsJsonObject(), "data") : null;
            String id = data == null ? "" : optString(data, "id");
            JsonArray inner = data == null ? null : nodes(data);
            if ("forward".equals(optString(segment.getAsJsonObject(), "type")) && !id.isBlank() && inner != null && !inner.isEmpty()) {
                remember(id, lines(inner));
            }
        }
    }

    private List<ForwardLine> lines(JsonArray nodes) {
        String self = outbox.selfId();
        List<ForwardLine> lines = new ArrayList<>();
        for (JsonElement e : nodes) {
            if (!e.isJsonObject() || lines.size() >= MAX_FORWARD_LINES) {
                continue;
            }
            JsonObject node = e.getAsJsonObject();
            JsonObject data2 = optObject(node, "data");
            JsonObject event = node.has("message") || node.has("raw_message") ? node : data2 != null && data2.has("content") ? content(data2) : node;
            JsonObject sender = optObject(event, "sender");
            String userId = sender == null ? optString(event, "user_id") : optString(sender, "user_id");
            String name = sender == null ? optString(data2 == null ? event : data2, "nickname")
                    : !optString(sender, "card").isBlank() ? optString(sender, "card") : optString(sender, "nickname");
            QqMessages.Incoming in = QqMessages.parse(event, self, (qq, nick) -> nick.isBlank() ? qq : nick, null,
                    (file, url) -> images.number(file, url, "forward"));
            long time = event.has("time") ? event.get("time").getAsLong() * 1000 : 0;
            lines.add(new ForwardLine(unforgeable(name.isBlank() ? "QQ " + userId : name), userId, time, in.text(), in.imageUrls(), in.attachments()));
            cacheNested(event);
        }
        return lines;
    }

    public String describeForward(String id) throws ToolFailure {
        List<ForwardLine> lines = forward(id);
        StringBuilder out = new StringBuilder("这段合并转发的聊天记录一共 " + lines.size() + " 条：\n");
        for (ForwardLine l : lines) {
            String row = (l.time() > 0 ? "[" + Clock.ago(l.time()) + "] " : "") + l.name() + "：" + l.text().replace("\n", " / ") + "\n";
            if (out.length() + row.length() > MAX_FORWARD_TEXT) {
                out.append("……后面还有，太长了没列完\n");
                break;
            }
            out.append(row);
        }
        return out.append("（里面再套的合并转发，用它的编号再读一次）").toString();
    }

    private static JsonObject content(JsonObject data) {
        JsonObject event = new JsonObject();
        event.add("message", data.get("content"));
        JsonObject sender = new JsonObject();
        sender.addProperty("user_id", optString(data, "user_id"));
        sender.addProperty("nickname", optString(data, "nickname"));
        event.add("sender", sender);
        return event;
    }

    String sharedGroup(String userId) {
        Map<String, String> mine = contacts.groups();
        for (MessageArchive.Seen s : archive.findPeople(userId, 20)) {
            if (s.userId().equals(userId) && s.groupId() != null && !s.groupId().isBlank()
                    && (mine.isEmpty() || mine.containsKey(s.groupId()))) {
                return s.groupId();
            }
        }
        return null;
    }

    private void requireManager(String groupId) throws ToolFailure {
        if (!groupId.matches("\\d{5,12}")) {
            throw new ToolFailure("群号不对：" + groupId);
        }
        String role = role(groupId, outbox.selfId());
        if (!role.equals("owner") && !role.equals("admin")) {
            throw new ToolFailure("你在「" + contacts.groupName(groupId) + "」不是管理员，做不了这个");
        }
    }

    private void protect(String groupId, String userId, String what) throws ToolFailure {
        if (!userId.matches("\\d{5,12}")) {
            throw new ToolFailure("QQ 号不对：" + userId);
        }
        if (userId.equals(outbox.selfId())) {
            throw new ToolFailure("不能对你自己" + what);
        }
        if (agent.personas().owners().contains(userId)) {
            throw new ToolFailure("这是你的主人，不能对主人" + what);
        }
        String target = role(groupId, userId);
        if (target.isEmpty()) {
            throw new ToolFailure("QQ " + userId + " 不在这个群里");
        }
        if (target.equals("owner")) {
            throw new ToolFailure("对方是群主，不能" + what);
        }
        if (target.equals("admin") && !role(groupId, outbox.selfId()).equals("owner")) {
            throw new ToolFailure("对方也是管理员，只有群主才能对管理员" + what);
        }
    }

    private String role(String groupId, String userId) throws ToolFailure {
        JsonObject params = new JsonObject();
        params.addProperty("group_id", number(groupId));
        params.addProperty("user_id", number(userId));
        params.addProperty("no_cache", true);
        try {
            JsonObject data = optObject(outbox.call("get_group_member_info", params, TIMEOUT), "data");
            return data == null ? "" : optString(data, "role");
        } catch (ToolFailure e) {
            return "";
        }
    }

    private String name(String groupId, String userId) {
        return unforgeable(archive.groupSenders(groupId).getOrDefault(userId, "QQ " + userId));
    }

    void note(String groupId, String text) {
        try {
            agent.record("group:" + groupId, ChatMessage.assistant(text, ""));
        } catch (RuntimeException ignored) {
        }
    }

    private static String minutesText(int minutes) {
        if (minutes % 1440 == 0) {
            return minutes / 1440 + " 天";
        }
        return minutes % 60 == 0 ? minutes / 60 + " 小时" : minutes + " 分钟";
    }

    private static long number(String id) throws ToolFailure {
        try {
            return Long.parseLong(id.strip());
        } catch (NumberFormatException e) {
            throw new ToolFailure("编号不对：" + id);
        }
    }
}
