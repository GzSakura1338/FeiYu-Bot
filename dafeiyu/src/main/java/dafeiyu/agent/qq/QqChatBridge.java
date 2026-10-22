package dafeiyu.agent.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.ChatTurn;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.SessionDetail;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.TurnRunner;
import dafeiyu.agent.context.HeldLines;
import dafeiyu.agent.intercom.Intercom;
import dafeiyu.agent.model.AgentStore;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.qq.GroupState.Line;
import dafeiyu.agent.qq.QqMessages.Incoming;
import dafeiyu.agent.qq.QqOutbox.Target;
import dafeiyu.agent.reply.Markup;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.NapCatService;
import dafeiyu.util.Clock;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static dafeiyu.agent.qq.QqMessages.senderName;
import static dafeiyu.agent.qq.QqMessages.unforgeable;
import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class QqChatBridge {

    private static final int MAX_IMAGES = 4;
    private static final String OWNER_TAG = "【主人】";
    private static final String POKE_TAG = "【拍一拍】";
    private static final long POKE_GAP_MS = 20_000;
    private static final String CONSOLE_TAG = "【控制台】";
    private static final long RESUME_WINDOW_MS = 10 * 60_000L;
    private static final String RESUMED = "你刚才正在回这条，回到一半被重启打断了，对方还没收到你的回复。接着把它回完；刚才在做的事（写文件、调工具）如果没做完，"
            + "看看聊天记录里做到哪一步了，接着做完，别从头重来。";
    private static final String FOCUS ="对方在说什么就接什么，先回应这条，别答非所问；别扯回前面已经聊完的话题，也别突然提起很久以前的事或者别处的事，除非对方自己提到。";
    private static final long AMBIENT_QUIET_MIN_MS = 2_000;
    private static final long AMBIENT_QUIET_MAX_MS = 4_000;
    private static final long AMBIENT_MAX_WAIT_MS = 8_000;
    private static final long RECENT_TALK_MS = 10 * 60_000;
    private static final long CONVERSATION_MS = 3 * 60_000;
    private static final long ACTIVE_MEMBERS_MS = 3 * 86_400_000L;
    private static final int ACTIVE_MEMBERS = 12;
    private static final String SEEN_MARK = "（以上是你刚才看过的）";
    private static final long FAVORITES_EVERY_MS = 6 * 3_600_000L;

    private enum Mode { ADDRESSED, AMBIENT, NOTE }

    public interface Observer {

        void heard(String where, String who, String text, boolean toMe);

        void said(String where, String text);
    }

    private static final class Inbox {
        boolean busy;
        boolean pending;
        Target target;
        final List<String> images = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        boolean resumed;
    }

    private final ChatAgent agent;
    private final NapCatService napcat;
    private final MessageArchive archive;
    private final LogBuffer log;
    private final Map<String, GroupState> groups = new ConcurrentHashMap<>();
    private final Map<String, Inbox> inboxes = new ConcurrentHashMap<>();
    private final Set<String> resumedGroups = ConcurrentHashMap.newKeySet();
    private final Map<String, String> latestInGroup = new ConcurrentHashMap<>();
    private final Set<String> ownerNames = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> pokedAt = new ConcurrentHashMap<>();
    private final Deque<Long> proactive = new ArrayDeque<>();
    private final QqOutbox outbox;
    private final QqContacts contacts;
    private final QqBackfill backfill;
    private final QqActions actions;
    private volatile Observer observer;
    private volatile MessageGate gate = MessageGate.NONE;
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile long favoritesSyncedAt;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "group-chat");
        t.setDaemon(true);
        return t;
    });

    public QqChatBridge(ChatAgent agent, NapCatService napcat, MessageArchive archive, LogBuffer log) {
        this.agent = agent;
        this.napcat = napcat;
        this.archive = archive;
        this.log = log;
        this.outbox = new QqOutbox(agent, napcat, archive, log, groups);
        this.contacts = new QqContacts(agent, napcat, archive, outbox, log);
        this.backfill = new QqBackfill(agent, archive, outbox, log);
        this.actions = new QqActions(agent, archive, outbox, contacts, log);
        agent.setForwardReader(actions::describeForward);
        agent.setPoker((session, userId) -> {
            try {
                return poke(session, userId);
            } catch (ToolFailure e) {
                log.info("agent [" + session + "] poke skipped: " + e.getMessage());
                return null;
            }
        });
        agent.intercom().onNotes(this::deliverNotes);
        agent.held().takeAll().forEach((session, lines) -> {
            lines.forEach(h -> agent.hear(session, h.line(), h.userId(), h.attachments()));
            log.info("agent [" + session + "] restored " + lines.size() + " message(s) heard while a reply was cut off");
        });
    }

    public String whyNotTell(String session) {
        if (!agent.store().qqReply()) {
            return "QQ 自动回复没开";
        }
        if (!napcat.status().connected()) {
            return "QQ 没连上";
        }
        if (session.startsWith("group:")) {
            if (!agent.store().groupMode()) {
                return "群聊模式没开";
            }
            Map<String, String> names = contacts.groups();
            return !names.isEmpty() && !names.containsKey(session.substring(6)) ? "不在这个群里" : null;
        }
        if (session.startsWith("private:")) {
            String userId = session.substring(8);
            if (!userId.matches("\\d{5,12}")) {
                return "QQ 号不对";
            }
            return contacts.canMessage(userId) || outbox.hasTempGroup(userId) ? null
                    : "不是好友，也没私聊过；可以用 message_stranger 走群临时会话直接发，或者去你们都在的群里 @ 他";
        }
        return "只能交给群聊或私聊";
    }

    private void deliverNotes(String session) {
        String selfId = outbox.selfId();
        if (session.startsWith("group:")) {
            startGroupTurn(groups.computeIfAbsent(session.substring(6), GroupState::new), selfId, Mode.NOTE);
            return;
        }
        if (!session.startsWith("private:")) {
            return;
        }
        Inbox inbox = inboxes.computeIfAbsent(session, k -> new Inbox());
        synchronized (inbox) {
            if (inbox.target == null) {
                inbox.target = Target.direct(session.substring(8), selfId, "");
            }
            if (inbox.busy) {
                return;
            }
            inbox.busy = true;
        }
        executor.submit(() -> runPrivate(session, inbox));
    }

    private static String notePrompt(List<Intercom.Note> notes) {
        return "（你心里想到要在这里说：" + String.join("；", notes.stream().map(Intercom.Note::intent).toList())
                + "。这是你自己的念头，不是别人问的。结合现在的聊天，像平时一样自然地说出来，可以 @ 相关的人；"
                + "只有刚刚已经说过一样的意思时才只写 " + TurnRunner.SILENCE + "，否则就说出来。）";
    }

    private void countProactive() {
        synchronized (proactive) {
            proactive.addLast(System.currentTimeMillis());
        }
    }

    private void runPrivateNotes(String session, Target target) {
        List<Intercom.Note> notes = agent.intercom().takeNotes(session);
        if (notes.isEmpty()) {
            return;
        }
        String why = whyNotTell(session);
        if (why != null) {
            agent.intercom().receipt(notes, "私聊", null, why);
            return;
        }
        ChatTurn turn = new ChatTurn(session, ChatMessage.user(notePrompt(notes)), target.userId(), outbox.files(target), "note",
                List.of(target.userId()), Map.of(), true);
        String sent = reply(target, turn, true);
        agent.intercom().receipt(notes, "私聊", sent, "看了看私聊，觉得现在不说比较好");
        if (sent != null) {
            countProactive();
        }
    }

    public void setObserver(Observer observer) {
        this.observer = observer;
    }

    public void setGate(MessageGate gate) {
        this.gate = gate == null ? MessageGate.NONE : gate;
        outbox.setGate(this.gate);
    }

    public String pluginSend(String session, String text, String imageUrl, String quote, String label) throws ToolFailure {
        if (!napcat.status().connected()) {
            throw new ToolFailure("QQ is not connected");
        }
        Target target = outbox.targetOf(session);
        if (target == null) {
            throw new ToolFailure("unknown session " + session);
        }
        String formatted = text == null || text.isBlank() ? "" : outbox.prepare(text);
        outbox.deliverForPlugin(target, formatted, imageUrl, quote);
        try {
            agent.hear(session, "【插件·" + label + "】：" + (formatted.isEmpty() ? "[图片]" : formatted.replace("\n", " / ")));
        } catch (RuntimeException ignored) {
        }
        return formatted;
    }

    public JsonArray groupMembers(String groupId) throws ToolFailure {
        JsonObject params = new JsonObject();
        params.addProperty("group_id", Long.parseLong(groupId));
        JsonObject response = outbox.call("get_group_member_list", params, Duration.ofSeconds(15));
        JsonArray out = new JsonArray();
        if (response.has("data") && response.get("data").isJsonArray()) {
            for (var e : response.getAsJsonArray("data")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject m = e.getAsJsonObject();
                JsonObject item = new JsonObject();
                item.addProperty("userId", optString(m, "user_id"));
                item.addProperty("nickname", optString(m, "nickname"));
                item.addProperty("card", optString(m, "card"));
                item.addProperty("role", optString(m, "role"));
                out.add(item);
            }
        }
        return out;
    }

    public boolean connected() {
        return napcat.status().connected();
    }

    private void observeHeard(String where, String who, String text, boolean toMe) {
        Observer o = observer;
        if (o != null) {
            try {
                o.heard(where, who, text, toMe);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void observeSaid(String where, String text) {
        Observer o = observer;
        if (o != null) {
            try {
                o.said(where, text);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private String memberOr(String groupId, String userId) {
        String name = contacts.memberName(groupId, userId);
        return name.isBlank() ? "QQ " + userId : name;
    }

    public QqActions actions() {
        return actions;
    }

    public void onEvent(JsonObject event) {
        if (optString(event, "post_type").equals("notice") && optString(event, "sub_type").equals("poke")) {
            onPoke(event);
            return;
        }
        if (optString(event, "post_type").equals("notice") && optString(event, "notice_type").equals("group_card")) {
            contacts.forgetMember(optString(event, "group_id"), optString(event, "user_id"));
            return;
        }
        if (optString(event, "post_type").equals("request") && optString(event, "request_type").equals("friend")) {
            executor.submit(() -> {
                actions.friendRequested(event);
                String userId = optString(event, "user_id");
                String comment = optString(event, "comment").strip();
                observeHeard("private:" + userId, "QQ " + userId + " 想加你好友（验证消息：" + (comment.isEmpty() ? "没写" : abbreviate(comment, 60))
                        + "），用 friend_requests 看看、answer_friend 决定", comment, true);
            });
            return;
        }
        if (optString(event, "post_type").equals("message")) {
            if (optString(event, "message_type").equals("private") && optString(event, "sub_type").equals("group")) {
                JsonObject sender = optObject(event, "sender");
                String via = optString(event, "group_id");
                if (via.isEmpty() && sender != null) {
                    via = optString(sender, "group_id");
                }
                outbox.rememberTempGroup(optString(event, "user_id"), via);
            }
            ingest(event, true);
        }
    }

    public void onConnected() {
        resumeInterrupted();
        backfill.run(this::ingest);
        agent.intercom().sessionsWithNotes().forEach(this::deliverNotes);
    }

    private void resumeInterrupted() {
        long now = System.currentTimeMillis();
        String selfId = outbox.selfId();
        int resumed = 0;
        for (AgentStore.Replying r : agent.store().takeReplying()) {
            if (now - r.at() > RESUME_WINDOW_MS || !agent.store().qqReply()) {
                continue;
            }
            if (r.session().startsWith("private:")) {
                Inbox inbox = inboxes.computeIfAbsent(r.session(), k -> new Inbox());
                synchronized (inbox) {
                    inbox.target = Target.direct(r.userId(), selfId, r.messageId());
                    inbox.lines.addAll(List.of(r.line().split("\n")));
                    inbox.resumed = true;
                    if (inbox.busy) {
                        inbox.pending = true;
                        resumed++;
                        continue;
                    }
                    inbox.busy = true;
                }
                executor.submit(() -> runPrivate(r.session(), inbox));
            } else if (r.session().startsWith("group:")) {
                String groupId = r.session().substring(6);
                GroupState state = groups.computeIfAbsent(groupId, GroupState::new);
                state.add(new Line(r.messageId(), r.userId(), r.speaker(), r.line(), now, true, List.of(), false));
                resumedGroups.add(groupId);
                startGroupTurn(state, selfId, Mode.ADDRESSED);
            }
            resumed++;
        }
        if (resumed > 0) {
            log.info("agent: picked up " + resumed + " reply(ies) interrupted by the restart");
        }
    }

    private void ingest(JsonObject event, boolean react) {
        String messageType = optString(event, "message_type");
        if (messageType.equals("group")) {
            latestInGroup.put(optString(event, "group_id"), optString(event, "message_id"));
        }
        boolean replying = agent.store().qqReply();
        MessageGate plugins = gate;
        if (!replying && plugins == MessageGate.NONE) {
            return;
        }
        String selfId = optString(event, "self_id");
        String userId = optString(event, "user_id");
        if (userId.isEmpty() || userId.equals(selfId)) {
            return;
        }
        GroupState state = messageType.equals("group") ? groups.computeIfAbsent(optString(event, "group_id"), GroupState::new) : null;
        String eventGroup = optString(event, "group_id");
        Incoming incoming = QqMessages.parse(event, selfId, state == null ? (qq, hint) -> hint
                : (qq, hint) -> state.mentionName(qq, hint == null || hint.isBlank() ? contacts.memberName(eventGroup, qq) : hint,
                        agent.personas().isOwner(qq), this::reservedName), this::stickerLabel);
        if (incoming.text().isEmpty() && incoming.imageUrls().isEmpty()) {
            return;
        }
        boolean owner = agent.personas().isOwner(userId);
        if (owner && !senderName(event).isBlank()) {
            ownerNames.add(senderName(event).strip());
        }
        String text = owner ? incoming.text() : unforgeable(incoming.text());
        MessageGate.Verdict verdict = MessageGate.Verdict.PASS;
        if (messageType.equals("group") || messageType.equals("private")) {
            try {
                verdict = plugins.incoming(new MessageGate.Message(messageType, optString(event, "group_id"), userId,
                        unforgeable(senderName(event)), owner, text, incoming.attachments(), incoming.mentionedMe(),
                        optString(event, "message_id"), System.currentTimeMillis(), react));
            } catch (RuntimeException e) {
                log.warn("plugins: message dispatch failed: " + e);
            }
        }
        if (!replying) {
            return;
        }
        if (verdict.drop()) {
            react = false;
        } else if (verdict.text() != null) {
            text = verdict.text();
        }
        if (state != null) {
            onGroupMessage(event, state, incoming, text, owner, selfId, userId, react);
        } else if (messageType.equals("private")) {
            if (react) {
                observeHeard("private:" + userId, owner ? "主人" : unforgeable(senderName(event)), text, true);
            }
            onPrivate(Target.direct(userId, selfId, optString(event, "message_id")), (owner ? OWNER_TAG : "") + text,
                    incoming.imageUrls(), incoming.attachments(), react);
        }
    }

    private void onPoke(JsonObject event) {
        if (!agent.store().qqReply()) {
            return;
        }
        String selfId = optString(event, "self_id");
        String userId = optString(event, "user_id");
        String targetId = optString(event, "target_id");
        if (userId.isEmpty() || userId.equals(selfId)) {
            return;
        }
        List<String> words = new ArrayList<>();
        if (event.has("raw_info") && event.get("raw_info").isJsonArray()) {
            event.getAsJsonArray("raw_info").forEach(e -> {
                if (e.isJsonObject() && optString(e.getAsJsonObject(), "type").equals("nor")) {
                    String txt = optString(e.getAsJsonObject(), "txt").strip();
                    if (!txt.isEmpty()) {
                        words.add(txt);
                    }
                }
            });
        }
        String action = words.isEmpty() ? "拍了拍" : unforgeable(words.getFirst());
        String suffix = words.size() > 1 ? unforgeable(String.join("", words.subList(1, words.size()))) : "";
        boolean me = targetId.equals(selfId);
        boolean owner = agent.personas().isOwner(userId);
        JsonArray attachments = new JsonArray();
        JsonObject poke = new JsonObject();
        poke.addProperty("kind", "poke");
        attachments.add(poke);
        String groupId = optString(event, "group_id");
        if (groupId.isEmpty() || groupId.equals("0")) {
            String line = (owner ? OWNER_TAG : "") + POKE_TAG + (owner ? "主人" : "对方") + " " + action + " 你" + suffix;
            observeHeard("private:" + userId, owner ? "主人" : "对方", action + " 你" + suffix, true);
            onPrivate(Target.direct(userId, selfId, ""), line, List.of(), attachments.toString(), true);
            return;
        }
        GroupState state = groups.computeIfAbsent(groupId, GroupState::new);
        String name = state.label(unforgeable(memberOr(groupId, userId)), userId, owner, this::reservedName);
        String target = me ? "你" : state.label(unforgeable(memberOr(groupId, targetId)), targetId,
                agent.personas().isOwner(targetId), this::reservedName);
        String line = (owner ? OWNER_TAG : "") + POKE_TAG + name + " " + action + " " + target + suffix;
        observeHeard("group:" + groupId, (owner ? "主人 " : "") + name, action + " " + target + suffix, me);
        boolean addressed = me && !state.cooling(userId) && !looksLikeMe(name);
        state.add(new Line("", userId, name, line, System.currentTimeMillis(), addressed, List.of(), false));
        hear(state, line, userId, attachments.toString());
        if (!agent.store().groupMode()) {
            return;
        }
        react(state, selfId, addressed);
    }

    private void onGroupMessage(JsonObject event, GroupState state, Incoming incoming, String text, boolean owner, String selfId, String userId,
                                boolean react) {
        String groupId = state.groupId;
        String messageId = optString(event, "message_id");
        String sender = senderName(event);
        if (sender.isBlank() || sender.equals(userId)) {
            sender = contacts.memberName(groupId, userId);
        }
        String name = state.label(unforgeable(sender), userId, owner, this::reservedName);
        String quote = incoming.replyTo().isEmpty() ? "" : state.quote(incoming.replyTo());
        String flat = text.strip().replaceAll("\\s*\\R+\\s*", " / ");
        String line = (owner ? OWNER_TAG : "") + name + quote + "：" + flat;
        state.heard(messageId, userId, name, line);
        if (!react) {
            hear(state, line, userId, incoming.attachments());
            return;
        }
        boolean toOthers = incoming.mentionedOthers() || (!incoming.replyTo().isEmpty() && !state.isBotMessage(incoming.replyTo()));
        boolean continuing = !toOthers && (state.talkingWith(userId, CONVERSATION_MS)
                || (owner && System.currentTimeMillis() - state.lastSpoke() < CONVERSATION_MS));
        boolean addressed = agent.store().groupMode()
                ? (incoming.mentionedMe() || state.isBotMessage(incoming.replyTo()) || (!incoming.mentionedOthers() && calledByName(incoming.text()))
                        || continuing)
                        && !looksLikeMe(name) && (owner || !state.cooling(userId))
                : incoming.mentionedMe();
        observeHeard("group:" + groupId, (owner ? "主人 " : "") + name, flat,
                addressed || incoming.mentionedMe() || state.isBotMessage(incoming.replyTo()));
        state.add(new Line(messageId, userId, name, line, System.currentTimeMillis(), addressed, incoming.imageUrls(), false));
        hear(state, line, userId, incoming.attachments());
        if (agent.store().groupMode() || addressed) {
            react(state, selfId, addressed);
        }
    }

    private void react(GroupState state, String selfId, boolean addressed) {
        long wait = state.calmIn();
        if (wait > 0) {
            holdBack(state, selfId, addressed, wait);
        } else if (addressed) {
            lastActivity = System.currentTimeMillis();
            startGroupTurn(state, selfId, Mode.ADDRESSED);
        } else {
            scheduleAmbient(state, selfId);
        }
    }

    private void holdBack(GroupState state, String selfId, boolean addressed, long wait) {
        synchronized (state) {
            state.calmAddressed |= addressed;
            if (state.calm != null) {
                return;
            }
            log.info("agent [group " + state.groupId + "] said a lot just now, looking again in " + (wait / 1000 + 1) + "s");
            state.calm = scheduler.schedule(() -> {
                boolean again;
                synchronized (state) {
                    again = state.calmAddressed;
                    state.calmAddressed = false;
                    state.calm = null;
                }
                react(state, selfId, again);
            }, wait + 500, TimeUnit.MILLISECONDS);
        }
    }

    private void onPrivate(Target target, String line, List<String> images, String attachments, boolean react) {
        String session = target.session();
        try {
            agent.hear(session, line, target.userId(), attachments);
        } catch (RuntimeException e) {
            log.warn("agent [" + target.where() + "] saving a private message failed: " + e.getMessage());
            return;
        }
        if (!react) {
            return;
        }
        lastActivity = System.currentTimeMillis();
        Inbox inbox = inboxes.computeIfAbsent(session, k -> new Inbox());
        synchronized (inbox) {
            inbox.target = target;
            inbox.images.addAll(images);
            inbox.lines.add(line);
            if (inbox.busy) {
                inbox.pending = true;
                return;
            }
            inbox.busy = true;
        }
        executor.submit(() -> runPrivate(session, inbox));
    }

    private void runPrivate(String session, Inbox inbox) {
        while (true) {
            Target target;
            List<String> images;
            List<String> latest;
            boolean resumed;
            synchronized (inbox) {
                target = inbox.target;
                images = List.copyOf(inbox.images.subList(Math.max(0, inbox.images.size() - MAX_IMAGES), inbox.images.size()));
                latest = List.copyOf(inbox.lines.subList(Math.max(0, inbox.lines.size() - 3), inbox.lines.size()));
                resumed = inbox.resumed;
                inbox.images.clear();
                inbox.lines.clear();
                inbox.pending = false;
                inbox.resumed = false;
            }
            if (latest.isEmpty()) {
                try {
                    runPrivateNotes(session, target);
                } catch (RuntimeException e) {
                    log.error("agent [" + target.where() + "] note turn crashed: " + e);
                }
                synchronized (inbox) {
                    if (!inbox.pending && !agent.intercom().hasNotes(session)) {
                        inbox.busy = false;
                        return;
                    }
                }
                continue;
            }
            String quoted = String.join("", latest.stream().map(l -> "「" + abbreviate(l, 200) + "」").toList());
            String prompt = "（" + (resumed ? RESUMED : "") + "对方在私聊里刚发来：" + quoted + "。" + FOCUS + "）";
            agent.store().markReplying(new AgentStore.Replying(session, target.userId(), target.messageId(), "", String.join("\n", latest),
                    System.currentTimeMillis()));
            try {
                ChatTurn turn = new ChatTurn(session, ChatMessage.user(prompt, download(target, images)), target.userId(),
                        outbox.files(target), "qq", List.of(target.userId()), Map.of(), true);
                if (reply(target, turn, true) != null) {
                    agent.intercom().report(session, "你在私聊里回了 QQ " + target.userId() + " 一句（私聊的内容留在私聊里）");
                }
            } catch (RuntimeException e) {
                log.error("agent [" + target.where() + "] reply crashed: " + e);
            } finally {
                agent.store().clearReplying(session);
            }
            synchronized (inbox) {
                if (!inbox.pending && !agent.intercom().hasNotes(session)) {
                    inbox.busy = false;
                    return;
                }
            }
        }
    }

    private void hear(GroupState state, String line, String userId, String attachments) {
        synchronized (state) {
            if (state.busy) {
                agent.held().add("group:" + state.groupId, new HeldLines.Line(line, userId, attachments));
                return;
            }
            heardNow(state, line, userId, attachments);
        }
    }

    private void heardNow(GroupState state, String line, String userId, String attachments) {
        try {
            agent.hear("group:" + state.groupId, line, userId, attachments);
        } catch (RuntimeException e) {
            log.warn("agent [group " + state.groupId + "] saving a group message failed: " + e.getMessage());
        }
    }

    private boolean claim(GroupState state) {
        synchronized (state) {
            if (state.busy) {
                return false;
            }
            state.busy = true;
            if (state.ambient != null) {
                state.ambient.cancel(false);
                state.ambient = null;
            }
            return true;
        }
    }

    private void release(GroupState state, String selfId) {
        boolean again;
        synchronized (state) {
            agent.held().take("group:" + state.groupId).forEach(h -> heardNow(state, h.line(), h.userId(), h.attachments()));
            state.busy = false;
            again = state.pendingAddressed;
            state.pendingAddressed = false;
        }
        if (again) {
            startGroupTurn(state, selfId, Mode.ADDRESSED);
        } else if (agent.intercom().hasNotes("group:" + state.groupId)) {
            startGroupTurn(state, selfId, Mode.NOTE);
        } else if (state.hasNewLines()) {
            scheduleAmbient(state, selfId);
        }
    }

    private void startGroupTurn(GroupState state, String selfId, Mode mode) {
        if (!claim(state)) {
            synchronized (state) {
                state.pendingAddressed |= mode == Mode.ADDRESSED;
            }
            return;
        }
        executor.submit(() -> {
            try {
                runGroupTurn(state, selfId, mode);
            } catch (RuntimeException e) {
                log.error("agent [group " + state.groupId + "] reply crashed: " + e);
            } finally {
                release(state, selfId);
            }
        });
    }

    private void runGroupTurn(GroupState state, String selfId, Mode mode) {
        String session = "group:" + state.groupId;
        List<Line> lines = state.drain();
        List<Intercom.Note> notes = mode == Mode.NOTE ? agent.intercom().takeNotes(session) : List.of();
        if (mode == Mode.NOTE && notes.isEmpty()) {
            state.restore(lines);
            return;
        }
        if (lines.isEmpty() && notes.isEmpty()) {
            return;
        }
        String place = "「" + groupName(state.groupId) + "」";
        if (!notes.isEmpty()) {
            String why = whyNotTell(session);
            if (why != null) {
                state.restore(lines);
                agent.intercom().receipt(notes, place, null, why);
                return;
            }
        }
        Line trigger = null;
        if (mode == Mode.ADDRESSED) {
            for (int i = lines.size() - 1; i >= 0 && trigger == null; i--) {
                trigger = lines.get(i).addressed() ? lines.get(i) : null;
            }
        }
        StringBuilder prompt = new StringBuilder();
        List<String> lookalikes = lines.stream().map(Line::speaker).filter(this::looksLikeMe).distinct().toList();
        if (!lookalikes.isEmpty()) {
            prompt.append("（").append(String.join("、", lookalikes))
                    .append(" 是群里和你重名的别人（多半是另一个机器人），不是你：那些话不是你说的，别人 @ 它、回复它、跟它聊天也不是在找你。"
                            + "跟它斗两句嘴可以，别没完没了地来回接。）");
        }
        List<String> active = new ArrayList<>();
        if (!notes.isEmpty()) {
            List<String> names = new ArrayList<>();
            archive.recentSenders(state.groupId, System.currentTimeMillis() - ACTIVE_MEMBERS_MS, ACTIVE_MEMBERS).forEach((qq, name) -> {
                if (!qq.equals(selfId)) {
                    names.add(state.know(qq, unforgeable(name), this::reservedName));
                    active.add(qq);
                }
            });
            prompt.append(prompt.isEmpty() ? "" : "\n").append(notePrompt(notes))
                    .append(names.isEmpty() ? "" : "（这个群最近说过话的人：" + String.join("、", names) + "。要喊人、要找谁，就 @ 这些名字）");
        } else if (trigger == null) {
            int said = state.spokenWithin(RECENT_TALK_MS);
            prompt.append(prompt.isEmpty() ? "" : "\n").append(TurnRunner.AMBIENT).append("以上是群里刚刚的聊天，没人 @ 你。")
                    .append(said > 0 ? "你最近十分钟已经在群里说了 " + said + " 句。" : "你最近十分钟没在群里说话。")
                    .append("你是群里最活跃的那个，看到了就接一句：接话、吐槽、起哄、回答、关心人、发个表情包都行，别光看着；")
                    .append("只有别人两个人之间 @ 着说私事、实在插不上嘴时，才只写 ").append(TurnRunner.SILENCE).append("。");
        } else {
            prompt.append(prompt.isEmpty() ? "" : "\n").append("（").append(resumedGroups.remove(state.groupId) ? RESUMED : "")
                    .append("有人在跟你说话，现在要回的是这条：「").append(abbreviate(trigger.text(), 300)).append("」。").append(FOCUS).append("）");
        }
        Target target = trigger == null
                ? Target.group(state.groupId, "", selfId, "")
                : Target.group(state.groupId, trigger.userId(), selfId, trigger.messageId());
        List<ImagePart> images = trigger == null ? List.of() : download(target, trigger.imageUrls());
        List<String> people = new ArrayList<>();
        if (trigger != null) {
            people.add(trigger.userId());
        }
        lines.reversed().forEach(l -> people.add(l.userId()));
        people.addAll(active);
        List<String> present = people.stream().distinct().limit(6).toList();
        Map<String, String> roster = state.roster(people.stream().distinct().toList());
        ChatTurn turn = trigger == null
                ? new ChatTurn(session, ChatMessage.user(prompt.toString()), "0", null, notes.isEmpty() ? "ambient" : "note", present, roster, true)
                : new ChatTurn(session, ChatMessage.user(prompt.toString(), images), trigger.userId(), outbox.files(target), "qq", present, roster, true);
        if (trigger != null) {
            agent.store().markReplying(new AgentStore.Replying(session, trigger.userId(), trigger.messageId(), trigger.speaker(), trigger.text(),
                    System.currentTimeMillis()));
        }
        String sent;
        try {
            sent = reply(target, turn, true);
        } finally {
            if (trigger != null) {
                agent.store().clearReplying(session);
            }
        }
        if (sent == null) {
            state.restore(lines);
            if (lines.stream().anyMatch(l -> !l.seen())) {
                heardNow(state, SEEN_MARK, "", "");
            }
        } else if (trigger != null) {
            state.repliedTo(trigger.userId());
        }
        if (!notes.isEmpty()) {
            agent.intercom().receipt(notes, place, sent, "看了看群里的情况，觉得现在不说比较好");
            if (sent != null) {
                countProactive();
            }
        } else if (sent != null) {
            String flat = abbreviate(sent.replace('\n', ' '), 80);
            agent.intercom().report(session, trigger != null
                    ? place + "：" + trigger.speaker() + " 跟你说「" + abbreviate(trigger.text().replace('\n', ' '), 50) + "」，你回：" + flat
                    : place + "：你插了一句：" + flat);
        }
    }

    private void scheduleAmbient(GroupState state, String selfId) {
        long quiet = ThreadLocalRandom.current().nextLong(AMBIENT_QUIET_MIN_MS, AMBIENT_QUIET_MAX_MS + 1);
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.ambient == null) {
                state.ambientDeadline = now + AMBIENT_MAX_WAIT_MS;
            } else {
                state.ambient.cancel(false);
            }
            long delay = Math.min(quiet, Math.max(0, state.ambientDeadline - now));
            state.ambient = scheduler.schedule(() -> ambientCheck(state, selfId), delay, TimeUnit.MILLISECONDS);
        }
    }

    private void ambientCheck(GroupState state, String selfId) {
        synchronized (state) {
            state.ambient = null;
            if (!agent.store().qqReply() || !agent.store().groupMode() || state.busy || !state.hasNewLines()) {
                return;
            }
        }
        long wait = state.calmIn();
        if (wait > 0) {
            holdBack(state, selfId, false, wait);
            return;
        }
        startGroupTurn(state, selfId, Mode.AMBIENT);
    }

    private String reply(Target target, ChatTurn turn, boolean deliver) {
        ChatResult result;
        try {
            result = agent.chat(turn, new StreamListener() {
                @Override
                public void onRetry(int attempt, long delayMs, String error) {
                    log.warn("agent [" + target.where() + "] retry " + attempt + " in " + delayMs / 1000 + "s: " + error);
                }

                @Override
                public void onToolCall(ToolCall call) {
                    log.info("agent [" + target.where() + "] tool " + call.name() + " " + abbreviate(call.arguments().replace('\n', ' '), 160));
                }

                @Override
                public void onBlocked(String category, boolean byModel, String replacement) {
                    log.warn("agent [" + target.where() + "] blocked a reply flagged by the " + (byModel ? "model review" : "keyword list")
                            + (replacement.isEmpty() ? ", stayed quiet" : ", sent a deflection instead"));
                }
            });
        } catch (AgentException e) {
            log.warn("agent [" + target.where() + "] reply " + (e.kind() == AgentException.Kind.CANCELLED ? "stopped" : "failed") + ": " + e.getMessage());
            return null;
        }
        if (result.content().isBlank()) {
            log.info("agent [" + target.where() + "] stayed quiet");
            return null;
        }
        if (!deliver) {
            log.info("agent [" + target.where() + "] console reply kept in the console");
            return result.content();
        }
        String text = QqText.format(abbreviate(result.content(), QqOutbox.MAX_REPLY_LENGTH));
        if (!outbox.hasContent(text)) {
            return null;
        }
        try {
            String words = StickerStore.withoutTags(text);
            Thread.sleep(QqText.typingPause(words.isEmpty() ? "表情" : words, result.latencyMs()));
            boolean quote = target.group() && !target.messageId().isEmpty() && !target.messageId().equals(latestInGroup.get(target.groupId()));
            outbox.deliver(target, text, quote ? target.messageId() : null);
            lastActivity = System.currentTimeMillis();
            observeSaid(target.session(), text);
        } catch (ToolFailure e) {
            log.warn("agent [" + target.where() + "] send failed: " + e.getMessage());
            agent.hear(target.session(), "（你上面那句没发到 QQ：" + abbreviate(e.getMessage(), 80) + "）");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        log.info("agent [" + target.where() + "] " + ("ambient".equals(turn.source()) ? "chimed in" : "replied") + " in "
                + String.format("%.1f", result.latencyMs() / 1000.0) + "s: " + abbreviate(text.replace('\n', ' '), 120));
        return text;
    }

    public String speak(String groupId, String text) throws ToolFailure {
        String reason = whyNot(groupId);
        if (reason != null) {
            throw new ToolFailure("现在不能去：" + reason);
        }
        String formatted = outbox.prepare(text);
        outbox.review(readGroup(groupId, 6), formatted);
        GroupState state = groups.computeIfAbsent(groupId, GroupState::new);
        if (!claim(state)) {
            throw new ToolFailure("现在不能去：正在这个群里聊着呢");
        }
        String selfId = outbox.selfId();
        try {
            outbox.deliver(Target.group(groupId, "", selfId, ""), formatted, null);
            agent.record("group:" + groupId, ChatMessage.assistant(formatted, ""));
        } finally {
            release(state, selfId);
        }
        synchronized (proactive) {
            proactive.addLast(System.currentTimeMillis());
        }
        lastActivity = System.currentTimeMillis();
        log.info("agent [group " + groupId + "] went over and said: " + abbreviate(formatted.replace('\n', ' '), 120));
        return formatted;
    }

    public String speakPrivate(String userId, String text) throws ToolFailure {
        if (!agent.store().qqReply()) {
            throw new ToolFailure("现在不能去：QQ 自动回复没开");
        }
        if (!napcat.status().connected()) {
            throw new ToolFailure("现在不能去：QQ 没连上");
        }
        if (!userId.matches("\\d{5,12}")) {
            throw new ToolFailure("QQ 号不对：" + userId);
        }
        if (!contacts.canMessage(userId)) {
            throw new ToolFailure("不能私聊 QQ " + userId + "：不是好友，也没私聊过。去你们都在的群里 @ 他吧");
        }
        String session = "private:" + userId;
        String formatted = outbox.prepare(text);
        String recent = String.join("\n", agent.session(session, 6).turns().stream().map(SessionDetail.Entry::content).toList());
        outbox.review(recent, formatted);
        outbox.deliver(Target.direct(userId, outbox.selfId(), ""), formatted, null);
        agent.record(session, ChatMessage.assistant(formatted, ""));
        lastActivity = System.currentTimeMillis();
        log.info("agent [private " + userId + "] went over and said: " + abbreviate(formatted.replace('\n', ' '), 120));
        return formatted;
    }

    public String poke(String session, String userId) throws ToolFailure {
        if (!agent.store().qqReply()) {
            throw new ToolFailure("现在不能拍：QQ 自动回复没开");
        }
        if (!napcat.status().connected()) {
            throw new ToolFailure("现在不能拍：QQ 没连上");
        }
        if (userId == null || !userId.matches("\\d{5,12}") || userId.equals(outbox.selfId())) {
            throw new ToolFailure("要拍的人不对：" + userId);
        }
        long now = System.currentTimeMillis();
        Long last = pokedAt.get(userId);
        if (last != null && now - last < POKE_GAP_MS) {
            throw new ToolFailure("刚拍过 TA，等一会儿再拍");
        }
        Target target = session.startsWith("group:") ? Target.group(session.substring(6), userId, outbox.selfId(), "")
                : Target.direct(userId, outbox.selfId(), "");
        outbox.poke(target, userId);
        pokedAt.put(userId, now);
        lastActivity = now;
        log.info("agent [" + target.where() + "] poked " + userId);
        return target.group() ? unforgeable(archive.groupSenders(target.groupId()).getOrDefault(userId, "QQ " + userId)) : "对方";
    }

    public void consoleTurn(String session, String text, boolean deliver) {
        Target target = outbox.targetOf(session);
        boolean toQq = deliver && target != null;
        ChatTurn turn = new ChatTurn(session, ChatMessage.user(CONSOLE_TAG + text), null, toQq ? outbox.files(target) : null, "console",
                target != null && !target.group() ? List.of(target.userId()) : List.of());
        String where = target == null ? session : target.where();
        log.info("agent [" + where + "] console message" + (toQq ? " (reply goes to QQ)" : "") + ": " + abbreviate(text.replace('\n', ' '), 120));
        executor.submit(() -> {
            try {
                if (target == null) {
                    agent.chat(turn, StreamListener.NONE);
                } else {
                    reply(target, turn, toQq);
                }
            } catch (AgentException e) {
                log.warn("agent [" + where + "] console turn failed: " + e.getMessage());
            } catch (RuntimeException e) {
                log.error("agent [" + where + "] console turn crashed: " + e);
            }
        });
    }

    public void say(String session, String text) throws ToolFailure {
        Target target = outbox.targetOf(session);
        if (target == null) {
            throw new IllegalArgumentException("只有 QQ 会话可以用 /say");
        }
        String formatted = outbox.prepare(text);
        outbox.deliver(target, formatted, null);
        agent.record(session, ChatMessage.assistant(formatted, ""));
        log.info("agent [" + target.where() + "] console said: " + abbreviate(formatted.replace('\n', ' '), 120));
    }

    public void sendSticker(String session, long id) throws ToolFailure {
        Target target = outbox.targetOf(session);
        if (target == null) {
            throw new IllegalArgumentException("只能发到 QQ 会话");
        }
        StickerStore.Sticker sticker = agent.stickers().store().get(id);
        if (sticker == null || sticker.file().isEmpty()) {
            throw new IllegalArgumentException("这张表情包还没下载好");
        }
        outbox.sendSticker(target, sticker);
        agent.record(session, ChatMessage.assistant("[表情包:" + id + "]", ""));
        log.info("agent [" + target.where() + "] console sent sticker #" + id);
    }

    public int syncFavorites() throws ToolFailure {
        JsonObject params = new JsonObject();
        params.addProperty("count", 500);
        JsonObject response = outbox.call("fetch_custom_face", params, Duration.ofSeconds(30));
        List<String> urls = new ArrayList<>();
        if (response.has("data") && response.get("data").isJsonArray()) {
            response.getAsJsonArray("data").forEach(e -> {
                if (e.isJsonPrimitive() && e.getAsString().startsWith("http")) {
                    urls.add(e.getAsString());
                }
            });
        }
        int added = agent.stickers().importFavorites(urls);
        favoritesSyncedAt = System.currentTimeMillis();
        log.info("stickers: synced " + urls.size() + " favorite stickers (" + added + " new)");
        return urls.size();
    }

    public void startLife() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (napcat.status().connected() && System.currentTimeMillis() - favoritesSyncedAt > FAVORITES_EVERY_MS) {
                try {
                    syncFavorites();
                } catch (ToolFailure | RuntimeException e) {
                    log.warn("stickers: syncing favorite stickers failed: " + e.getMessage());
                }
            }
        }, 2, 30, TimeUnit.MINUTES);
    }

    public String whyNot(String groupId) {
        if (!agent.store().qqReply() || !agent.store().groupMode()) {
            return "QQ 自动回复或群聊模式没开";
        }
        if (!napcat.status().connected()) {
            return "QQ 没连上";
        }
        Map<String, String> names = contacts.groups();
        if (!names.isEmpty() && !names.containsKey(groupId)) {
            return "不在这个群里";
        }
        GroupState state = groups.get(groupId);
        if (state != null && state.busy) {
            return "正在这个群里发消息，等这句发完";
        }
        return null;
    }

    public String readGroup(String groupId, int count) {
        List<String> lines = new ArrayList<>();
        for (SessionDetail.Entry entry : agent.session("group:" + groupId, count).turns()) {
            String at = Clock.stamp(entry.createdAt());
            if (entry.role().equals("user")) {
                if (!entry.content().equals(SEEN_MARK) && !Clock.isMarker(entry.content())) {
                    lines.add("[" + at + "] " + entry.content());
                }
            } else if (entry.role().equals("assistant")) {
                String said = Markup.saidOf(entry.content());
                if (!said.isBlank()) {
                    lines.add("[" + at + "] 你：" + said);
                }
            }
        }
        return lines.isEmpty() ? "（这个群还没有聊天记录）" : String.join("\n", lines);
    }

    public Map<String, String> groupNames() {
        return contacts.groups();
    }

    public String groupName(String groupId) {
        return contacts.groupName(groupId);
    }

    public String findPerson(String query) {
        return contacts.findPerson(query);
    }

    public int proactiveToday() {
        synchronized (proactive) {
            long now = System.currentTimeMillis();
            while (!proactive.isEmpty() && now - proactive.peekFirst() > 24 * 3_600_000L) {
                proactive.pollFirst();
            }
            return proactive.size();
        }
    }

    public long lastSpokeIn(String groupId) {
        GroupState state = groups.get(groupId);
        return state == null ? 0 : state.lastSpoke();
    }

    public long lastActivity() {
        return lastActivity;
    }

    public List<MessageArchive.GroupActivity> activity(long since) {
        return archive.groupActivity(since);
    }

    private String stickerLabel(String file, String url) {
        StickerStore.Sticker sticker = agent.stickers().noticed(file, url);
        return sticker == null ? null : sticker.label();
    }

    private boolean calledByName(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return agent.store().wakeWords().stream().map(String::strip).filter(w -> !w.isEmpty())
                .anyMatch(w -> lower.contains(w.toLowerCase(Locale.ROOT)));
    }

    private boolean looksLikeMe(String label) {
        String nickname = napcat.status().nickname();
        return label.contains("#") && (calledByName(label) || nickname != null && !nickname.isBlank() && label.startsWith(nickname + "#"));
    }

    private boolean reservedName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return name.contains("主人") || name.equals(napcat.status().nickname()) || calledByName(name)
                || ownerNames.stream().anyMatch(o -> o.length() >= 2 ? lower.contains(o.toLowerCase(Locale.ROOT)) : name.equals(o));
    }

    private List<ImagePart> download(Target target, List<String> imageUrls) {
        List<ImagePart> images = new ArrayList<>();
        for (String url : imageUrls.subList(0, Math.min(MAX_IMAGES, imageUrls.size()))) {
            try {
                images.add(ImageLoader.download(url));
            } catch (AgentException e) {
                log.warn("agent [" + target.where() + "] image skipped: " + e.getMessage());
            }
        }
        return images;
    }
}
