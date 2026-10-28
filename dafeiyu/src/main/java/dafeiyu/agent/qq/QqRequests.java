package dafeiyu.agent.qq;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.tool.Args;
import dafeiyu.agent.tool.Tool;
import dafeiyu.agent.tool.ToolContext;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;
import dafeiyu.log.LogBuffer;
import dafeiyu.util.Clock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static dafeiyu.agent.qq.QqMessages.displayName;
import static dafeiyu.agent.qq.QqMessages.unforgeable;
import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class QqRequests {

    public static final String FRIEND = "friend";
    public static final String DOUBT = "doubt";
    public static final String INVITE = "invite";
    public static final String JOIN = "add";

    public record Request(String kind, String flag, String userId, String nickname, String groupId, String groupName, String comment,
                          long at) {

        String key() {
            return switch (kind) {
                case INVITE -> INVITE + ":" + groupId;
                case JOIN -> JOIN + ":" + groupId + ":" + userId;
                default -> FRIEND + ":" + userId;
            };
        }

        boolean friend() {
            return kind.equals(FRIEND) || kind.equals(DOUBT);
        }
    }

    private static final String KEY = "agent.qq.requests";
    private static final String IGNORED_KEY = "agent.qq.requestsIgnored";
    private static final long TTL_MS = 3 * 86_400_000L;
    private static final int MAX_LISTED = 10;
    private static final int MAX_IGNORED = 200;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration BACKFILL_TIMEOUT = Duration.ofSeconds(60);
    private static final long HANDOFF_MS = 30 * 60_000L;
    private static final Gson GSON = new Gson();

    private record Handoff(String session, long at) {}

    private final ChatAgent agent;
    private final QqOutbox outbox;
    private final QqContacts contacts;
    private final QqActions actions;
    private final Settings settings;
    private final LogBuffer log;
    private final Map<String, Request> pending = new LinkedHashMap<>();
    private final Set<String> ignored = new LinkedHashSet<>();
    private final Map<String, Long> added = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Handoff> handoffs = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Consumer<Request> onNew = r -> {};

    QqRequests(ChatAgent agent, Database db, QqOutbox outbox, QqContacts contacts, QqActions actions, LogBuffer log) {
        this.agent = agent;
        this.outbox = outbox;
        this.contacts = contacts;
        this.actions = actions;
        this.settings = new Settings(db);
        this.log = log;
        load();
    }

    public List<Tool> tools() {
        return List.of(
                Tool.text("requests", "看看有哪些申请等你处理：谁加你好友、谁邀请你进群、谁申请加入你当管理员的群，验证消息写了什么、你们在哪见过。", """
                        {"type": "object", "properties": {}}""", (args, context) -> {
                    String list = describe(context.session());
                    return list.isEmpty() ? "现在没有等你处理的申请" : list;
                }),
                Tool.text("answer_friend", "同意或拒绝好友申请。主人让你同意或拒绝就照做；其他的你自己决定：认识的、聊得来的、正常说明来意的可以加；"
                        + "广告、来路不明、验证消息奇怪的就拒绝。", """
                        {"type": "object", "properties": {
                          "user_id": {"type": "string", "description": "申请人的 QQ 号"},
                          "approve": {"type": "boolean", "description": "true 同意，false 拒绝"},
                          "remark": {"type": "string", "description": "同意时给他的备注，可以不写"}
                        }, "required": ["user_id", "approve"]}""",
                        (args, context) -> answerFriend(args.qq("user_id"), approve(args), args.text("remark"))),
                Tool.text("answer_group", "同意或拒绝进群的事：别人邀请你进某个群，或者有人申请加入你当管理员的群。主人邀请的、主人让你同意的就同意；"
                        + "其他的你自己决定：好友、私聊过、在群里见过的人拉你进群，一般就去，在私聊里跟他说好了的就照说好的办；"
                        + "来路不明的、广告群、验证消息奇怪的才拒绝。", """
                        {"type": "object", "properties": {
                          "group_id": {"type": "string", "description": "群号"},
                          "user_id": {"type": "string", "description": "申请进群的人的 QQ 号；别人邀请你进群时不用写"},
                          "approve": {"type": "boolean", "description": "true 同意，false 拒绝"},
                          "reason": {"type": "string", "description": "拒绝时告诉对方的理由，可以不写"}
                        }, "required": ["group_id", "approve"]}""",
                        (args, context) -> answerGroup(args.group("group_id"), args.optQq("user_id"), approve(args), args.text("reason"), context)));
    }

    List<Tool> ownerTools() {
        return pending().isEmpty() ? List.of() : tools();
    }

    private synchronized void load() {
        try {
            Request[] saved = GSON.fromJson(settings.get(KEY), Request[].class);
            for (Request r : saved == null ? new Request[0] : saved) {
                if (r != null && r.kind() != null && r.flag() != null && r.userId() != null && r.groupId() != null
                        && r.nickname() != null && r.groupName() != null && r.comment() != null) {
                    pending.put(r.key(), new Request(r.kind(), r.flag(), r.userId(), r.nickname(), r.groupId(), r.groupName(), comment(r.comment()), r.at()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("agent: saved requests unreadable: " + e.getMessage());
        }
        try {
            String[] skip = GSON.fromJson(settings.get(IGNORED_KEY), String[].class);
            ignored.addAll(List.of(skip == null ? new String[0] : skip));
        } catch (RuntimeException e) {
            log.warn("agent: ignored requests unreadable: " + e.getMessage());
        }
    }

    private void save() {
        settings.put(KEY, GSON.toJson(pending.values()));
    }

    void setOnNew(Consumer<Request> onNew) {
        this.onNew = onNew == null ? r -> {} : onNew;
    }

    public synchronized List<Request> pending() {
        long now = System.currentTimeMillis();
        if (pending.values().removeIf(r -> now - r.at() > TTL_MS)) {
            save();
        }
        return List.copyOf(pending.values());
    }

    List<Request> pendingFrom(String userId) {
        return pending().stream().filter(r -> !r.friend() && r.userId().equals(userId)).toList();
    }

    void handOff(Request r, String session) {
        handoffs.put(r.key(), new Handoff(session, System.currentTimeMillis()));
    }

    private boolean heldElsewhere(Request r, String session) {
        Handoff h = handoffs.get(r.key());
        return h != null && !h.session().equals(session) && System.currentTimeMillis() - h.at() < HANDOFF_MS;
    }

    void received(JsonObject event) {
        String type = optString(event, "request_type");
        String sub = optString(event, "sub_type");
        String kind = type.equals("friend") ? FRIEND : !type.equals("group") ? null
                : sub.equals(INVITE) ? INVITE : sub.equals(JOIN) ? JOIN : null;
        String userId = optString(event, "user_id");
        String flag = optString(event, "flag");
        String groupId = kind == null || kind.equals(FRIEND) ? "" : optString(event, "group_id");
        if (kind == null || !userId.matches("\\d{5,12}") || flag.isBlank() || !kind.equals(FRIEND) && !groupId.matches("\\d{5,12}")) {
            return;
        }
        add(new Request(kind, flag, userId, nickname(userId), groupId, groupId.isEmpty() ? "" : groupName(groupId),
                comment(optString(event, "comment")), System.currentTimeMillis()));
    }

    void backfill() {
        long now = System.currentTimeMillis();
        try {
            JsonObject params = new JsonObject();
            params.addProperty("count", 20);
            JsonElement data = outbox.call("get_group_system_msg", params, BACKFILL_TIMEOUT).get("data");
            if (data != null && data.isJsonObject()) {
                fromSystem(data.getAsJsonObject().get("invited_requests"), INVITE, now);
                fromSystem(data.getAsJsonObject().get("join_requests"), JOIN, now);
            } else {
                fromSystem(data, null, now);
            }
        } catch (ToolFailure | RuntimeException e) {
            log.warn("agent: could not fetch group requests: " + e.getMessage());
        }
        try {
            doubtful(now);
        } catch (ToolFailure | RuntimeException e) {
            log.warn("agent: could not fetch flagged friend requests: " + e.getMessage());
        }
    }

    private void doubtful(long now) throws ToolFailure {
        JsonObject params = new JsonObject();
        params.addProperty("count", 20);
        JsonElement data = outbox.call("get_doubt_friends_add_request", params, BACKFILL_TIMEOUT).get("data");
        Set<String> listed = new HashSet<>();
        for (JsonObject o : objects(data)) {
            String flag = first(o, "flag", "uid");
            String userId = first(o, "uin", "user_id");
            long at = time(first(o, "time", "reqTime"));
            listed.add(flag);
            if (!userId.matches("\\d{5,12}") || flag.isBlank() || ignored(flag) || at <= 0 || now - at > TTL_MS) {
                continue;
            }
            String source = optString(o, "source").strip();
            String why = optString(o, "reason").strip();
            add(new Request(DOUBT, flag, userId, displayName(optString(o, "nick")), "", "",
                    comment(optString(o, "msg") + (source.isEmpty() ? "" : "（来源：" + source + "）") + (why.isEmpty() ? "" : "（QQ 提示：" + why + "）")),
                    at));
        }
        synchronized (this) {
            if (pending.values().removeIf(r -> r.kind().equals(DOUBT) && !listed.contains(r.flag()))) {
                save();
            }
        }
    }

    private synchronized boolean ignored(String flag) {
        return ignored.contains(flag);
    }

    private synchronized void ignore(String flag) {
        ignored.add(flag);
        while (ignored.size() > MAX_IGNORED) {
            ignored.remove(ignored.iterator().next());
        }
        settings.put(IGNORED_KEY, GSON.toJson(ignored));
    }

    private void fromSystem(JsonElement list, String kind, long now) {
        String self = outbox.selfId();
        for (JsonObject o : objects(list)) {
            String type = kind != null ? kind : optString(o, "requester_uin").equals(self) ? INVITE : JOIN;
            String id = optString(o, "request_id");
            String flag = first(o, "flag", "request_id");
            long at = time(id);
            String groupId = optString(o, "group_id");
            boolean checked = o.has("checked") && o.get("checked").isJsonPrimitive() && o.get("checked").getAsBoolean();
            if (checked) {
                synchronized (this) {
                    if (pending.values().removeIf(r -> r.kind().equals(type) && (r.flag().equals(flag) || r.flag().equals(id)))) {
                        save();
                    }
                }
                continue;
            }
            if (at <= 0 || now - at > TTL_MS || !groupId.matches("\\d{5,12}")) {
                continue;
            }
            String userId;
            String nick;
            String via = "";
            if (kind == null) {
                userId = optString(o, type.equals(INVITE) ? "invitor_uin" : "requester_uin");
                nick = displayName(optString(o, type.equals(INVITE) ? "invitor_nick" : "requester_nick"));
                String invitor = optString(o, "invitor_uin");
                if (type.equals(JOIN) && invitor.matches("\\d{5,12}") && !invitor.equals(userId)) {
                    String name = displayName(optString(o, "invitor_nick"));
                    via = "（是群里的" + (name.isEmpty() ? "QQ " + invitor : name + "（QQ " + invitor + "）") + "邀请来的）";
                }
            } else {
                String actor = optString(o, "actor");
                userId = type.equals(INVITE) && actor.matches("\\d{5,12}") && !actor.equals(self) ? actor : optString(o, "invitor_uin");
                nick = type.equals(JOIN) ? displayName(optString(o, "requester_nick")) : "";
            }
            if (!userId.matches("\\d{5,12}") || userId.equals(self)) {
                continue;
            }
            String group = displayName(optString(o, "group_name"));
            add(new Request(type, flag, userId, nick.isEmpty() ? nickname(userId) : nick, groupId, group.isEmpty() ? groupName(groupId) : group,
                    comment(optString(o, "message") + via), at));
        }
    }

    private static String first(JsonObject o, String... keys) {
        for (String key : keys) {
            String value = optString(o, key).strip();
            if (!value.isEmpty() && !value.equals("0")) {
                return value;
            }
        }
        return "";
    }

    private static List<JsonObject> objects(JsonElement list) {
        List<JsonObject> out = new ArrayList<>();
        if (list != null && list.isJsonArray()) {
            for (JsonElement e : list.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static long time(String raw) {
        long value;
        try {
            value = Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
        return value > 100_000_000_000_000L ? value / 1000 : value > 100_000_000_000L ? value : value > 1_000_000_000L ? value * 1000 : 0;
    }

    private void add(Request r) {
        synchronized (this) {
            Request old = pending.get(r.key());
            if (old != null && old.flag().equals(r.flag())) {
                return;
            }
            pending.put(r.key(), r);
            save();
        }
        log.info("agent: " + r.kind() + " request from " + r.userId() + (r.groupId().isEmpty() ? "" : " for group " + r.groupId()));
        try {
            onNew.accept(r);
        } catch (RuntimeException ignored) {
        }
    }

    private synchronized void drop(Request r) {
        if (pending.remove(r.key()) != null) {
            save();
        }
    }

    void friendAdded(String userId) {
        added.put(userId, System.currentTimeMillis());
        dropKey(FRIEND + ":" + userId);
    }

    void friendAsked(String userId) {
        Long at = added.get(userId);
        if (!userId.matches("\\d{5,12}") || at != null && System.currentTimeMillis() - at < 60_000 || contacts.friends().containsKey(userId)) {
            return;
        }
        add(new Request(FRIEND, userId, userId, nickname(userId), "", "", "", System.currentTimeMillis()));
    }

    void joined(String groupId, String userId) {
        dropKey(userId.equals(outbox.selfId()) ? INVITE + ":" + groupId : JOIN + ":" + groupId + ":" + userId);
    }

    private synchronized void dropKey(String key) {
        if (pending.remove(key) != null) {
            save();
        }
    }

    public String describe(String session) {
        List<Request> list = pending();
        if (list.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Request r : list.subList(Math.max(0, list.size() - MAX_LISTED), list.size())) {
            lines.add("- " + line(r) + (heldElsewhere(r, session) ? "（他正在私聊里跟你说这事，交给私聊那边答复，你别替那边做决定）" : ""));
        }
        if (list.size() > MAX_LISTED) {
            lines.addFirst("（一共 " + list.size() + " 个，这里是最近的 " + MAX_LISTED + " 个）");
        }
        return String.join("\n", lines);
    }

    String line(Request r) {
        String who = (agent.personas().isOwner(r.userId()) ? "【主人】" : "")
                + (r.nickname().isEmpty() ? "QQ " + r.userId() : r.nickname() + "（QQ " + r.userId() + "）");
        String words = r.comment().isEmpty() ? "（没写）" : r.comment();
        String when = Clock.ago(r.at());
        return switch (r.kind()) {
            case INVITE -> who + "邀请你进" + group(r) + "，" + when + "，附言：" + words
                    + (contacts.friends().containsKey(r.userId()) ? "；是你的好友" : "") + seenIn(r.userId());
            case JOIN -> who + "申请加入" + group(r) + "（你是管理员），" + when + "，验证消息：" + words;
            default -> who + "想加你好友" + (r.kind().equals(DOUBT) ? "（QQ 提示这个申请可能有风险）" : "") + "，" + when + "，验证消息：" + words
                    + seenIn(r.userId());
        };
    }

    private static String group(Request r) {
        return (r.groupName().isEmpty() ? "群" : "「" + r.groupName() + "」") + "（群号 " + r.groupId() + "）";
    }

    private String seenIn(String userId) {
        String group = actions.sharedGroup(userId);
        return group == null ? "；你们没在同一个群里见过" : "；在「" + contacts.groupName(group) + "」里见过";
    }

    public String answerFriend(String userId, boolean approve, String remark) throws ToolFailure {
        Request r = pending().stream().filter(x -> x.friend() && x.userId().equals(userId)).findFirst().orElse(null);
        if (r == null) {
            throw new ToolFailure("QQ " + userId + " 没有在加你好友（或者申请已经过期了）");
        }
        String name = r.nickname().isEmpty() ? "QQ " + userId : r.nickname();
        JsonObject params = new JsonObject();
        params.addProperty("flag", r.flag());
        params.addProperty("approve", approve);
        if (r.kind().equals(DOUBT)) {
            try {
                call("set_doubt_friends_add_request", params, r);
            } catch (ToolFailure e) {
                if (approve || String.valueOf(e.getMessage()).contains("已经不在了")) {
                    throw e;
                }
                drop(r);
                ignore(r.flag());
                log.info("agent: flagged friend request from " + userId + " ignored (" + e.getMessage() + ")");
                return "没理" + name + "的好友申请（这个有风险提示的申请拒绝不了，不理就行）";
            }
        } else {
            call("set_friend_add_request", params, r);
        }
        drop(r);
        contacts.forgetFriends();
        if (approve && remark != null && !remark.isBlank()) {
            JsonObject note = new JsonObject();
            note.addProperty("user_id", Long.parseLong(userId));
            note.addProperty("remark", abbreviate(remark.strip(), 30));
            try {
                outbox.call("set_friend_remark", note, TIMEOUT);
            } catch (ToolFailure e) {
                log.warn("agent: friend remark for " + userId + " not set: " + e.getMessage());
            }
        }
        if (approve) {
            outbox.forgetTempGroup(userId);
        }
        log.info("agent: friend request from " + userId + (approve ? " approved" : " rejected"));
        return approve ? "通过了" + name + "的好友申请，现在可以私聊了" : "拒绝了" + name + "的好友申请";
    }

    public String answerGroup(String groupId, String userId, boolean approve, String reason, ToolContext context) throws ToolFailure {
        List<Request> matches = pending().stream().filter(r -> !r.friend() && r.groupId().equals(groupId))
                .filter(r -> userId.isEmpty() || r.kind().equals(INVITE) || r.userId().equals(userId)).toList();
        if (matches.isEmpty()) {
            throw new ToolFailure("群 " + groupId + " 没有等你处理的邀请或进群申请（或者已经过期了）");
        }
        if (matches.size() > 1) {
            throw new ToolFailure("这个群有好几个人在申请，写上 user_id：" + String.join("、",
                    matches.stream().map(r -> (r.nickname().isEmpty() ? "" : r.nickname()) + "（QQ " + r.userId() + "）").toList()));
        }
        Request r = matches.getFirst();
        boolean owner = context.userId() != null && agent.personas().isOwner(context.userId());
        if (!owner && heldElsewhere(r, context.session())) {
            throw new ToolFailure((r.nickname().isEmpty() ? "QQ " + r.userId() : r.nickname()) + " 正在私聊里跟你说这件事，交给私聊那边答复；"
                    + "那边半小时都没答复的话你再处理");
        }
        JsonObject params = new JsonObject();
        params.addProperty("flag", r.flag());
        params.addProperty("approve", approve);
        if (!approve && reason != null && !reason.isBlank()) {
            params.addProperty("reason", abbreviate(reason.strip(), 50));
        }
        call("set_group_add_request", params, r);
        drop(r);
        String name = r.nickname().isEmpty() ? "QQ " + r.userId() : r.nickname();
        log.info("agent: " + r.kind() + " request for group " + groupId + " from " + r.userId() + (approve ? " approved" : " rejected"));
        if (r.kind().equals(INVITE)) {
            contacts.forgetGroups();
            return approve ? "同意了" + name + "的邀请，进" + group(r) + "；那个群要是还得管理员审核，等他们通过就进去了"
                    : "拒绝了" + name + "邀请你进" + group(r);
        }
        if (approve) {
            actions.note(groupId, "（你同意了" + name + "进群）");
        }
        return approve ? "同意了" + name + "加入" + group(r) : "拒绝了" + name + "加入" + group(r);
    }

    private void call(String action, JsonObject params, Request r) throws ToolFailure {
        try {
            outbox.call(action, params, TIMEOUT);
        } catch (ToolFailure e) {
            if (String.valueOf(e.getMessage()).matches("(?is).*(No such request|request.*not found).*")) {
                drop(r);
                throw new ToolFailure("这个申请已经不在了：可能在手机上处理过，或者已经过期了");
            }
            throw e;
        }
    }

    private static boolean approve(Args args) throws ToolFailure {
        JsonElement value = args.get("approve");
        if (value == null || !value.isJsonPrimitive()) {
            throw new ToolFailure("写清楚 approve：true 同意，false 拒绝");
        }
        return value.getAsBoolean();
    }

    private String nickname(String userId) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("user_id", Long.parseLong(userId));
            JsonObject data = optObject(outbox.call("get_stranger_info", params, TIMEOUT), "data");
            return data == null ? "" : displayName(optString(data, "nickname"));
        } catch (ToolFailure | RuntimeException e) {
            return "";
        }
    }

    private String groupName(String groupId) {
        String known = contacts.groups().get(groupId);
        if (known != null && !known.isBlank()) {
            return displayName(known);
        }
        try {
            JsonObject params = new JsonObject();
            params.addProperty("group_id", Long.parseLong(groupId));
            JsonObject data = optObject(outbox.call("get_group_info", params, TIMEOUT), "data");
            return data == null ? "" : displayName(optString(data, "group_name"));
        } catch (ToolFailure | RuntimeException e) {
            return "";
        }
    }

    private static String comment(String raw) {
        return abbreviate(unforgeable(raw == null ? "" : raw).replaceAll("[\\p{Cntrl}\\s]+", " ").strip(), 200);
    }
}
