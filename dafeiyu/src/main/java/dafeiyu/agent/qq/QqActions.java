package dafeiyu.agent.qq;

import com.google.gson.JsonObject;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.message.StoredMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dafeiyu.agent.qq.QqMessages.unforgeable;
import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class QqActions {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_MUTE_MINUTES = 30 * 24 * 60;
    private static final int MAX_RECALL = 10;
    private static final int MAX_LEAVES_PER_DAY = 2;

    private final ChatAgent agent;
    private final MessageArchive archive;
    private final QqOutbox outbox;
    private final QqContacts contacts;
    private final LogBuffer log;
    private final List<Long> leaves = new ArrayList<>();

    QqActions(ChatAgent agent, MessageArchive archive, QqOutbox outbox, QqContacts contacts, LogBuffer log) {
        this.agent = agent;
        this.archive = archive;
        this.outbox = outbox;
        this.contacts = contacts;
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

    String latestMessageId(String groupId) {
        return archive.conversation(true, groupId, 50).reversed().stream().map(StoredMessage::messageId)
                .filter(id -> id != null && id.matches("-?\\d+")).findFirst().orElse(null);
    }

    public synchronized void checkLeave(String groupId) throws ToolFailure {
        if (!groupId.matches("\\d{5,12}")) {
            throw new ToolFailure("群号不对：" + groupId);
        }
        if (!contacts.groups().containsKey(groupId)) {
            throw new ToolFailure("你不在这个群里");
        }
        if (role(groupId, outbox.selfId()).equals("owner")) {
            throw new ToolFailure("你是「" + contacts.groupName(groupId) + "」的群主，退群会把整个群解散，不能退");
        }
        long now = System.currentTimeMillis();
        leaves.removeIf(at -> now - at > 86_400_000L);
        if (leaves.size() >= MAX_LEAVES_PER_DAY) {
            throw new ToolFailure("这一天里已经退过 " + MAX_LEAVES_PER_DAY + " 个群了，先缓一缓，真不想待就跟主人说");
        }
    }

    public String leaveGroup(String groupId, String reason) throws ToolFailure {
        String why = abbreviate(reason == null ? "" : reason.strip(), 200);
        if (why.isEmpty()) {
            throw new ToolFailure("要写清楚为什么退");
        }
        String name;
        synchronized (this) {
            checkLeave(groupId);
            name = contacts.groupName(groupId);
            JsonObject params = new JsonObject();
            params.addProperty("group_id", number(groupId));
            params.addProperty("is_dismiss", false);
            outbox.call("set_group_leave", params, TIMEOUT);
            leaves.add(System.currentTimeMillis());
        }
        contacts.forgetGroups();
        log.warn("agent [group " + groupId + "] left the group: " + why);
        for (String owner : agent.personas().owners()) {
            try {
                outbox.deliver(QqOutbox.Target.direct(owner, outbox.selfId(), ""), "我退出了「" + name + "」（群号 " + groupId + "）：" + why, null);
            } catch (ToolFailure e) {
                log.warn("agent: could not tell owner " + owner + " about leaving " + groupId + ": " + e.getMessage());
            }
        }
        return "退出了「" + name + "」，已经私聊告诉主人了";
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
        String via = outbox.tempGroup(userId) != null ? outbox.tempGroup(userId) : groupId.isEmpty() ? sharedGroup(userId) : groupId;
        if (via == null) {
            throw new ToolFailure(QqOutbox.NO_PRIVATE_CHAT);
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

    String senderOf(String messageId) {
        StoredMessage message = messageId == null || messageId.isBlank() ? null : archive.byMessageId(messageId.strip());
        return message == null || message.userId() == null ? "" : message.userId();
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
