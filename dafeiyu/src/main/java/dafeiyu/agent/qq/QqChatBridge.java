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
import dafeiyu.agent.memory.Mood;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.qq.GroupState.Line;
import dafeiyu.agent.qq.QqMessages.Incoming;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.tool.FileSink;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.NapCatService;
import dafeiyu.util.Clock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

import static dafeiyu.agent.qq.QqMessages.segment;
import static dafeiyu.agent.qq.QqMessages.senderName;
import static dafeiyu.agent.qq.QqMessages.unforgeable;
import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class QqChatBridge {

    private static final int MAX_REPLY_LENGTH = 3000;
    private static final int MAX_IMAGES = 4;
    private static final long MAX_INLINE_IMAGE = 10L * 1024 * 1024;
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(3);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    private static final String OWNER_TAG = "【主人】";
    private static final String POKE_TAG = "【拍一拍】";
    private static final long POKE_GAP_MS = 20_000;
    private static final String CONSOLE_TAG = "【控制台】";
    private static final long AMBIENT_QUIET_MIN_MS = 4_000;
    private static final long AMBIENT_QUIET_MAX_MS = 12_000;
    private static final long AMBIENT_MAX_WAIT_MS = 25_000;
    private static final long AMBIENT_LOOK_INTERVAL_MS = 30_000;
    private static final long AMBIENT_COOLDOWN_MS = 20_000;
    private static final int AMBIENT_HOURLY_LIMIT = 30;
    private static final long RECENT_TALK_MS = 10 * 60_000;
    private static final String SEEN_MARK = "（以上是你刚才看过的）";
    private static final long FAVORITES_EVERY_MS = 6 * 3_600_000L;
    private static final long GROUP_NAMES_TTL_MS = 10 * 60_000;

    private enum Mode { ADDRESSED, AMBIENT }

    public interface Observer {

        void heard(String where, String who, String text, boolean toMe);

        void said(String where, String text);
    }

    private volatile Observer observer;

    public void setObserver(Observer observer) {
        this.observer = observer;
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

    private final ChatAgent agent;
    private final NapCatService napcat;
    private final MessageArchive archive;
    private final LogBuffer log;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, String> latestInGroup = new ConcurrentHashMap<>();
    private final Map<String, GroupState> groups = new ConcurrentHashMap<>();
    private final Set<String> ownerNames = ConcurrentHashMap.newKeySet();
    private final Deque<Long> proactive = new ArrayDeque<>();
    private volatile Map<String, String> groupNames = Map.of();
    private volatile long groupNamesAt;
    private final Map<String, Long> pokedAt = new ConcurrentHashMap<>();
    private volatile Map<String, String> friends = Map.of();
    private volatile long friendsAt;
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile long favoritesSyncedAt;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "group-chat");
        t.setDaemon(true);
        return t;
    });

    private record Target(String messageType, String groupId, String userId, String selfId, String messageId, String where) {

        boolean group() {
            return messageType.equals("group");
        }
    }

    public QqChatBridge(ChatAgent agent, NapCatService napcat, MessageArchive archive, LogBuffer log) {
        this.agent = agent;
        this.napcat = napcat;
        this.archive = archive;
        this.log = log;
        agent.setPoker((session, userId) -> {
            try {
                return poke(session, userId);
            } catch (ToolFailure e) {
                log.info("agent [" + session + "] poke skipped: " + e.getMessage());
                return null;
            }
        });
    }

    public void onEvent(JsonObject event) {
        if (optString(event, "post_type").equals("notice") && optString(event, "sub_type").equals("poke")) {
            onPoke(event);
            return;
        }
        if (!optString(event, "post_type").equals("message")) {
            return;
        }
        String messageType = optString(event, "message_type");
        if (messageType.equals("group")) {
            latestInGroup.put(optString(event, "group_id"), optString(event, "message_id"));
        }
        if (!agent.store().qqReply()) {
            return;
        }
        String selfId = optString(event, "self_id");
        String userId = optString(event, "user_id");
        if (userId.isEmpty() || userId.equals(selfId)) {
            return;
        }
        GroupState state = messageType.equals("group") ? groups.computeIfAbsent(optString(event, "group_id"), GroupState::new) : null;
        Incoming incoming = QqMessages.parse(event, selfId, state == null ? (qq, hint) -> hint
                : (qq, hint) -> state.mentionName(qq, hint, agent.personas().isOwner(qq), this::reservedName), this::stickerLabel);
        if (incoming.text().isEmpty() && incoming.imageUrls().isEmpty()) {
            return;
        }
        boolean owner = agent.personas().isOwner(userId);
        if (owner && !senderName(event).isBlank()) {
            ownerNames.add(senderName(event).strip());
        }
        String text = owner ? incoming.text() : unforgeable(incoming.text());
        if (state != null) {
            onGroupMessage(event, state, incoming, text, owner, selfId, userId);
        } else if (messageType.equals("private")) {
            lastActivity = System.currentTimeMillis();
            observeHeard("private:" + userId, owner ? "主人" : unforgeable(senderName(event)), text, true);
            Target target = new Target("private", "", userId, selfId, optString(event, "message_id"), "private " + userId);
            replyOnce("private:" + userId, target, (owner ? OWNER_TAG : "") + text, incoming.imageUrls(), incoming.attachments());
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
            String line = POKE_TAG + (owner ? "主人" : "对方") + " " + action + " 你" + suffix;
            lastActivity = System.currentTimeMillis();
            observeHeard("private:" + userId, owner ? "主人" : "对方", action + " 你" + suffix, true);
            Target target = new Target("private", "", userId, selfId, "", "private " + userId);
            replyOnce("private:" + userId, target, (owner ? OWNER_TAG : "") + line, List.of(), attachments.toString());
            return;
        }
        GroupState state = groups.computeIfAbsent(groupId, GroupState::new);
        Map<String, String> senders = archive.groupSenders(groupId);
        String name = state.label(unforgeable(senders.getOrDefault(userId, "QQ " + userId)), userId, owner, this::reservedName);
        String target = me ? "你" : state.label(unforgeable(senders.getOrDefault(targetId, "QQ " + targetId)), targetId,
                agent.personas().isOwner(targetId), this::reservedName);
        String line = (owner ? OWNER_TAG : "") + POKE_TAG + name + " " + action + " " + target + suffix;
        observeHeard("group:" + groupId, (owner ? "主人 " : "") + name, action + " " + target + suffix, me);
        boolean addressed = me && !state.cooling(userId) && !looksLikeMe(name);
        state.add(new Line("", userId, name, line, System.currentTimeMillis(), addressed, List.of(), false));
        hear(state, line, userId, attachments.toString());
        if (!agent.store().groupMode() || state.tooChatty()) {
            return;
        }
        if (addressed) {
            lastActivity = System.currentTimeMillis();
            startGroupTurn(state, selfId, Mode.ADDRESSED);
        } else {
            scheduleAmbient(state, selfId);
        }
    }

    private void onGroupMessage(JsonObject event, GroupState state, Incoming incoming, String text, boolean owner, String selfId, String userId) {
        String groupId = state.groupId;
        String messageId = optString(event, "message_id");
        String name = state.label(unforgeable(senderName(event)), userId, owner, this::reservedName);
        String quote = incoming.replyTo().isEmpty() ? "" : state.quote(incoming.replyTo());
        String line = (owner ? OWNER_TAG : "") + name + quote + "：" + text.strip().replaceAll("\\s*\\R+\\s*", " / ");
        state.heard(messageId, userId, name, line);
        observeHeard("group:" + groupId, (owner ? "主人 " : "") + name, text.strip().replaceAll("\\s*\\R+\\s*", " / "),
                incoming.mentionedMe() || state.isBotMessage(incoming.replyTo()));
        Target target = new Target("group", groupId, userId, selfId, messageId, "group " + groupId);

        if (!agent.store().groupMode()) {
            if (incoming.mentionedMe()) {
                replyOnce("group:" + groupId, target, line, incoming.imageUrls(), incoming.attachments());
            }
            return;
        }
        boolean addressed = (incoming.mentionedMe() || state.isBotMessage(incoming.replyTo())
                || (!incoming.mentionedOthers() && calledByName(incoming.text())))
                && !looksLikeMe(name) && !state.cooling(userId);
        state.add(new Line(messageId, userId, name, line, System.currentTimeMillis(), addressed, incoming.imageUrls(), false));
        hear(state, line, userId, incoming.attachments());
        if (state.tooChatty()) {
            log.info("agent [" + target.where() + "] said a lot just now, holding back");
            return;
        }
        if (addressed) {
            lastActivity = System.currentTimeMillis();
            startGroupTurn(state, selfId, Mode.ADDRESSED);
        } else {
            scheduleAmbient(state, selfId);
        }
    }

    private void hear(GroupState state, String line, String userId, String attachments) {
        synchronized (state) {
            if (state.busy) {
                state.held.add(new GroupState.Held(line, userId, attachments));
                return;
            }
            heardNow(state, line, userId, attachments);
        }
    }

    private void flushHeld(GroupState state) {
        state.held.forEach(h -> heardNow(state, h.line(), h.userId(), h.attachments()));
        state.held.clear();
    }

    private void heardNow(GroupState state, String line, String userId, String attachments) {
        try {
            agent.hear("group:" + state.groupId, line, userId, attachments);
        } catch (RuntimeException e) {
            log.warn("agent [group " + state.groupId + "] saving a group message failed: " + e.getMessage());
        }
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

    private void replyOnce(String session, Target target, String prompt, List<String> imageUrls, String attachments) {
        if (!busy.add(session)) {
            log.info("agent [" + target.where() + "] still replying, message skipped");
            return;
        }
        executor.submit(() -> {
            try {
                List<ImagePart> images = download(target, imageUrls);
                reply(target, new ChatTurn(session, ChatMessage.user(prompt, images), target.userId(), files(target), "qq").withAttachments(attachments), true);
            } catch (RuntimeException e) {
                log.error("agent [" + target.where() + "] reply crashed: " + e);
            } finally {
                busy.remove(session);
            }
        });
    }

    private void startGroupTurn(GroupState state, String selfId, Mode mode) {
        synchronized (state) {
            if (state.busy) {
                state.pendingAddressed |= mode == Mode.ADDRESSED;
                return;
            }
            state.busy = true;
            if (state.ambient != null) {
                state.ambient.cancel(false);
                state.ambient = null;
            }
        }
        executor.submit(() -> {
            try {
                runGroupTurn(state, selfId, mode);
            } catch (RuntimeException e) {
                log.error("agent [group " + state.groupId + "] reply crashed: " + e);
            } finally {
                boolean again;
                synchronized (state) {
                    flushHeld(state);
                    state.busy = false;
                    again = state.pendingAddressed;
                    state.pendingAddressed = false;
                }
                if (again) {
                    startGroupTurn(state, selfId, Mode.ADDRESSED);
                } else if (state.hasNewLines()) {
                    scheduleAmbient(state, selfId);
                }
            }
        });
    }

    private void runGroupTurn(GroupState state, String selfId, Mode mode) {
        List<Line> lines = state.drain();
        if (lines.isEmpty()) {
            return;
        }
        state.looked();
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
        if (trigger == null) {
            int said = state.spokenWithin(RECENT_TALK_MS);
            prompt.append(prompt.isEmpty() ? "" : "\n").append(TurnRunner.AMBIENT).append("以上是群里刚刚的聊天，没人叫你。")
                    .append(said > 0 ? "你最近十分钟已经在群里说了 " + said + " 句。" : "你最近十分钟没在群里说话。")
                    .append("想说就说一句，不想说就只写 ").append(TurnRunner.SILENCE).append("。");
        }
        String where = "group " + state.groupId;
        Target target = trigger == null
                ? new Target("group", state.groupId, "", selfId, "", where)
                : new Target("group", state.groupId, trigger.userId(), selfId, trigger.messageId(), where);
        List<ImagePart> images = trigger == null ? List.of() : download(target, trigger.imageUrls());
        List<String> people = new ArrayList<>();
        if (trigger != null) {
            people.add(trigger.userId());
        }
        lines.reversed().forEach(l -> people.add(l.userId()));
        List<String> present = people.stream().distinct().limit(6).toList();
        Map<String, String> roster = state.roster(people.stream().distinct().toList());
        String source = trigger == null ? "ambient" : "qq";
        String session = "group:" + state.groupId;
        ChatTurn turn = trigger == null
                ? new ChatTurn(session, ChatMessage.user(prompt.toString()), "0", null, source, present, roster, true)
                : new ChatTurn(session, ChatMessage.user(prompt.toString(), images), trigger.userId(), files(target), source, present, roster, true);
        String sent = reply(target, turn, true);
        if (sent == null) {
            state.restore(lines);
            if (!lines.isEmpty() && lines.stream().anyMatch(l -> !l.seen())) {
                heardNow(state, SEEN_MARK, "", "");
            }
        } else if (trigger != null) {
            state.repliedTo(trigger.userId());
        }
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

    public long lastActivity() {
        return lastActivity;
    }

    public List<MessageArchive.GroupActivity> activity(long since) {
        return archive.groupActivity(since);
    }

    public boolean connected() {
        return napcat.status().connected();
    }

    public Map<String, String> groupNames() {
        long now = System.currentTimeMillis();
        if (now - groupNamesAt > GROUP_NAMES_TTL_MS && napcat.status().connected()) {
            groupNamesAt = now;
            try {
                JsonObject response = call("get_group_list", new JsonObject(), Duration.ofSeconds(10));
                Map<String, String> names = new LinkedHashMap<>();
                if (response.has("data") && response.get("data").isJsonArray()) {
                    response.getAsJsonArray("data").forEach(e -> {
                        if (e.isJsonObject()) {
                            names.put(optString(e.getAsJsonObject(), "group_id"), optString(e.getAsJsonObject(), "group_name"));
                        }
                    });
                }
                groupNames = Map.copyOf(names);
            } catch (ToolFailure | RuntimeException e) {
                log.warn("agent: reading the group list failed: " + e.getMessage());
            }
        }
        return groupNames;
    }

    public String groupName(String groupId) {
        String name = groupNames().get(groupId);
        return name == null || name.isBlank() ? "群 " + groupId : name;
    }

    public Map<String, String> friends() {
        long now = System.currentTimeMillis();
        if (now - friendsAt > GROUP_NAMES_TTL_MS && napcat.status().connected()) {
            friendsAt = now;
            try {
                JsonObject response = call("get_friend_list", new JsonObject(), Duration.ofSeconds(10));
                Map<String, String> names = new LinkedHashMap<>();
                if (response.has("data") && response.get("data").isJsonArray()) {
                    response.getAsJsonArray("data").forEach(e -> {
                        if (e.isJsonObject()) {
                            JsonObject f = e.getAsJsonObject();
                            String remark = optString(f, "remark");
                            names.put(optString(f, "user_id"), unforgeable(remark.isBlank() ? optString(f, "nickname") : remark));
                        }
                    });
                }
                friends = Map.copyOf(names);
            } catch (ToolFailure | RuntimeException e) {
                log.warn("agent: reading the friend list failed: " + e.getMessage());
            }
        }
        return friends;
    }

    private boolean canMessage(String userId) {
        return friends().containsKey(userId) || agent.session("private:" + userId, 0).totalTurns() > 0;
    }

    public String findPerson(String query) {
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

    public String poke(String session, String userId) throws ToolFailure {
        if (!agent.store().qqReply()) {
            throw new ToolFailure("现在不能拍：QQ 自动回复没开");
        }
        if (!napcat.status().connected()) {
            throw new ToolFailure("现在不能拍：QQ 没连上");
        }
        if (userId == null || !userId.matches("\\d{5,12}") || userId.equals(String.valueOf(napcat.status().selfId()))) {
            throw new ToolFailure("要拍的人不对：" + userId);
        }
        long now = System.currentTimeMillis();
        Long last = pokedAt.get(userId);
        if (last != null && now - last < POKE_GAP_MS) {
            throw new ToolFailure("刚拍过 TA，等一会儿再拍");
        }
        boolean group = session.startsWith("group:");
        String groupId = group ? session.substring(6) : "";
        JsonObject params = new JsonObject();
        params.addProperty("user_id", Long.parseLong(userId));
        if (group) {
            params.addProperty("group_id", Long.parseLong(groupId));
        }
        try {
            call(group ? "group_poke" : "friend_poke", params, Duration.ofSeconds(10));
        } catch (ToolFailure e) {
            call("send_poke", params, Duration.ofSeconds(10));
        }
        pokedAt.put(userId, now);
        lastActivity = now;
        String name = group ? archive.groupSenders(groupId).getOrDefault(userId, "QQ " + userId) : "对方";
        log.info("agent [" + (group ? "group " + groupId : "private " + userId) + "] poked " + userId);
        return unforgeable(name);
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
        if (!canMessage(userId)) {
            throw new ToolFailure("不能私聊 QQ " + userId + "：不是好友，也没私聊过。去你们都在的群里 @ 他吧");
        }
        String formatted = QqFaces.limit(QqText.format(abbreviate(text, MAX_REPLY_LENGTH)), false);
        StickerStore store = agent.stickers().store();
        formatted = StickerStore.limit(formatted, false, id -> {
            StickerStore.Sticker s = store.get(id);
            return s != null && s.usable();
        });
        String session = "private:" + userId;
        String recent = agent.session(session, 6).turns().stream().map(SessionDetail.Entry::content).collect(java.util.stream.Collectors.joining("\n"));
        ContentGuard.Verdict verdict = agent.guard().review(agent.store().active(), recent, formatted);
        if (verdict != null) {
            throw new ToolFailure("这句话不能发（涉及" + verdict.category().label() + "），换个说法或者别说了");
        }
        String words = StickerStore.withoutTags(formatted);
        List<StickerStore.Sticker> stickers = StickerStore.tags(formatted).stream().limit(1).map(store::get).filter(s -> s != null && !s.file().isEmpty()).toList();
        if (words.isEmpty() && stickers.isEmpty()) {
            throw new ToolFailure("没有要发的内容");
        }
        Target target = new Target("private", "", userId, String.valueOf(napcat.status().selfId()), "", "private " + userId);
        if (!words.isEmpty()) {
            send(target, content(target, words), words);
        }
        for (StickerStore.Sticker sticker : stickers) {
            sendSticker(target, sticker);
        }
        agent.record(session, ChatMessage.assistant(formatted, ""));
        lastActivity = System.currentTimeMillis();
        log.info("agent [private " + userId + "] went over and said: " + abbreviate(formatted.replace('\n', ' '), 120));
        return formatted;
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

    public String whyNot(String groupId) {
        if (!agent.store().qqReply() || !agent.store().groupMode()) {
            return "QQ 自动回复或群聊模式没开";
        }
        if (!napcat.status().connected()) {
            return "QQ 没连上";
        }
        if (!groupNames().isEmpty() && !groupNames().containsKey(groupId)) {
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
                String said = Mood.split(entry.content()).said();
                if (!said.isBlank()) {
                    lines.add("[" + at + "] 你：" + said);
                }
            }
        }
        return lines.isEmpty() ? "（这个群还没有聊天记录）" : String.join("\n", lines);
    }

    public String speak(String groupId, String text) throws ToolFailure {
        String reason = whyNot(groupId);
        if (reason != null) {
            throw new ToolFailure("现在不能去：" + reason);
        }
        GroupState state = groups.computeIfAbsent(groupId, GroupState::new);
        String formatted = QqFaces.limit(QqText.format(abbreviate(text, MAX_REPLY_LENGTH)), false);
        StickerStore store = agent.stickers().store();
        formatted = StickerStore.limit(formatted, false, id -> {
            StickerStore.Sticker s = store.get(id);
            return s != null && s.usable();
        });
        ContentGuard.Verdict verdict = agent.guard().review(agent.store().active(), readGroup(groupId, 6), formatted);
        if (verdict != null) {
            throw new ToolFailure("这句话不能发（涉及" + verdict.category().label() + "），换个说法或者别说了");
        }
        String words = StickerStore.withoutTags(formatted);
        List<StickerStore.Sticker> stickers = StickerStore.tags(formatted).stream().limit(1).map(store::get).filter(s -> s != null && !s.file().isEmpty()).toList();
        if (words.isEmpty() && stickers.isEmpty()) {
            throw new ToolFailure("没有要发的内容");
        }
        Target target = new Target("group", groupId, "", String.valueOf(napcat.status().selfId()), "", "group " + groupId);
        synchronized (state) {
            if (state.busy) {
                throw new ToolFailure("现在不能去：正在这个群里聊着呢");
            }
            state.busy = true;
        }
        try {
            if (!words.isEmpty()) {
                send(target, content(target, words), words);
            }
            for (StickerStore.Sticker sticker : stickers) {
                sendSticker(target, sticker);
            }
            agent.record("group:" + groupId, ChatMessage.assistant(formatted, ""));
        } finally {
            synchronized (state) {
                flushHeld(state);
                state.busy = false;
            }
        }
        synchronized (proactive) {
            proactive.addLast(System.currentTimeMillis());
        }
        lastActivity = System.currentTimeMillis();
        log.info("agent [group " + groupId + "] went over and said: " + abbreviate(formatted.replace('\n', ' '), 120));
        return formatted;
    }

    public int syncFavorites() throws ToolFailure {
        JsonObject params = new JsonObject();
        params.addProperty("count", 500);
        JsonObject response = call("fetch_custom_face", params, Duration.ofSeconds(30));
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

    public void sendSticker(String session, long id) throws ToolFailure {
        Target target = targetOf(session);
        if (target == null) {
            throw new IllegalArgumentException("只能发到 QQ 会话");
        }
        StickerStore.Sticker sticker = agent.stickers().store().get(id);
        if (sticker == null || sticker.file().isEmpty()) {
            throw new IllegalArgumentException("这张表情包还没下载好");
        }
        sendSticker(target, sticker);
        agent.record(session, ChatMessage.assistant("[表情包:" + id + "]", ""));
        log.info("agent [" + target.where() + "] console sent sticker #" + id);
    }

    private void sendSticker(Target target, StickerStore.Sticker sticker) throws ToolFailure {
        Path file = agent.stickers().store().path(sticker);
        JsonObject data = new JsonObject();
        data.addProperty("file", "base64://" + Base64.getEncoder().encodeToString(read(file, sticker.file())));
        data.addProperty("sub_type", 1);
        data.addProperty("summary", "[动画表情]");
        JsonObject segment = new JsonObject();
        segment.addProperty("type", "image");
        segment.add("data", data);
        JsonArray message = new JsonArray();
        message.add(segment);
        send(target, message, "[表情包#" + sticker.id() + "]");
        agent.stickers().store().sent(sticker.id());
    }

    private void scheduleAmbient(GroupState state, String selfId) {
        long quiet = ThreadLocalRandom.current().nextLong(AMBIENT_QUIET_MIN_MS, AMBIENT_QUIET_MAX_MS + 1);
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.ambient == null) {
                state.ambientDeadline = now + state.lookDelay(AMBIENT_MAX_WAIT_MS, AMBIENT_LOOK_INTERVAL_MS);
            } else {
                state.ambient.cancel(false);
            }
            long delay = Math.min(state.lookDelay(quiet, AMBIENT_LOOK_INTERVAL_MS), Math.max(0, state.ambientDeadline - now));
            state.ambient = scheduler.schedule(() -> ambientCheck(state, selfId), delay, TimeUnit.MILLISECONDS);
        }
    }

    private void ambientCheck(GroupState state, String selfId) {
        synchronized (state) {
            state.ambient = null;
            if (!agent.store().qqReply() || !agent.store().groupMode() || state.busy || !state.hasNewLines()
                    || state.tooChatty() || !state.mayChimeIn(AMBIENT_COOLDOWN_MS, AMBIENT_HOURLY_LIMIT)) {
                return;
            }
        }
        startGroupTurn(state, selfId, Mode.AMBIENT);
    }

    public void consoleTurn(String session, String text, boolean deliver) {
        Target target = targetOf(session);
        boolean toQq = deliver && target != null;
        ChatTurn turn = new ChatTurn(session, ChatMessage.user(CONSOLE_TAG + text), null, toQq ? files(target) : null, "console",
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
        Target target = targetOf(session);
        if (target == null) {
            throw new IllegalArgumentException("只有 QQ 会话可以用 /say");
        }
        String clean = QqText.format(text);
        send(target, content(target, clean), clean);
        agent.record(session, ChatMessage.assistant(clean, ""));
        log.info("agent [" + target.where() + "] console said: " + abbreviate(clean.replace('\n', ' '), 120));
    }

    private Target targetOf(String session) {
        String selfId = String.valueOf(napcat.status().selfId());
        if (session.startsWith("group:")) {
            String id = session.substring(6);
            return new Target("group", id, "", selfId, "", "group " + id);
        }
        if (session.startsWith("private:")) {
            String id = session.substring(8);
            return new Target("private", "", id, selfId, "", "private " + id);
        }
        return null;
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
        String text = QqText.format(abbreviate(result.content(), MAX_REPLY_LENGTH));
        String words = StickerStore.withoutTags(text);
        List<StickerStore.Sticker> stickers = StickerStore.tags(text).stream().limit(1)
                .map(id -> agent.stickers().store().get(id)).filter(s -> s != null && !s.file().isEmpty()).toList();
        if (words.isEmpty() && stickers.isEmpty()) {
            return null;
        }
        try {
            Thread.sleep(QqText.typingPause(words.isEmpty() ? "表情" : words, result.latencyMs()));
            if (!words.isEmpty()) {
                JsonArray message = new JsonArray();
                if (target.group() && !target.messageId().isEmpty() && !target.messageId().equals(latestInGroup.get(target.groupId()))) {
                    message.add(segment("reply", "id", target.messageId()));
                }
                message.addAll(content(target, words));
                send(target, message, words);
            }
            for (StickerStore.Sticker sticker : stickers) {
                sendSticker(target, sticker);
            }
            lastActivity = System.currentTimeMillis();
            observeSaid(target.group() ? "group:" + target.groupId() : "private:" + target.userId(), text);
        } catch (ToolFailure e) {
            log.warn("agent [" + target.where() + "] send failed: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        log.info("agent [" + target.where() + "] " + ("ambient".equals(turn.source()) || "idle".equals(turn.source()) ? "chimed in" : "replied") + " in "
                + String.format("%.1f", result.latencyMs() / 1000.0) + "s: " + abbreviate(text.replace('\n', ' '), 120));
        return text;
    }

    private JsonArray content(Target target, String text) {
        GroupState state = target.group() ? groups.get(target.groupId()) : null;
        return state == null ? QqFaces.segments(QqMessages.withoutTails(text)) : QqMessages.content(text, state.speakers());
    }

    private FileSink files(Target target) {
        return (file, name) -> {
            String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                throw new ToolFailure("Unable to read " + name + ": " + e.getMessage());
            }
            if (IMAGE_EXTENSIONS.contains(extension) && size <= MAX_INLINE_IMAGE) {
                JsonArray message = new JsonArray();
                message.add(segment("image", "file", "base64://" + Base64.getEncoder().encodeToString(read(file, name))));
                send(target, message, "[图片] " + name);
                log.info("agent [" + target.where() + "] sent image " + name);
                return "Sent image " + name + " to the user.";
            }
            JsonObject params = destination(target);
            params.addProperty("file", file.toAbsolutePath().toString());
            params.addProperty("name", name);
            call(target.group() ? "upload_group_file" : "upload_private_file", params, UPLOAD_TIMEOUT);
            archiveOutgoing(target, null, "[文件] " + name);
            log.info("agent [" + target.where() + "] sent file " + name + " (" + size / 1024 + " KB)");
            return "Sent file " + name + " to the user.";
        };
    }

    private static JsonObject destination(Target target) {
        JsonObject params = new JsonObject();
        String key = target.group() ? "group_id" : "user_id";
        String id = target.group() ? target.groupId() : target.userId();
        try {
            params.addProperty(key, Long.parseLong(id));
        } catch (NumberFormatException e) {
            params.addProperty(key, id);
        }
        return params;
    }

    private void send(Target target, JsonArray message, String archiveText) throws ToolFailure {
        JsonObject params = destination(target);
        params.add("message", message);
        JsonObject data = optObject(call(target.group() ? "send_group_msg" : "send_private_msg", params, Duration.ofSeconds(15)), "data");
        String messageId = data == null ? null : optString(data, "message_id");
        archiveOutgoing(target, messageId, archiveText);
        if (target.group()) {
            groups.computeIfAbsent(target.groupId(), GroupState::new).spoke(messageId, archiveText);
        }
    }

    private JsonObject call(String action, JsonObject params, Duration timeout) throws ToolFailure {
        JsonObject response;
        try {
            response = napcat.callAction(action, params, timeout).get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        } catch (Exception e) {
            throw new ToolFailure(e.getCause() != null ? String.valueOf(e.getCause().getMessage()) : e.toString());
        }
        if (!optString(response, "status").equals("ok")) {
            String reason = optString(response, "wording").isEmpty() ? optString(response, "message") : optString(response, "wording");
            throw new ToolFailure(reason.isEmpty() ? "QQ rejected the request" : reason);
        }
        return response;
    }

    private void archiveOutgoing(Target target, String messageId, String text) {
        String nickname = napcat.status().nickname();
        try {
            archive.recordOutgoing(target.messageType(), target.groupId(), target.userId(), target.selfId(),
                    nickname == null ? "大肥鱼" : nickname, messageId, text);
        } catch (RuntimeException e) {
            log.warn("agent: saving reply to database failed: " + e.getMessage());
        }
    }

    private static byte[] read(Path file, String name) throws ToolFailure {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ToolFailure("Unable to read " + name + ": " + e.getMessage());
        }
    }
}
