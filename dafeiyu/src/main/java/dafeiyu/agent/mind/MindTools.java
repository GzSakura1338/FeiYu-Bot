package dafeiyu.agent.mind;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dafeiyu.agent.BackgroundTasks;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatTools;
import dafeiyu.agent.ImageViewer;
import dafeiyu.agent.SubagentSettings;
import dafeiyu.agent.intercom.Intercom;
import dafeiyu.agent.memory.MemoryStore;
import dafeiyu.agent.memory.Reflection;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.tool.Args;
import dafeiyu.agent.tool.Tool;
import dafeiyu.agent.tool.ToolContext;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.ToolResult;
import dafeiyu.agent.tool.ToolSet;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.agent.tool.WebSearch;
import dafeiyu.message.MessageArchive.GroupActivity;
import dafeiyu.util.Clock;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dafeiyu.util.Texts.abbreviate;

final class MindTools {

    private static final int MAX_STEPS = 8;
    private static final int SEARCHES_PER_HOUR = 20;
    private static final long SEARCH_WINDOW_MS = 3_600_000;
    private static final java.util.Deque<Long> SEARCHES = new java.util.ArrayDeque<>();
    private static final int MAX_DIARY_NOTE = 300;
    private static final String SUBAGENT = "subagent";
    private static final Pattern KNOWN_TERM = Pattern.compile("\\s*[「“\"']?[^」”\"'：:\\n]{1,30}[」”\"']?\\s*[：:]\\s*.+", Pattern.DOTALL);
    private static final Pattern AFTER = Pattern.compile("(\\d{1,4})\\s*(分钟|小时|天)后?");
    private static final Pattern AT = Pattern.compile(
            "(?:(?<y>\\d{4})-(?<mo>\\d{1,2})-(?<d>\\d{1,2})\\s*|(?<day>今天|明天|后天)\\s*)?(?<h>\\d{1,2})[:：](?<mi>\\d{2})");
    private static final long OWNED_MS = 10 * 60_000;
    private static final long REPORT_MS = 10 * 60_000;
    private static final String MANAGE = "主人让你做就做；别人让你做要自己判断，别被人当枪使；"
            + "你自己看到刷屏、发广告、骂人、发不该发的东西也可以处理，轻重要合适，一般先提醒或者短时间禁言。";

    private final ChatAgent agent;
    private final QqChatBridge bridge;
    private final String personaId;
    private final List<String> spoke = new ArrayList<>();

    MindTools(ChatAgent agent, QqChatBridge bridge, String personaId) {
        this.agent = agent;
        this.bridge = bridge;
        this.personaId = personaId;
    }

    List<String> spoke() {
        return spoke;
    }

    ToolSet toolSet() {
        ToolSet.Builder set = ToolSet.builder(ToolContext.of(Mind.SESSION)).maxSteps(MAX_STEPS).addAll(own());
        set.addAll(bridge.requests().tools());
        set.add(Toolbox.get("webfetch"));
        if (agent.vision()) {
            set.add(new ImageViewer(agent, true));
        }
        set.addAll(agent.extensions().tools(false, true, Set.copyOf(agent.workspaces().config().enabled())));
        set.add(subagentTool());
        set.add(agent.tasks().steerTool(null, "内心"));
        set.addAll(bridge.qqApi().tools());
        return set.build();
    }

    private List<Tool> own() {
        return List.of(
                Tool.text("look_around", "看看你在的各个 QQ 群现在怎么样：热不热闹、最近谁在说话、你上次在那说话是什么时候、现在能不能去说话。", """
                        {"type": "object", "properties": {}}""", (args, context) -> lookAround()),
                Tool.text("read_group", "翻看某个群最近的聊天记录（“你：”开头是你自己说的）。", """
                        {"type": "object", "properties": {
                          "group_id": {"type": "string", "description": "群号，look_around 里有"},
                          "count": {"type": "integer", "description": "看最近多少条，默认 20，最多 40"}
                        }, "required": ["group_id"]}""",
                        (args, context) -> bridge.readGroup(args.group("group_id"), Math.max(1, Math.min(40, args.integer("count", 20))))),
                ChatTools.readForward(agent),
                Tool.text("recall", "想想你记得的事：about 写 self（你自己）、diary（你的日记）、knowledge（你查过的词和知识）、group:群号 或 user:QQ号，要多少查多少。", """
                        {"type": "object", "properties": {
                          "about": {"type": "string", "description": "self、diary、knowledge、group:群号 或 user:QQ号"},
                          "query": {"type": "string", "description": "只想找跟某件事有关的就写关键词，不写就是全部"},
                          "offset": {"type": "integer", "description": "接着上次往后看时填，结果里会告诉你填几"}
                        }, "required": ["about"]}""",
                        (args, context) -> recall(args.text("about"), args.text("query"), Math.max(0, args.integer("offset", 0)))),
                ChatTools.skill(agent, personaId),
                Tool.text("search", "上网搜一搜：群里聊到你不懂的东西、想知道最近发生了什么、对什么好奇，就搜来看看。返回标题、链接和摘要。", """
                        {"type": "object", "properties": {
                          "query": {"type": "string", "description": "要搜的内容，想查最近的事就带上年份"}
                        }, "required": ["query"]}""", (args, context) -> search(args.text("query"))),
                Tool.text("stickers", "看看你能发的表情包和它们的编号。", """
                        {"type": "object", "properties": {}}""", (args, context) -> stickers()),
                Tool.text("collect_sticker", "把群里见到的、你喜欢的表情包收藏起来（[表情包#编号：说明] 里的编号），以后可以拿来发。", """
                        {"type": "object", "properties": {
                          "id": {"type": "integer", "description": "表情包编号"}
                        }, "required": ["id"]}""", (args, context) -> collect(args.number("id", 0))),
                Tool.text("find_person", "找人：按昵称或 QQ 号查这个人是谁、在哪些群里说过话、最近什么时候出现、能不能私聊。别人托你去找某个人时先用它。", """
                        {"type": "object", "properties": {
                          "name": {"type": "string", "description": "昵称（可以只写一部分）或 QQ 号"}
                        }, "required": ["name"]}""", (args, context) -> bridge.findPerson(args.text("name"))),
                Tool.text("tell_private", "想私聊某个人时，把想表达的意思交给和他的私聊（对方得是好友或者私聊过）。那边会自然地说出来，说没说会告诉你。", """
                        {"type": "object", "properties": {
                          "user_id": {"type": "string", "description": "对方 QQ 号，find_person 里有"},
                          "intent": {"type": "string", "description": "想表达的意思，比如“问问他最近考试怎么样”"},
                          "again": {"type": "boolean", "description": "被提醒“已经交过了”“TA 正在群里聊”之后，确实还要交时才填 true"}
                        }, "required": ["user_id", "intent"]}""",
                        (args, context) -> tellPrivate(args.qq("user_id"), args.text("intent"), args.bool("again", false))),
                Tool.text("settle", "心里挂着的一件事办完了或者决定不办了，把它了结。编号在“<心里挂着的事>”里。", """
                        {"type": "object", "properties": {
                          "id": {"type": "integer", "description": "那件事的编号"},
                          "status": {"type": "string", "enum": ["done", "dropped"], "description": "done 办完了，dropped 不办了"},
                          "result": {"type": "string", "description": "一句话结果或者不办的原因"}
                        }, "required": ["id", "status"]}""",
                        (args, context) -> settle(args.number("id", 0), args.text("status"), args.text("result"))),
                Tool.text("remind", "定个提醒：要等到某个时候才做的事（几点跟谁说晚安、过一阵再去看看谁回了没有），定好就放下，不用一直记着；到点会叫醒你、告诉你。", """
                        {"type": "object", "properties": {
                          "at": {"type": "string", "description": "什么时候：23:00（今天，过了就是明天）、明天 08:30、2026-10-25 20:00、30分钟后、2小时后"},
                          "what": {"type": "string", "description": "到时候要做什么"}
                        }, "required": ["at", "what"]}""", (args, context) -> remind(args.text("at"), args.text("what"))),
                Tool.text("poke", "拍一拍某个人（QQ 的戳一戳）。写了 group_id 就在那个群里拍，不写就是私聊里拍（对方得是好友）。同一个人一会儿只能拍一次。", """
                        {"type": "object", "properties": {
                          "user_id": {"type": "string", "description": "要拍的人的 QQ 号"},
                          "group_id": {"type": "string", "description": "在哪个群里拍，可以不写"}
                        }, "required": ["user_id"]}""",
                        (args, context) -> poke(args.qq("user_id"), args.text("group_id").isEmpty() ? "" : args.group("group_id"))),
                Tool.text("tell_group", "想在某个群说话时，把想表达的意思交给那个群里的你。那边会看着当时的聊天找时机自然地说出来，说没说会告诉你。", """
                        {"type": "object", "properties": {
                          "group_id": {"type": "string", "description": "群号"},
                          "intent": {"type": "string", "description": "想表达的意思，比如“想跟铃说早安，问问她昨晚睡得好不好”"},
                          "again": {"type": "boolean", "description": "被提醒“已经交过了”之后，确实还要再交时才填 true"}
                        }, "required": ["group_id", "intent"]}""",
                        (args, context) -> tellGroup(args.group("group_id"), args.text("intent"), args.bool("again", false))),
                Tool.text("remember", "把值得长期记住的事记下来。about 写 self、group:群号、user:QQ号、knowledge（搜到的新词、新知识），"
                        + "或者 diary（往今天的日记里补一两句，整天的日记回顾时会自动写）。", """
                        {"type": "object", "properties": {
                          "about": {"type": "string", "description": "self、group:群号、user:QQ号、knowledge 或 diary"},
                          "term": {"type": "string", "description": "about 是 knowledge 时写：这个词、这个东西叫什么（比如「弓头鲸」），以后聊天里有人提到它你就会想起来"},
                          "text": {"type": "string", "description": "要记住的一件事，30 字左右；knowledge 写它的意思、关键知识"}
                        }, "required": ["about", "text"]}""",
                        (args, context) -> remember(args.text("about"), args.text("text"), args.text("term"))),
                Tool.text("recall_messages", "撤回群里的消息：不写 user_id 是撤回你自己最近说的（只能撤 2 分钟内的）；写了 user_id 是撤回那个人最近的消息，"
                        + "要你是管理员。" + MANAGE, """
                        {"type": "object", "properties": {
                          "group_id": {"type": "string", "description": "群号"},
                          "user_id": {"type": "string", "description": "撤回谁的，不写就是你自己的"},
                          "count": {"type": "integer", "description": "撤回最近几条，默认 1，最多 10"}
                        }, "required": ["group_id"]}""",
                        (args, context) -> act(args.group("group_id"),
                                bridge.actions().recall(args.group("group_id"), args.optQq("user_id"), args.integer("count", 1)))),
                Tool.text("mute", "在群里禁言某个人，要你是管理员。" + MANAGE, """
                        {"type": "object", "properties": {
                          "group_id": {"type": "string", "description": "群号"},
                          "user_id": {"type": "string", "description": "要禁言的人的 QQ 号"},
                          "minutes": {"type": "integer", "description": "禁言多少分钟，最多 43200（30 天）"}
                        }, "required": ["group_id", "user_id", "minutes"]}""",
                        (args, context) -> act(args.group("group_id"),
                                bridge.actions().mute(args.group("group_id"), args.qq("user_id"), args.integer("minutes", 10)))),
                Tool.text("unmute", "解除某个人的禁言，要你是管理员。", """
                        {"type": "object", "properties": {
                          "group_id": {"type": "string", "description": "群号"},
                          "user_id": {"type": "string", "description": "要解禁的人的 QQ 号"}
                        }, "required": ["group_id", "user_id"]}""",
                        (args, context) -> act(args.group("group_id"), bridge.actions().mute(args.group("group_id"), args.qq("user_id"), 0))),
                Tool.text("kick", "把某个人踢出群，要你是管理员。这是最重的处理，踢之前想清楚。" + MANAGE, """
                        {"type": "object", "properties": {
                          "group_id": {"type": "string", "description": "群号"},
                          "user_id": {"type": "string", "description": "要踢的人的 QQ 号"},
                          "block": {"type": "boolean", "description": "是否不让他再加群，默认 false"}
                        }, "required": ["group_id", "user_id"]}""",
                        (args, context) -> act(args.group("group_id"),
                                bridge.actions().kick(args.group("group_id"), args.qq("user_id"), args.bool("block", false)))),
                Tool.text("delete_friend", "删掉一个 QQ 好友。你自己决定，比如对方一直骚扰你、发广告。删了之后私聊不了，除非他再加你。", """
                        {"type": "object", "properties": {
                          "user_id": {"type": "string", "description": "要删的好友的 QQ 号"}
                        }, "required": ["user_id"]}""", (args, context) -> bridge.actions().deleteFriend(args.qq("user_id"))),
                Tool.text("message_stranger", "给不是好友、但最近通过群临时会话私聊过你的人回私信。QQ 不让你主动给不是好友的人开私聊："
                        + "对方没先找过你就发不出去，这时去你们都在的群里 @ 他，请他先私聊你或者加你好友。是好友的人用 tell_private。"
                        + "这里写的就是直接发出去的话。", """
                        {"type": "object", "properties": {
                          "user_id": {"type": "string", "description": "对方 QQ 号，find_person 里有"},
                          "text": {"type": "string", "description": "要发的话"},
                          "group_id": {"type": "string", "description": "通过哪个群的临时会话发，不写就找一个你们都在的群"}
                        }, "required": ["user_id", "text"]}""",
                        (args, context) -> stranger(args.qq("user_id"), args.text("group_id").replace("group:", "").strip(), args.text("text"))));
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

    private String recall(String about, String query, int offset) throws ToolFailure {
        String scope = scope(about, true);
        return agent.memories().lookup(personaId, scope, Mind.SESSION, Set.copyOf(agent.personas().owners()), query, offset);
    }

    private static String search(String query) throws ToolFailure {
        synchronized (SEARCHES) {
            long now = System.currentTimeMillis();
            while (!SEARCHES.isEmpty() && now - SEARCHES.peekFirst() > SEARCH_WINDOW_MS) {
                SEARCHES.removeFirst();
            }
            if (SEARCHES.size() >= SEARCHES_PER_HOUR) {
                long minutes = (SEARCH_WINDOW_MS - (now - SEARCHES.peekFirst())) / 60_000 + 1;
                return "（这一小时已经搜了 " + SEARCHES_PER_HOUR + " 次，再搜搜索服务要限流了。先歇一歇，大约 " + minutes
                        + " 分钟后再搜；这会儿用你已经知道的、记下的去想。）";
            }
            SEARCHES.addLast(now);
        }
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

    private String tellPrivate(String userId, String intent, boolean again) throws ToolFailure {
        boolean entrusted = agent.intercom().entrustedInPrivate(REPORT_MS).stream().anyMatch(c -> c.session().equals("private:" + userId));
        if (!again && !entrusted) {
            String groupId = bridge.activeGroupWith(userId, OWNED_MS);
            if (groupId != null) {
                return "QQ " + userId + " 这会儿正在「" + bridge.groupName(groupId) + "」里跟你聊着（群号 " + groupId + "），那边的你在跟 TA 说话。"
                        + "要说的交给那个群（tell_group）就行，别再分到私聊，免得一件事两头说、两头做。确实只能私下说的（私事、秘密），带上 again=true 再交。";
            }
        }
        return tell("private:" + userId, intent, "和 QQ " + userId + " 的私聊", again);
    }

    private String tellGroup(String groupId, String intent, boolean again) throws ToolFailure {
        if (!again) {
            for (Intercom.Commitment c : agent.intercom().entrustedInPrivate(REPORT_MS)) {
                String userId = c.session().substring("private:".length());
                if (mentions(intent, userId) && groupId.equals(bridge.activeGroupWith(userId, OWNED_MS))) {
                    return c.who() + "在私聊里托过你「" + abbreviate(c.what(), 60) + "」，TA 这会儿也在「" + bridge.groupName(groupId) + "」里。"
                            + "这句要是在跟 TA 说这件事的进展或结果，用 tell_private（user_id " + userId + "）回私聊，别交给群里，群里所有人都看得到；"
                            + "本来就要在这个群里说的话，带上 again=true 再交。";
                }
            }
        }
        return tell("group:" + groupId, intent, "「" + bridge.groupName(groupId) + "」", again);
    }

    private boolean mentions(String intent, String userId) {
        return agent.personas().isOwner(userId) && intent.contains("主人") || intent.contains(userId)
                || bridge.namesOf(userId).stream().anyMatch(intent::contains);
    }

    private String placeOf(String session) {
        return session.startsWith("group:") ? "「" + bridge.groupName(session.substring(6)) + "」" : "和 QQ " + session.substring(session.indexOf(':') + 1) + " 的私聊";
    }

    private String tell(String session, String intent, String place, boolean again) throws ToolFailure {
        if (intent.isEmpty()) {
            throw new ToolFailure("想说的意思是空的");
        }
        String reason = bridge.whyNotTell(session);
        if (reason != null) {
            throw new ToolFailure("交不过去：" + reason + "。别再试这个地方了，想想别的办法或者先放下");
        }
        Intercom.Told same = again ? null : agent.intercom().similar(intent);
        if (same != null) {
            boolean waiting = agent.intercom().pending().stream().anyMatch(n -> n.session().equals(same.session()) && n.intent().equals(same.intent()));
            return Clock.ago(same.at()) + "你已经把差不多的事交给" + placeOf(same.session()) + "了：「" + abbreviate(same.intent(), 80) + "」，"
                    + (waiting ? "那边还没说。" : "看回执就知道结果。") + "一件事只交一个地方，别重复交；确实要补充新内容或者换个地方，带上 again=true 再交。";
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

    private String remind(String at, String what) throws ToolFailure {
        if (what.isEmpty()) {
            throw new ToolFailure("what 要写到时候做什么");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime when;
        Matcher m;
        if ((m = AFTER.matcher(at)).matches()) {
            long n = Long.parseLong(m.group(1));
            when = m.group(2).startsWith("分") ? now.plusMinutes(n) : m.group(2).startsWith("小") ? now.plusHours(n) : now.plusDays(n);
        } else if ((m = AT.matcher(at)).matches()) {
            try {
                LocalTime time = LocalTime.of(Integer.parseInt(m.group("h")), Integer.parseInt(m.group("mi")));
                if (m.group("y") != null) {
                    when = LocalDate.of(Integer.parseInt(m.group("y")), Integer.parseInt(m.group("mo")), Integer.parseInt(m.group("d"))).atTime(time);
                } else {
                    String day = m.group("day") == null ? "" : m.group("day");
                    when = now.toLocalDate().plusDays(day.equals("明天") ? 1 : day.equals("后天") ? 2 : 0).atTime(time);
                    if (day.isEmpty() && !when.isAfter(now)) {
                        when = when.plusDays(1);
                    }
                }
            } catch (DateTimeException e) {
                throw new ToolFailure("没有这个时间：" + at);
            }
        } else {
            throw new ToolFailure("看不懂这个时间：" + at + "。写 23:00、明天 08:30、2026-10-25 20:00、30分钟后 这样的");
        }
        if (!when.isAfter(now) || when.isAfter(now.plusDays(30))) {
            throw new ToolFailure("提醒要定在现在之后、30 天以内");
        }
        long millis = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<Intercom.Reminder> all = agent.intercom().remind(millis, abbreviate(what, 200));
        StringBuilder out = new StringBuilder("定好了，" + Clock.stamp(millis) + " 提醒你：" + what + "。在那之前不用再记着它了。");
        if (all.size() > 1) {
            out.append("\n现在定着的提醒：");
            all.forEach(r -> out.append("\n- ").append(Clock.stamp(r.at())).append(" ").append(r.what()));
        }
        return out.toString();
    }

    private String remember(String about, String text, String term) throws ToolFailure {
        if (text.isEmpty()) {
            throw new ToolFailure("要记的内容是空的");
        }
        String scope = scope(about, true);
        if (scope.equals(MemoryStore.DIARY) && text.length() > MAX_DIARY_NOTE) {
            throw new ToolFailure("往日记里补一两句就好（" + MAX_DIARY_NOTE + " 字以内）；一整天的日记回顾时会自动写");
        }
        if (scope.equals(MemoryStore.KNOWLEDGE) && !term.isEmpty()) {
            text = "「" + term.replaceAll("^[「“\"']+|[」”\"']+$", "") + "」：" + text;
        } else if (scope.equals(MemoryStore.KNOWLEDGE) && !KNOWN_TERM.matcher(text).matches()) {
            throw new ToolFailure("记知识要写 term：这是什么词、什么东西。比如 term 写「" + abbreviate(text.replaceAll("[（(，,：:。].*", ""), 8)
                    + "」，text 写它的意思。改好再记一次");
        }
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

    private Tool subagentTool() {
        SubagentSettings.Config config = agent.tasks().subagents().config();
        List<String> kinds = new ArrayList<>();
        if (config.enabled() && config.research()) {
            kinds.add(BackgroundTasks.RESEARCH);
        }
        if (config.enabled() && config.build() && agent.workspaces().workspace() != null) {
            kinds.add(BackgroundTasks.BUILD);
        }
        if (kinds.isEmpty()) {
            return null;
        }
        String which = String.join("；", kinds.stream().map(k -> k.equals(BackgroundTasks.RESEARCH)
                ? "research 查资料：要连着搜好几次、读好几个网页、看图才弄得清的事，只交回一段结论，不会写文件"
                : "build 动手：在工作目录里做网页、画画、写文件，要留下文件（比如写成报告）就选它").toList());
        int today = agent.tasks().spawnedToday();
        String quota = config.dailyLimit() > 0 ? "今天已经开了 " + today + " 个，每天最多 " + config.dailyLimit() + " 个。" : "";
        JsonObject schema = Args.schema("""
                {"type": "object", "properties": {
                  "kind": {"type": "string", "description": "开新的子 agent 时选种类"},
                  "title": {"type": "string", "description": "一句话的任务名，显示在网页上"},
                  "task": {"type": "string", "description": "完整的任务说明：要做成什么、为什么、相关背景，别人托的写清是谁在哪托的。它看不到你的念头"},
                  "continue": {"type": "string", "description": "接着让之前某个子 agent 干（追问、改要求）时，填它的编号（#后面那串）；开新的就不写"}
                }, "required": ["task"]}""");
        JsonArray values = new JsonArray();
        kinds.forEach(values::add);
        schema.getAsJsonObject("properties").getAsJsonObject("kind").add("enum", values);
        return Tool.of(new ToolSpec(SUBAGENT, "把要花时间的事交给子 agent 在后台去办，交出去马上就能接着想别的，做好了会告诉你。能开的种类：" + which + "。" + quota, schema),
                (args, context) -> ToolResult.text(subagent(args)));
    }

    private String subagent(Args args) throws ToolFailure {
        String task = args.text("task");
        String again = args.text("continue").replace("#", "").strip();
        if (!again.isEmpty()) {
            return agent.tasks().again(again, task);
        }
        String kind = args.text("kind");
        String title = args.text("title");
        BackgroundTasks.Task started = agent.tasks().spawn(kind, title.isEmpty() ? abbreviate(task, 20) : title, task);
        return "已经交给子 agent 了：#" + started.id() + "「" + started.title() + "」（" + BackgroundTasks.label(kind) + "）。做好会告诉你，"
                + "你先去想别的、做别的，别等着，也别再交一遍；要改要求或者叫停用 steer_task。";
    }
}
