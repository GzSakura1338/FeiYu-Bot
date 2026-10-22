package dafeiyu.agent.mind;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.ToolSet;
import dafeiyu.agent.memory.MemoryStore;
import dafeiyu.agent.memory.Reflection;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Toolbox;
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
    private static final List<ToolSpec> SPECS = List.of(
            spec("look_around", "看看你在的各个 QQ 群现在怎么样：热不热闹、最近谁在说话、你上次在那说话是什么时候、现在能不能去说话。", """
                    {"type": "object", "properties": {}}"""),
            spec("read_group", "翻看某个群最近的聊天记录（“你：”开头是你自己说的）。", """
                    {"type": "object", "properties": {
                      "group_id": {"type": "string", "description": "群号，look_around 里有"},
                      "count": {"type": "integer", "description": "看最近多少条，默认 20，最多 40"}
                    }, "required": ["group_id"]}"""),
            spec("recall", "想想你记得的事：about 写 self（你自己）、diary（你的日记）、group:群号 或 user:QQ号。", """
                    {"type": "object", "properties": {
                      "about": {"type": "string", "description": "self、diary、group:群号 或 user:QQ号"}
                    }, "required": ["about"]}"""),
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
            spec("say_private", "私聊某个人说一句话（对方得是好友或者私聊过）。会真的发到对方 QQ。可以写 [表情包:编号] 和 QQ 表情。", """
                    {"type": "object", "properties": {
                      "user_id": {"type": "string", "description": "对方 QQ 号，find_person 里有"},
                      "text": {"type": "string", "description": "要说的话，一两句就好"}
                    }, "required": ["user_id", "text"]}"""),
            spec("poke", "拍一拍某个人（QQ 的戳一戳）。写了 group_id 就在那个群里拍，不写就是私聊里拍（对方得是好友）。同一个人一会儿只能拍一次。", """
                    {"type": "object", "properties": {
                      "user_id": {"type": "string", "description": "要拍的人的 QQ 号"},
                      "group_id": {"type": "string", "description": "在哪个群里拍，可以不写"}
                    }, "required": ["user_id"]}"""),
            spec("say_in_group", "去某个群说一句话。会真的发到 QQ 群里，大家都看得到。可以写 [表情包:编号]、QQ 表情和 @昵称。", """
                    {"type": "object", "properties": {
                      "group_id": {"type": "string", "description": "群号"},
                      "text": {"type": "string", "description": "要说的话，一句就好"}
                    }, "required": ["group_id", "text"]}"""),
            spec("remember", "把值得长期记住的事记下来。about 写 self、group:群号 或 user:QQ号。", """
                    {"type": "object", "properties": {
                      "about": {"type": "string", "description": "self、group:群号 或 user:QQ号"},
                      "text": {"type": "string", "description": "要记住的一件事，30 字左右"}
                    }, "required": ["about", "text"]}"""));

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
        return SPECS;
    }

    @Override
    public int maxSteps() {
        return MAX_STEPS;
    }

    @Override
    public Toolbox.Outcome run(ToolCall call) {
        used = true;
        JsonObject args = Jsons.parseObject(call.arguments());
        if (args == null) {
            args = new JsonObject();
        }
        try {
            String output = switch (call.name()) {
                case "look_around" -> lookAround();
                case "read_group" -> bridge.readGroup(group(args), Math.max(1, Math.min(40, count(args))));
                case "recall" -> recall(optString(args, "about").strip());
                case "stickers" -> stickers();
                case "find_person" -> bridge.findPerson(optString(args, "name"));
                case "poke" -> poke(optString(args, "user_id").strip(), optString(args, "group_id").strip());
                case "say_private" -> sayPrivate(optString(args, "user_id").strip(), optString(args, "text").strip());
                case "collect_sticker" -> collect(args.has("id") ? args.get("id").getAsLong() : 0);
                case "say_in_group" -> say(group(args), optString(args, "text").strip());
                case "remember" -> remember(optString(args, "about").strip(), optString(args, "text").strip());
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
        List<MemoryStore.Memory> list = agent.memories().list(personaId, scope);
        if (list.isEmpty()) {
            return "（关于这个你还什么都没记）";
        }
        List<String> lines = new ArrayList<>();
        for (MemoryStore.Memory m : list.subList(0, Math.min(12, list.size()))) {
            lines.add("- " + m.content());
        }
        return String.join("\n", lines);
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

    private String sayPrivate(String userId, String text) throws ToolFailure {
        if (text.isEmpty()) {
            throw new ToolFailure("要说的话是空的");
        }
        String sent = bridge.speakPrivate(userId, text);
        used = true;
        spoke.add("private:" + userId);
        return "已经私聊 QQ " + userId + " 说了：" + sent + "。对方回你的话，你会在私聊里看到。";
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

    private String say(String groupId, String text) throws ToolFailure {
        if (text.isEmpty()) {
            throw new ToolFailure("要说的话是空的");
        }
        String sent = bridge.speak(groupId, text);
        spoke.add(groupId);
        return "已经在「" + bridge.groupName(groupId) + "」说了：" + sent + "。有人接话的话，你会在那个群里看到。";
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

    private static String scope(String about, boolean diary) throws ToolFailure {
        String scope = about.toLowerCase(Locale.ROOT).replace("：", ":").replace(" ", "");
        if (scope.equals("自己") || scope.equals("我")) {
            scope = MemoryStore.SELF;
        }
        if ((scope.equals(MemoryStore.DIARY) && !diary) || !MemoryStore.validScope(scope)) {
            throw new ToolFailure("about 要写 self" + (diary ? "、diary" : "") + "、group:群号 或 user:QQ号");
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
        try {
            return args.has("count") ? args.get("count").getAsInt() : 20;
        } catch (RuntimeException e) {
            return 20;
        }
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
