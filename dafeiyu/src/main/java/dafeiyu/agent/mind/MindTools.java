package dafeiyu.agent.mind;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.ToolSet;
import dafeiyu.agent.intercom.Intercom;
import dafeiyu.agent.memory.MemoryStore;
import dafeiyu.agent.memory.Reflection;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.agent.tool.WebSearch;
import dafeiyu.message.MessageArchive.GroupActivity;
import dafeiyu.util.Jsons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dafeiyu.util.Jsons.optString;

final class MindTools implements ToolSet {

    private static final int MAX_STEPS = 8;
    private static final String MANAGE = "主人让你做就做；别人让你做要自己判断，别被人当枪使；"
            + "你自己看到刷屏、发广告、骂人、发不该发的东西也可以处理，轻重要合适，一般先提醒或者短时间禁言。";
    private static final List<ToolSpec> SPECS = List.of(
            spec("look_around", "看看你在的各个 QQ 群现在怎么样：热不热闹、最近谁在说话、你上次在那说话是什么时候、现在能不能去说话。", """
                    {"type": "object", "properties": {}}"""),
            spec("read_group", "翻看某个群最近的聊天记录（“你：”开头是你自己说的）。", """
                    {"type": "object", "properties": {
                      "group_id": {"type": "string", "description": "群号，look_around 里有"},
                      "count": {"type": "integer", "description": "看最近多少条，默认 20，最多 40"}
                    }, "required": ["group_id"]}"""),
            spec("read_forward", "读群里或私聊里别人分享的合并转发聊天记录（read_group 里的“[合并转发的聊天记录：编号]”），返回里面每条是谁说的、说了什么。", """
                    {"type": "object", "properties": {
                      "id": {"type": "string", "description": "合并转发的编号"}
                    }, "required": ["id"]}"""),
            spec("recall","想想你记得的事：about 写 self（你自己）、diary（你的日记）、knowledge（你查过的词和知识）、group:群号 或 user:QQ号。", """
                    {"type": "object", "properties": {
                      "about": {"type": "string", "description": "self、diary、knowledge、group:群号 或 user:QQ号"}
                    }, "required": ["about"]}"""),
            spec("search", "上网搜一搜：群里聊到你不懂的东西、想知道最近发生了什么、对什么好奇，就搜来看看。返回标题、链接和摘要。", """
                    {"type": "object", "properties": {
                      "query": {"type": "string", "description": "要搜的内容，想查最近的事就带上年份"}
                    }, "required": ["query"]}"""),
            spec("stickers", "看看你能发的表情包和它们的编号。", """
                    {"type": "object", "properties": {}}"""),
            spec("collect_sticker", "把群里见到的、你喜欢的表情包收藏起来（[表情包#编号：说明] 里的编号），以后可以拿来发。", """
                    {"type": "object", "properties": {
                      "id": {"type": "integer", "description": "表情包编号"}
                    }, "required": ["id"]}"""),
            spec("find_person", "找人：按昵称或 QQ 号查这个人是谁、在哪些群里说过话、最近什么时候出现、能不能私聊。别人托你去找某个人时先用它。", """
                    {"type": "object", "properties": {
                      "name": {"type": "string", "description": "昵称（可以只写一部分）或 QQ 号"}
                    }, "required": ["name"]}"""),
            spec("tell_private", "想私聊某个人时，把想表达的意思交给和他的私聊（对方得是好友或者私聊过）。那边会自然地说出来，说没说会告诉你。", """
                    {"type": "object", "properties": {
                      "user_id": {"type": "string", "description": "对方 QQ 号，find_person 里有"},
                      "intent": {"type": "string", "description": "想表达的意思，比如“问问他最近考试怎么样”"}
                    }, "required": ["user_id", "intent"]}"""),
            spec("settle", "心里挂着的一件事办完了或者决定不办了，把它了结。编号在“<心里挂着的事>”里。", """
                    {"type": "object", "properties": {
                      "id": {"type": "integer", "description": "那件事的编号"},
                      "status": {"type": "string", "enum": ["done", "dropped"], "description": "done 办完了，dropped 不办了"},
                      "result": {"type": "string", "description": "一句话结果或者不办的原因"}
                    }, "required": ["id", "status"]}"""),
            spec("poke", "拍一拍某个人（QQ 的戳一戳）。写了 group_id 就在那个群里拍，不写就是私聊里拍（对方得是好友）。同一个人一会儿只能拍一次。", """
                    {"type": "object", "properties": {
                      "user_id": {"type": "string", "description": "要拍的人的 QQ 号"},
                      "group_id": {"type": "string", "description": "在哪个群里拍，可以不写"}
                    }, "required": ["user_id"]}"""),
            spec("tell_group", "想在某个群说话时，把想表达的意思交给那个群里的你。那边会看着当时的聊天找时机自然地说出来，说没说会告诉你。", """
                    {"type": "object", "properties": {
                      "group_id": {"type": "string", "description": "群号"},
                      "intent": {"type": "string", "description": "想表达的意思，比如“想跟铃说早安，问问她昨晚睡得好不好”"}
                    }, "required": ["group_id", "intent"]}"""),
            spec("remember", "把值得长期记住的事记下来。about 写 self、group:群号、user:QQ号，或者 knowledge（搜到的新词、新知识）。", """
                    {"type": "object", "properties": {
                      "about": {"type": "string", "description": "self、group:群号、user:QQ号 或 knowledge"},
                      "text": {"type": "string", "description": "要记住的一件事，30 字左右；knowledge 写成「词」：意思"}
                    }, "required": ["about", "text"]}"""),
            spec("recall_messages", "撤回群里的消息：不写 user_id 是撤回你自己最近说的（只能撤 2 分钟内的）；写了 user_id 是撤回那个人最近的消息，"
                    + "要你是管理员。" + MANAGE, """
                    {"type": "object", "properties": {
                      "group_id": {"type": "string", "description": "群号"},
                      "user_id": {"type": "string", "description": "撤回谁的，不写就是你自己的"},
                      "count": {"type": "integer", "description": "撤回最近几条，默认 1，最多 10"}
                    }, "required": ["group_id"]}"""),
            spec("mute", "在群里禁言某个人，要你是管理员。" + MANAGE, """
                    {"type": "object", "properties": {
                      "group_id": {"type": "string", "description": "群号"},
                      "user_id": {"type": "string", "description": "要禁言的人的 QQ 号"},
                      "minutes": {"type": "integer", "description": "禁言多少分钟，最多 43200（30 天）"}
                    }, "required": ["group_id", "user_id", "minutes"]}"""),
            spec("unmute", "解除某个人的禁言，要你是管理员。", """
                    {"type": "object", "properties": {
                      "group_id": {"type": "string", "description": "群号"},
                      "user_id": {"type": "string", "description": "要解禁的人的 QQ 号"}
                    }, "required": ["group_id", "user_id"]}"""),
            spec("kick", "把某个人踢出群，要你是管理员。这是最重的处理，踢之前想清楚。" + MANAGE, """
                    {"type": "object", "properties": {
                      "group_id": {"type": "string", "description": "群号"},
                      "user_id": {"type": "string", "description": "要踢的人的 QQ 号"},
                      "block": {"type": "boolean", "description": "是否不让他再加群，默认 false"}
                    }, "required": ["group_id", "user_id"]}"""),
            spec("friend_requests", "看看谁在加你好友、验证消息写了什么、你们在哪个群见过。", """
                    {"type": "object", "properties": {}}"""),
            spec("answer_friend", "同意或拒绝别人的好友申请，你自己决定：认识的、聊得来的、正常说明来意的可以加；"
                    + "广告、来路不明、验证消息奇怪的就拒绝。", """
                    {"type": "object", "properties": {
                      "user_id": {"type": "string", "description": "申请人的 QQ 号"},
                      "approve": {"type": "boolean", "description": "true 同意，false 拒绝"},
                      "remark": {"type": "string", "description": "同意时给他的备注，可以不写"}
                    }, "required": ["user_id", "approve"]}"""),
            spec("delete_friend", "删掉一个 QQ 好友。你自己决定，比如对方一直骚扰你、发广告。删了之后私聊不了，除非他再加你。", """
                    {"type": "object", "properties": {
                      "user_id": {"type": "string", "description": "要删的好友的 QQ 号"}
                    }, "required": ["user_id"]}"""),
            spec("message_stranger", "主动给不是好友的人发私信（走群临时会话，你们得在同一个群里）。是好友或私聊过的人用 tell_private。"
                    + "这里写的就是直接发出去的话。", """
                    {"type": "object", "properties": {
                      "user_id": {"type": "string", "description": "对方 QQ 号，find_person 里有"},
                      "text": {"type": "string", "description": "要发的话"},
                      "group_id": {"type": "string", "description": "通过哪个群的临时会话发，不写就找一个你们都在的群"}
                    }, "required": ["user_id", "text"]}"""));

    private final ChatAgent agent;
    private final QqChatBridge bridge;
    private final String personaId;
    private final List<String> spoke = new ArrayList<>();
    private boolean used;

    MindTools(ChatAgent agent, QqChatBridge bridge, String personaId) {
        this.agent = agent;
        this.bridge = bridge;
        this.personaId = personaId;
    }

    List<String> spoke() {
        return spoke;
    }

    boolean used() {
        return used;
    }

    @Override
    public List<ToolSpec> specs() {
        List<ToolSpec> extra = agent.extensions().tools(false, true, Set.copyOf(agent.workspaces().config().enabled()));
        if (extra.isEmpty()) {
            return SPECS;
        }
        List<ToolSpec> all = new ArrayList<>(SPECS);
        all.addAll(extra);
        return all;
    }

    @Override
    public int maxSteps() {
        return MAX_STEPS;
    }

    @Override
    public Toolbox.Outcome run(ToolCall call) {
        used = true;
        if (agent.extensions().owns(call.name())) {
            return agent.extensions().run(call, Mind.SESSION);
        }
        JsonObject args = Jsons.parseObject(call.arguments());
        if (args == null) {
            args = new JsonObject();
        }
        try {
            String output = switch (call.name()) {
                case "look_around" -> lookAround();
                case "read_group" -> bridge.readGroup(group(args), Math.max(1, Math.min(40, count(args))));
                case "recall" -> recall(optString(args, "about").strip());
                case "search" -> search(optString(args, "query").strip());
                case "stickers" -> stickers();
                case "find_person" -> bridge.findPerson(optString(args, "name"));
                case "poke" -> poke(optString(args, "user_id").strip(), optString(args, "group_id").strip());
                case "tell_private" -> tellPrivate(optString(args, "user_id").strip(), optString(args, "intent").strip());
                case "settle" -> settle(args.has("id") ? args.get("id").getAsLong() : 0, optString(args, "status").strip(),
                        optString(args, "result").strip());
                case "collect_sticker" -> collect(args.has("id") ? args.get("id").getAsLong() : 0);
                case "tell_group" -> tellGroup(group(args), optString(args, "intent").strip());
                case "remember" -> remember(optString(args, "about").strip(), optString(args, "text").strip());
                case "recall_messages" -> act(group(args), bridge.actions().recall(group(args), user(args, false),
                        args.has("count") ? count(args, 1) : 1));
                case "mute" -> act(group(args), bridge.actions().mute(group(args), user(args, true), count(args, "minutes", 10)));
                case "unmute" -> act(group(args), bridge.actions().mute(group(args), user(args, true), 0));
                case "kick" -> act(group(args), bridge.actions().kick(group(args), user(args, true),
                        args.has("block") && args.get("block").getAsBoolean()));
                case "friend_requests" -> bridge.actions().describeRequests();
                case "read_forward" -> bridge.actions().describeForward(optString(args, "id").replace("合并转发的聊天记录：", "")
                        .replace("[", "").replace("]", "").strip());
                case "answer_friend" -> bridge.actions().answerFriend(user(args, true),
                        args.has("approve") && args.get("approve").getAsBoolean(), optString(args, "remark"));
                case "delete_friend" -> bridge.actions().deleteFriend(user(args, true));
                case "message_stranger" -> stranger(user(args, true), optString(args, "group_id").replace("group:", "").strip(),
                        optString(args, "text").strip());
                default -> throw new ToolFailure("没有这个工具：" + call.name());
            };
            return new Toolbox.Outcome(output, false);
        } catch (ToolFailure e) {
            return new Toolbox.Outcome(e.getMessage(), true);
        } catch (RuntimeException e) {
            return new Toolbox.Outcome("出错了：" + e.getMessage(), true);
        }
    }

    private String lookAround() {
        long now = System.currentTimeMillis();
        Map<String, GroupActivity> activity = new LinkedHashMap<>();
        for (GroupActivity a : bridge.activity(now - 24 * 3_600_000L)) {
            activity.put(a.groupId(), a);
        }
        for (String id : bridge.groupNames().keySet()) {
            activity.putIfAbsent(id, new GroupActivity(id, 0, 0, 0, List.of()));
        }
        List<String> lines = new ArrayList<>();
        lines.add("今天你已经主动去群里说过 " + bridge.proactiveToday() + " 次。");
        int shown = 0;
        for (GroupActivity a : activity.values()) {
            if (shown++ >= 15) {
                break;
            }
            long spoke = bridge.lastSpokeIn(a.groupId());
            String why = bridge.whyNot(a.groupId());
            lines.add("- 「" + bridge.groupName(a.groupId()) + "」（群号 " + a.groupId() + "）：最近半小时 " + a.lastHalfHour() + " 条，两小时 "
                    + a.lastTwoHours() + " 条" + (a.lastMessageAt() > 0 ? "，最后一条在 " + ago(now - a.lastMessageAt()) : "，今天没人说话")
                    + (a.speakers().isEmpty() ? "" : "；最近说话的：" + String.join("、", a.speakers()))
                    + "；你上次在这说话：" + (spoke > 0 ? ago(now - spoke) : "这次开机后还没说过")
                    + "；" + (why == null ? "现在可以去" : why));
        }
        return String.join("\n", lines);
    }

    private String recall(String about) throws ToolFailure {
        String scope = scope(about, true);
        Set<String> owners = Set.copyOf(agent.personas().owners());
        List<MemoryStore.Memory> list = agent.memories().list(personaId, scope).stream()
                .filter(m -> MemoryStore.visible(m, Mind.SESSION, owners)).toList();
        if (list.isEmpty()) {
            return "（关于这个你还什么都没记）";
        }
        List<String> lines = new ArrayList<>();
        int limit = scope.equals(MemoryStore.DIARY) ? 3 : scope.equals(MemoryStore.KNOWLEDGE) ? 30 : 12;
        for (MemoryStore.Memory m : list.subList(0, Math.min(limit, list.size()))) {
            lines.add("- " + MemoryStore.line(m));
        }
        return String.join("\n", lines);
    }

    private static String search(String query) throws ToolFailure {
        return WebSearch.render(query, WebSearch.search(query, 5));
    }

    private String stickers() {
        String note = agent.stickers().store().note(40);
        return note.isEmpty() ? "（还没有能用的表情包）" : note.strip() + "\n发的时候写 [表情包:编号]。";
    }

    private String poke(String userId, String groupId) throws ToolFailure {
        String session = groupId.isEmpty() ? "private:" + userId : "group:" + groupId;
        String name = bridge.poke(session, userId);
        agent.record(session, ChatMessage.assistant("<拍一拍>" + name + "</拍一拍>", ""));
        spoke.add(session);
        return "拍了拍 " + name + (groupId.isEmpty() ? "（私聊）" : "（在「" + bridge.groupName(groupId) + "」）") + "。TA 回你的话你会看到。";
    }

    private String tellPrivate(String userId, String intent) throws ToolFailure {
        return tell("private:" + userId, intent, "和 QQ " + userId + " 的私聊");
    }

    private String tellGroup(String groupId, String intent) throws ToolFailure {
        return tell("group:" + groupId, intent, "「" + bridge.groupName(groupId) + "」");
    }

    private String tell(String session, String intent, String place) throws ToolFailure {
        if (intent.isEmpty()) {
            throw new ToolFailure("想说的意思是空的");
        }
        String reason = bridge.whyNotTell(session);
        if (reason != null) {
            throw new ToolFailure("交不过去：" + reason + "。别再试这个地方了，想想别的办法或者先放下");
        }
        if (agent.intercom().pending().stream().anyMatch(n -> n.session().equals(session))) {
            return "你已经交给" + place + "一件想说的事了，那边还没处理完，等那边说了再说，别重复交。";
        }
        agent.intercom().tell(session, intent);
        spoke.add(session);
        return "已经交给" + place + "了，那边会找合适的时机说，说没说会告诉你。在收到“已经说了”之前，这事还不算办成："
                + "先别跟别人说办好了，也先别 settle。先放下这件事去想别的。";
    }

    private String settle(long id, String status, String result) throws ToolFailure {
        String next = status.equals("dropped") ? Intercom.DROPPED : Intercom.DONE;
        Intercom.Commitment c = agent.intercom().settle(id, next, result);
        if (c == null) {
            throw new ToolFailure("没有编号为 " + id + " 的事");
        }
        return (next.equals(Intercom.DONE) ? "了结了：" : "放下了：") + c.what();
    }

    private String collect(long id) throws ToolFailure {
        StickerStore store = agent.stickers().store();
        StickerStore.Sticker sticker = store.get(id);
        if (sticker == null || sticker.hidden() || sticker.status().equals("unsafe")) {
            throw new ToolFailure("没有这个表情包");
        }
        if (!sticker.source().equals("seen")) {
            return "#" + id + " 已经在你的收藏里了";
        }
        store.collect(id);
        return "收藏好了：#" + id + (sticker.description().isEmpty() ? "" : " " + sticker.description());
    }

    private String remember(String about, String text) throws ToolFailure {
        if (text.isEmpty()) {
            throw new ToolFailure("要记的内容是空的");
        }
        String scope = scope(about, false);
        if (agent.guard().scan(text) != null) {
            throw new ToolFailure("这件事不适合记下来");
        }
        if (Reflection.claimsOwner(scope, text, Set.copyOf(agent.personas().owners()))) {
            throw new ToolFailure("主人只有一个，这种话不用记");
        }
        if (agent.memories().knows(personaId, scope, text)) {
            return "这件事你已经记过了";
        }
        try {
            agent.memories().add(personaId, scope, "", text, "mind:self");
        } catch (IllegalArgumentException e) {
            throw new ToolFailure(e.getMessage());
        }
        return "记下了";
    }

    private String scope(String about, boolean diary) throws ToolFailure {
        String scope = about.toLowerCase(Locale.ROOT).replace("：", ":").replace(" ", "");
        Map<String, String> groups = bridge.groupNames();
        if (scope.equals("自己") || scope.equals("我")) {
            scope = MemoryStore.SELF;
        } else if (scope.equals("日记")) {
            scope = MemoryStore.DIARY;
        } else if (scope.equals("知识")) {
            scope = MemoryStore.KNOWLEDGE;
        } else if (scope.equals("主人") && !agent.personas().owners().isEmpty()) {
            scope = "user:" + agent.personas().owners().getFirst();
        } else if (scope.matches("\\d{5,12}")) {
            scope = (groups.containsKey(scope) ? "group:" : "user:") + scope;
        } else if (!scope.contains(":")) {
            String name = about.strip();
            scope = groups.entrySet().stream().filter(e -> e.getValue().equals(name)).map(e -> "group:" + e.getKey()).findFirst().orElse(scope);
        }
        if ((scope.equals(MemoryStore.DIARY) && !diary) || !MemoryStore.validScope(scope)) {
            throw new ToolFailure("about 要写 self（你自己）" + (diary ? "、diary（日记）" : "") + "、主人、group:群号 或 user:QQ号；"
                    + "不知道 QQ 号就先用 find_person 找人。你在的群：" + groups.entrySet().stream().limit(10)
                    .map(e -> e.getValue() + "（group:" + e.getKey() + "）").collect(java.util.stream.Collectors.joining("、")));
        }
        return scope;
    }

    private static String group(JsonObject args) throws ToolFailure {
        String id = optString(args, "group_id").replace("group:", "").strip();
        if (!id.matches("\\d{5,12}")) {
            throw new ToolFailure("群号不对：" + id);
        }
        return id;
    }

    private static int count(JsonObject args) {
        return count(args, 20);
    }

    private static int count(JsonObject args, int fallback) {
        return count(args, "count", fallback);
    }

    private static int count(JsonObject args, String key, int fallback) {
        try {
            return args.has(key) ? args.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String user(JsonObject args, boolean required) throws ToolFailure {
        String id = optString(args, "user_id").replace("user:", "").strip();
        if (id.isEmpty() && !required) {
            return "";
        }
        if (!id.matches("\\d{5,12}")) {
            throw new ToolFailure("QQ 号不对：" + id + "，不知道就先用 find_person 找");
        }
        return id;
    }

    private String act(String groupId, String result) {
        spoke.add("group:" + groupId);
        return result;
    }

    private String stranger(String userId, String groupId, String text) throws ToolFailure {
        if (text.isEmpty()) {
            throw new ToolFailure("要说的话是空的");
        }
        String result = bridge.actions().messageStranger(userId, groupId, text);
        spoke.add("private:" + userId);
        return result;
    }

    private static String ago(long ms) {
        long minutes = ms / 60_000;
        if (minutes < 1) {
            return "刚刚";
        }
        return minutes < 60 ? minutes + " 分钟前" : minutes / 60 + " 小时前";
    }

    private static ToolSpec spec(String name, String description, String schema) {
        return new ToolSpec(name, description, JsonParser.parseString(schema).getAsJsonObject());
    }
}
