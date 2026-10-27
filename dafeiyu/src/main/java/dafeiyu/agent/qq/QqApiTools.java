package dafeiyu.agent.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.qq.QqApi.Action;
import dafeiyu.agent.qq.QqApi.Param;
import dafeiyu.agent.qq.QqApiPolicy.Access;
import dafeiyu.agent.tool.Args;
import dafeiyu.agent.tool.PublicWeb;
import dafeiyu.agent.tool.Tool;
import dafeiyu.agent.tool.ToolContext;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.ToolResult;
import dafeiyu.agent.tool.Workspace;
import dafeiyu.log.LogBuffer;
import dafeiyu.util.Jsons;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static dafeiyu.util.Texts.abbreviate;

public final class QqApiTools {

    public static final String SEARCH = "qq_api_search";
    public static final String CALL = "qq_api_call";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SLOW_TIMEOUT = Duration.ofSeconds(120);
    private static final Pattern SLOW = Pattern.compile("upload|ai_record|^download_file$|flash|fileset|custom_face|qzone|album");
    private static final Pattern NOT_TEXT = Pattern.compile("(?i).*(id|flag|type|seq|status|url|uin|format|time|count|duration|enable|approve|escape|file|files|image|images|path)$");
    private static final Set<String> FILE_KEYS = Set.of("file", "files", "image", "images", "path", "file_path", "folder_path", "thumb_path");
    private static final Pattern LOCAL_PATH = Pattern.compile("(?i)^[a-z]:|^file:|\\\\|\\.\\.|^~|^/(?!\\p{XDigit}{8}-\\p{XDigit}{4}-)");
    private static final Set<String> SEND = Set.of("send_group_msg", "send_private_msg", "send_msg");
    private static final Set<String> POKE = Set.of("group_poke", "friend_poke", "send_poke");
    private static final int MAX_FOUND = 8;
    private static final int MAX_LISTED = 8;
    private static final int MAX_OUTPUT = 12_000;
    private static final int MAX_ITEMS = 40;
    private static final int MAX_STRING = 400;
    private static final int WRITES_PER_MINUTE = 30;
    private static final Pattern HARD_READ =
            Pattern.compile("^(get_|fetch_|can_|check_|nc_get_|_get_)[a-z0-9_]*$");

    private final ChatAgent agent;
    private final QqApi api;
    private final QqApiPolicy policy;
    private final QqChatBridge bridge;
    private final QqOutbox outbox;
    private final QqActions actions;
    private final QqContacts contacts;
    private final LogBuffer log;
    private final Deque<Long> writes = new ArrayDeque<>();

    QqApiTools(ChatAgent agent, QqApi api, QqApiPolicy policy, QqChatBridge bridge, QqOutbox outbox, QqActions actions, QqContacts contacts,
               LogBuffer log) {
        this.agent = agent;
        this.api = api;
        this.policy = policy;
        this.bridge = bridge;
        this.outbox = outbox;
        this.actions = actions;
        this.contacts = contacts;
        this.log = log;
    }

    public QqApi api() {
        return api;
    }

    public record Check(String tool, String action, String result, String detail) {

        public static final String OK = "ok";
        public static final String FAILED = "failed";
        public static final String SKIPPED = "skipped";
        public static final String OFF = "off";
        public static final String DRY = "dry";
    }

    record Probe(String groupId, List<String> otherGroups, Map<String, String> known) {}

    private static final Map<String, List<String>> OPTIONAL_FILLS = Map.of(
            "share_peer", List.of("group_id"), "send_ark_share", List.of("group_id"), "get_image", List.of("file"),
            "get_file", List.of("file"), "get_record", List.of("file"), "get_forward_msg", List.of("id"),
            "get_mini_app_ark", List.of("type"), "get_group_file_url", List.of("busid"));

    List<Check> selfTest(Probe probe) {
        List<Check> out = new ArrayList<>();
        ToolContext context = new ToolContext("group:" + probe.groupId(), "", null, null, false, false);
        Tool search = searchTool();
        Tool call = callTool();
        out.add(run(search, "", new JsonObject(), context));
        for (String category : api.categories()) {
            JsonObject args = new JsonObject();
            args.addProperty("category", category);
            out.add(run(search, "分类：" + category, args, context));
        }
        Map<String, String> found = discover(probe);
        for (Action a : api.all()) {
            if (!policy.allowed(a.name())) {
                out.add(new Check(CALL, a.name(), Check.OFF, why(a)));
                continue;
            }
            if (policy.access(a.name()) == Access.WRITE || !HARD_READ.matcher(a.name()).matches()) {
                out.add(new Check(CALL, a.name(), Check.DRY, "会改动东西，自测不真调；接口在清单里、开关开着"));
                continue;
            }
            JsonObject params = new JsonObject();
            String missing = null;
            List<String> extra = OPTIONAL_FILLS.getOrDefault(a.name(), List.of());
            for (Param p : a.params()) {
                if (!p.required() && !extra.contains(p.name())) {
                    continue;
                }
                JsonElement value = sample(a.name(), p.name(), probe.groupId(), found);
                if (value == null && p.required()) {
                    missing = p.name();
                    break;
                }
                if (value != null) {
                    params.add(p.name(), value);
                }
            }
            if (missing != null) {
                out.add(new Check(CALL, a.name(), Check.SKIPPED, "必填参数 " + missing + " 找不到能用的真实数据"));
                continue;
            }
            Check check = run(call, a.name(), args(a.name(), params), context);
            if (check.result().equals(Check.FAILED) && params.has("group_id")) {
                for (String other : probe.otherGroups()) {
                    params.addProperty("group_id", Long.parseLong(other));
                    Check retry = run(call, a.name(), args(a.name(), params), new ToolContext("group:" + other, "", null, null, false, false));
                    if (retry.result().equals(Check.OK)) {
                        check = new Check(CALL, a.name(), Check.OK, "在群 " + probe.groupId() + " 失败（" + abbreviate(check.detail(), 60) + "），在群 " + other + " 成功");
                        break;
                    }
                }
            }
            out.add(check);
        }
        return out;
    }

    private static JsonObject args(String action, JsonObject params) {
        JsonObject args = new JsonObject();
        args.addProperty("action", action);
        args.add("params", params.deepCopy());
        return args;
    }

    private Map<String, String> discover(Probe probe) {
        Map<String, String> found = new java.util.HashMap<>();
        probe.known().forEach((key, value) -> put(found, key, value));
        JsonObject group = new JsonObject();
        group.addProperty("group_id", Long.parseLong(probe.groupId()));
        put(found, "file_id", first(quietly("get_group_root_files", group), "file_id"));
        put(found, "busid", first(quietly("get_group_root_files", group), "busid"));
        put(found, "album_id", first(quietly("get_qun_album_list", group), "album_id"));
        JsonObject characters = group.deepCopy();
        characters.addProperty("chat_type", 1);
        put(found, "character", first(quietly("get_ai_characters", characters), "character_id"));
        put(found, "tid", first(quietly("get_qzone_msg_list", new JsonObject()), "tid"));
        return found;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private JsonObject quietly(String action, JsonObject params) {
        try {
            return outbox.call(action, params, TIMEOUT);
        } catch (ToolFailure e) {
            return null;
        }
    }

    private static String first(JsonElement element, String key) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                return o.get(key).getAsString();
            }
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                String found = first(e.getValue(), key);
                if (found != null) {
                    return found;
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement e : element.getAsJsonArray()) {
                String found = first(e, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static Check run(Tool tool, String action, JsonObject args, ToolContext context) {
        try {
            ToolResult result = tool.run(new Args(args), context);
            return new Check(tool.name(), action, result.error() ? Check.FAILED : Check.OK, abbreviate(result.output().replaceAll("\\s+", " "), 160));
        } catch (ToolFailure e) {
            return new Check(tool.name(), action, Check.FAILED, abbreviate(String.valueOf(e.getMessage()), 300));
        } catch (RuntimeException e) {
            return new Check(tool.name(), action, Check.FAILED, "程序出错：" + e);
        }
    }

    private JsonElement sample(String action, String name, String groupId, Map<String, String> found) {
        if (action.equals("get_forward_msg") && (name.equals("id") || name.equals("message_id"))) {
            return text(found.get("forward_id"));
        }
        if (action.equals("fetch_ptt_text") && name.equals("message_id")) {
            return found.containsKey("voice_message_id") ? new JsonPrimitive(Long.parseLong(found.get("voice_message_id"))) : null;
        }
        if (action.equals("get_private_file_url") && name.equals("file_id")) {
            return text(found.get("private_file_id"));
        }
        return switch (name) {
            case "group_id" -> new JsonPrimitive(Long.parseLong(groupId));
            case "user_id" -> new JsonPrimitive(Long.parseLong(outbox.selfId()));
            case "message_id" -> found.containsKey("message_id") ? new JsonPrimitive(Long.parseLong(found.get("message_id"))) : null;
            case "words" -> {
                JsonArray words = new JsonArray();
                words.add("hello");
                yield words;
            }
            case "url", "picUrl", "jumpUrl" -> new JsonPrimitive("https://www.baidu.com");
            case "title", "desc", "text" -> new JsonPrimitive("自测");
            case "domain" -> new JsonPrimitive("qun.qq.com");
            case "type" -> action.equals("get_mini_app_ark") ? new JsonPrimitive("bili") : null;
            case "file" -> switch (action) {
                case "get_image" -> text(found.get("image_file"));
                case "get_file" -> text(found.get("image_file"));
                case "get_record" -> text(found.get("voice_file"));
                default -> null;
            };
            case "out_format" -> new JsonPrimitive("mp3");
            case "image" -> text(found.get("image_url"));
            case "file_id" -> text(found.get("file_id"));
            case "busid" -> found.containsKey("busid") && found.get("busid").matches("\\d{1,18}") ? new JsonPrimitive(Long.parseLong(found.get("busid")))
                    : text(found.get("busid"));
            case "album_id" -> text(found.get("album_id"));
            case "character" -> text(found.get("character"));
            case "emojiId", "emoji_id" -> new JsonPrimitive("76");
            case "emojiType", "emoji_type" -> new JsonPrimitive("1");
            case "face_id" -> new JsonPrimitive(76);
            case "query" -> new JsonPrimitive("笑");
            case "tid" -> text(found.get("tid"));
            default -> null;
        };
    }

    private static JsonElement text(String value) {
        return value == null ? null : new JsonPrimitive(value);
    }

    public QqApiPolicy policy() {
        return policy;
    }

    public List<Tool> tools() {
        if (api.all().isEmpty() || !policy.config().enabled()) {
            return List.of();
        }
        return List.of(searchTool(), callTool());
    }

    private Tool searchTool() {
        JsonObject schema = Args.schema("""
                {"type": "object", "properties": {
                  "query": {"type": "string", "description": "关键词或接口名，比如 群名片、精华、群公告、点赞、群文件、set_group_card；不写就列出所有分类"},
                  "category": {"type": "string", "description": "只看某一类的全部接口"}
                }}""");
        JsonArray categories = new JsonArray();
        api.categories().forEach(categories::add);
        schema.getAsJsonObject("properties").getAsJsonObject("category").add("enum", categories);
        return Tool.of(new ToolSpec(SEARCH, "查 QQ 还能做哪些事：SnowLuma 一共有 " + api.all().size() + " 个 QQ 接口（各种消息、群管理、群公告、精华、群文件、群相册、"
                + "QQ 空间、点赞、资料、表情回应、在线状态……）。按关键词或分类找，返回接口名、作用和参数怎么填，找到后用 " + CALL + " 调用。"
                + "在群里说话、私聊、禁言、踢人、撤回、拍一拍、处理申请这些已经有专门的工具，有专门的就用专门的。", schema),
                (args, context) -> ToolResult.text(search(args.text("query"), args.text("category"))));
    }

    private Tool callTool() {
        return Tool.text(CALL, "调用一个 QQ 接口（SnowLuma / OneBot 11）。action 填接口名，params 填参数对象；不知道接口名或参数怎么填，先用 " + SEARCH + " 查。"
                + "会改动东西的接口（发消息、改群设置、传文件、点赞……）只在主人让你做、或者你自己想清楚该做的时候用，别人让的要自己判断，别被人当枪使。"
                + "文件参数只能填公网网址、base64:// 或工作目录里的文件。", """
                {"type": "object", "properties": {
                  "action": {"type": "string", "description": "接口名，比如 set_group_card；不知道就先用 qq_api_search 查"},
                  "params": {"type": "object", "description": "接口参数，按 qq_api_search 给的说明填，比如 {\\"group_id\\": 123456, \\"user_id\\": 654321, \\"card\\": \\"新名片\\"}"}
                }, "required": ["action"]}""", this::call);
    }

    private String search(String query, String category) throws ToolFailure {
        if (!category.isEmpty() && !api.categories().contains(category)) {
            throw new ToolFailure("没有「" + category + "」这个分类，可选：" + String.join("、", api.categories()));
        }
        if (query.isEmpty() && category.isEmpty()) {
            return overview();
        }
        List<Action> found = query.isEmpty() ? api.inCategory(category)
                : api.search(query, category.isEmpty() ? MAX_FOUND : Integer.MAX_VALUE).stream()
                .filter(a -> category.isEmpty() || a.category().equals(category)).limit(MAX_FOUND).toList();
        if (found.isEmpty()) {
            return "没找到和「" + query + "」有关的接口，换个说法，或者不写 query 看看分类。";
        }
        return (query.isEmpty() ? "「" + category + "」一共 " + found.size() + " 个接口：" : "找到 " + found.size() + " 个：") + "\n\n"
                + found.stream().map(this::describe).collect(Collectors.joining("\n\n"))
                + "\n\n用 " + CALL + " 调用：action 填接口名，params 填参数。";
    }

    private String overview() {
        long usable = api.all().stream().filter(a -> policy.allowed(a.name())).count();
        StringBuilder out = new StringBuilder("SnowLuma " + api.version() + " 一共 " + api.all().size() + " 个 QQ 接口，现在能用 " + usable + " 个。分类：");
        for (String category : api.categories()) {
            List<Action> list = api.inCategory(category);
            Set<String> names = new LinkedHashSet<>(list.stream().map(Action::summary).toList());
            out.append("\n- ").append(category).append("（").append(list.size()).append(" 个）：")
                    .append(String.join("、", names.stream().limit(MAX_LISTED).toList())).append(names.size() > MAX_LISTED ? "……" : "");
        }
        return out.append("\n用 category 看一类的全部接口和参数，或者用 query 搜关键词（比如“群名片”“精华”“公告”）。").toString();
    }

    private String describe(Action a) {
        StringBuilder out = new StringBuilder(a.name()).append("：").append(a.summary());
        if (!a.description().isEmpty()) {
            out.append("。").append(a.description());
        }
        out.append(policy.access(a.name()) == Access.READ ? "［只读］" : "［会改动东西］");
        if (!QqApiPolicy.sideEffect(a.name()).isEmpty()) {
            out.append("［注意：").append(QqApiPolicy.sideEffect(a.name())).append("］");
        }
        if (!policy.allowed(a.name())) {
            out.append("［现在用不了：").append(why(a)).append("］");
        }
        out.append(a.params().isEmpty() ? "\n  不用参数" : "\n  参数：" + a.params().stream().map(QqApiTools::param).collect(Collectors.joining("；")));
        return out.toString();
    }

    private static String param(Param p) {
        return p.name() + "（" + (p.required() ? "必填" : "可选") + "，" + p.type() + (p.description().isEmpty() ? "" : "，" + p.description()) + "）";
    }

    private String why(Action a) {
        String reason = QqApiPolicy.lockReason(a.name());
        return reason.isEmpty() ? "主人在设置里关掉了" : "高危接口（" + reason + "），默认关着，要用得请主人在网页「设置 › Agent › QQ 接口」里打开";
    }

    private String call(Args args, ToolContext context) throws ToolFailure {
        String name = args.text("action");
        Action action = api.get(name);
        if (action == null) {
            List<Action> near = api.search(name.replace('_', ' '), 5);
            throw new ToolFailure("没有叫「" + name + "」的接口" + (near.isEmpty() ? "，先用 " + SEARCH + " 查"
                    : "。是不是这些：" + near.stream().map(a -> a.name() + "（" + a.summary() + "）").collect(Collectors.joining("、"))));
        }
        if (!policy.allowed(action.name())) {
            throw new ToolFailure(action.name() + "（" + action.summary() + "）现在用不了：" + why(action));
        }
        JsonObject params = params(args);
        List<String> missing = action.params().stream().filter(Param::required)
                .filter(p -> !params.has(p.name()) || params.get(p.name()).isJsonNull()).map(QqApiTools::param).toList();
        if (!missing.isEmpty()) {
            throw new ToolFailure("缺参数：" + String.join("；", missing) + "\n完整说明：\n" + describe(action));
        }
        JsonObject raw = params.deepCopy();
        List<String> locals = new ArrayList<>();
        boolean ids = policy.access(action.name()) == Access.READ;
        checkFiles(params, "", locals, ids);
        protect(action.name(), params);
        boolean writes = policy.access(action.name()) == Access.WRITE;
        if (writes) {
            requireOwner(action, context);
            throttle();
        }
        String routed = route(action, params, context);
        if (routed != null) {
            log.info("agent [" + context.session() + "] qq api " + action.name() + " (routed) " + abbreviate(params.toString(), 200));
            return routed;
        }
        if (writes) {
            review(params);
        }
        Duration timeout = SLOW.matcher(action.name()).find() ? SLOW_TIMEOUT : TIMEOUT;
        JsonObject response;
        try {
            response = outbox.call(action.name(), params, timeout);
        } catch (ToolFailure e) {
            if (locals.isEmpty() || outbox.pathsUnreadable()) {
                throw e;
            }
            outbox.markPathsUnreadable(e.getMessage());
            if (!outbox.pathsUnreadable()) {
                throw e;
            }
            JsonObject inline = raw.deepCopy();
            checkFiles(inline, "", new ArrayList<>(), ids);
            response = outbox.call(action.name(), inline, timeout);
        }
        log.info("agent [" + context.session() + "] qq api " + action.name() + " " + abbreviate(params.toString(), 200));
        if (writes) {
            noteDone(action, params);
        }
        return render(action, response);
    }

    private static JsonObject params(Args args) throws ToolFailure {
        JsonElement raw = args.get("params");
        if (raw == null) {
            JsonObject rest = args.json().deepCopy();
            rest.remove("action");
            rest.remove("params");
            return rest;
        }
        if (raw.isJsonObject()) {
            return raw.getAsJsonObject().deepCopy();
        }
        JsonObject parsed = raw.isJsonPrimitive() ? Jsons.parseObject(raw.getAsString()) : null;
        if (parsed == null) {
            throw new ToolFailure("params 要是一个对象，比如 {\"group_id\": 123456}");
        }
        return parsed;
    }

    private void checkFiles(JsonElement element, String key, List<String> locals, boolean ids) throws ToolFailure {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                if (isString(e.getValue())) {
                    e.setValue(new JsonPrimitive(checkValue(e.getKey(), e.getValue().getAsString(), locals, ids)));
                } else {
                    checkFiles(e.getValue(), e.getKey(), locals, ids);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (isString(array.get(i))) {
                    array.set(i, new JsonPrimitive(checkValue(key, array.get(i).getAsString(), locals, ids)));
                } else {
                    checkFiles(array.get(i), key, locals, ids);
                }
            }
        }
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private String checkValue(String key, String value, List<String> locals, boolean ids) throws ToolFailure {
        if (value.toLowerCase(Locale.ROOT).contains("[cq:")) {
            throw new ToolFailure("参数里别写 CQ 码，消息用消息段数组写，比如 [{\"type\":\"text\",\"data\":{\"text\":\"你好\"}}]");
        }
        if (key.equals("url")) {
            PublicWeb.requirePublic(value);
            return value;
        }
        if (!FILE_KEYS.contains(key)) {
            return value;
        }
        if (ids && !value.strip().toLowerCase(Locale.ROOT).matches("^(base64|https?)://.*")) {
            if (LOCAL_PATH.matcher(value.strip()).find()) {
                throw new ToolFailure("这个接口的文件参数填消息里的文件名或编号（比如 3F2A….jpg），不能填电脑上的路径");
            }
            return value.strip();
        }
        String safe = safeFile(value);
        if (!value.strip().toLowerCase(Locale.ROOT).matches("^(base64|https?)://.*")) {
            locals.add(safe);
        }
        return safe;
    }

    private String safeFile(String value) throws ToolFailure {
        String v = value.strip();
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.startsWith("base64://")) {
            return v;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            PublicWeb.requirePublic(v);
            return v;
        }
        if (lower.matches("^[a-z][a-z0-9+.-]+://.*") && !lower.startsWith("file://")) {
            throw new ToolFailure("文件只能用公网网址、base64:// 或工作目录里的文件：" + abbreviate(v, 80));
        }
        Workspace workspace = agent.workspaces().workspace();
        if (workspace == null) {
            throw new ToolFailure("没有设工作目录，文件只能用公网网址或 base64://：" + abbreviate(v, 80));
        }
        String path = v;
        if (lower.startsWith("file://")) {
            try {
                path = Path.of(URI.create(v)).toString();
            } catch (IllegalArgumentException e) {
                throw new ToolFailure("文件地址不对：" + abbreviate(v, 80));
            }
        }
        return outbox.reachable(workspace.existingFile(path));
    }

    private void protect(String name, JsonObject params) throws ToolFailure {
        if (name.equals("set_group_kick_members")) {
            JsonElement users = params.get("user_id");
            for (JsonElement u : users != null && users.isJsonArray() ? users.getAsJsonArray() : new JsonArray()) {
                guard(u.isJsonPrimitive() ? u.getAsString() : "");
            }
            if (users != null && !users.isJsonArray()) {
                guard(Jsons.optString(params, "user_id"));
            }
        } else if (name.equals("set_group_admin") && !bool(params, "enable", true)) {
            guard(Jsons.optString(params, "user_id"));
        } else if (name.equals("delete_msg") && agent.personas().owners().contains(actions.senderOf(Jsons.optString(params, "message_id")))) {
            throw new ToolFailure("这是主人发的消息，不能撤回");
        }
    }

    private void guard(String userId) throws ToolFailure {
        if (agent.personas().owners().contains(userId.strip())) {
            throw new ToolFailure("这是你的主人，不能对主人这么做");
        }
        if (userId.strip().equals(outbox.selfId())) {
            throw new ToolFailure("不能对你自己这么做");
        }
    }

    private void requireOwner(Action action, ToolContext context) throws ToolFailure {
        String who = context.userId() == null ? "" : context.userId().strip();
        String session = context.session() == null ? "" : context.session();
        if (session.startsWith("web:") || session.equals("console")) {
            return;
        }
        // 没有明确触发者的回合（她自己决定做事、内心回合等）qqUser 是 "0"，
        // 这种情况在私聊里能确定就是对方本人 —— 从会话名反推，别再一律拒绝。
        if (who.isEmpty() || "0".equals(who)) {
            if (session.startsWith("private:")) {
                String peer = session.substring("private:".length()).strip();
                if (!peer.isEmpty() && !"0".equals(peer)) {
                    who = peer;
                }
            }
        }
        if (who.isEmpty() || "0".equals(who)) {
            throw new ToolFailure(action.name() + "（" + action.summary() + "）会改动东西，"
                    + "但这个回合拿不到发起者的 QQ 号，为安全起见不执行");
        }
        if (!agent.personas().owners().contains(who)) {
            throw new ToolFailure(action.name() + "（" + action.summary() + "）会改动东西，"
                    + "只有主人能让我调这类接口（现在是 " + who + " 在要求）");
        }
    }

    private synchronized void throttle() throws ToolFailure {
        long now = System.currentTimeMillis();
        while (!writes.isEmpty() && now - writes.peekFirst() > 60_000) {
            writes.removeFirst();
        }
        if (writes.size() >= WRITES_PER_MINUTE) {
            throw new ToolFailure("一分钟内已经调了 " + WRITES_PER_MINUTE + " 次会改动东西的 QQ 接口，歇一会儿再说");
        }
        writes.addLast(now);
    }

    private String route(Action action, JsonObject params, ToolContext context) throws ToolFailure {
        String name = action.name();
        if (name.equals("set_group_ban")) {
            long seconds = number(params, "duration", 30 * 60);
            return actions.mute(id(params, "group_id"), id(params, "user_id"), seconds <= 0 ? 0 : (int) Math.max(1, (seconds + 59) / 60));
        }
        if (name.equals("set_group_kick")) {
            return actions.kick(id(params, "group_id"), id(params, "user_id"), bool(params, "reject_add_request", false));
        }
        if (name.equals("delete_friend")) {
            return actions.deleteFriend(params.has("user_id") ? id(params, "user_id") : id(params, "friend_id"));
        }
        if (POKE.contains(name)) {
            String groupId = Jsons.optString(params, "group_id").strip();
            String target = Jsons.optString(params, "target_id").isBlank() ? id(params, "user_id") : id(params, "target_id");
            String session = groupId.isEmpty() ? "private:" + id(params, "user_id") : "group:" + id(params, "group_id");
            String who = bridge.poke(session, target);
            agent.record(session, ChatMessage.assistant("<拍一拍>" + who + "</拍一拍>", ""));
            return "拍了拍 " + who;
        }
        if (SEND.contains(name)) {
            return send(name, params, context);
        }
        return null;
    }

    private String send(String name, JsonObject params, ToolContext context) throws ToolFailure {
        String text = plainText(params.get("message"));
        if (text == null) {
            return null;
        }
        boolean group = name.equals("send_group_msg") || name.equals("send_msg")
                && (Jsons.optString(params, "message_type").equals("group") || Jsons.optString(params, "message_type").isEmpty() && params.has("group_id"));
        if (group) {
            String groupId = id(params, "group_id");
            return "在「" + bridge.groupName(groupId) + "」发了：" + bridge.speak(groupId, text, context == null ? "" : context.session());
        }
        String userId = id(params, "user_id");
        if (contacts.canMessage(userId)) {
            return "私聊 QQ " + userId + " 发了：" + bridge.speakPrivate(userId, text);
        }
        return actions.messageStranger(userId, Jsons.optString(params, "group_id").strip(), text);
    }

    private static String plainText(JsonElement message) {
        if (message == null || message.isJsonNull()) {
            return null;
        }
        if (message.isJsonPrimitive()) {
            return message.getAsString();
        }
        JsonArray segments = message.isJsonArray() ? message.getAsJsonArray() : new JsonArray();
        if (message.isJsonObject()) {
            segments.add(message);
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement s : segments) {
            JsonObject segment = s.isJsonObject() ? s.getAsJsonObject() : null;
            JsonObject data = segment == null ? null : Jsons.optObject(segment, "data");
            if (data == null || !Jsons.optString(segment, "type").equals("text")) {
                return null;
            }
            text.append(Jsons.optString(data, "text"));
        }
        return text.isEmpty() ? null : text.toString();
    }

    private void review(JsonObject params) throws ToolFailure {
        List<String> texts = new ArrayList<>();
        collectText(params, "", texts);
        String joined = String.join("\n", texts).strip();
        if (joined.codePoints().filter(Character::isLetter).count() >= 2) {
            outbox.review("", joined);
        }
    }

    private static void collectText(JsonElement element, String key, List<String> out) {
        if (element == null || element.isJsonNull() || NOT_TEXT.matcher(key).matches()) {
            return;
        }
        if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(e -> collectText(e.getValue(), e.getKey(), out));
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(e -> collectText(e, key, out));
        } else if (element.getAsJsonPrimitive().isString()) {
            out.add(element.getAsString());
        }
    }

    private void noteDone(Action action, JsonObject params) {
        String groupId = Jsons.optString(params, "group_id").strip();
        if (groupId.matches("\\d{5,12}")) {
            actions.note(groupId, "（你用 QQ 接口「" + action.summary() + "」（" + action.name() + "）：" + abbreviate(params.toString(), 160) + "）");
        }
    }

    private static String render(Action action, JsonObject response) {
        JsonElement data = response == null ? null : response.get("data");
        if (data == null || data.isJsonNull() || data.isJsonObject() && data.getAsJsonObject().isEmpty()) {
            return action.summary() + "：成功了。";
        }
        return action.summary() + "：成功了，返回：\n" + abbreviate(trim(data).toString(), MAX_OUTPUT);
    }

    private static JsonElement trim(JsonElement element) {
        if (element.isJsonArray()) {
            JsonArray in = element.getAsJsonArray();
            JsonArray out = new JsonArray();
            for (int i = 0; i < Math.min(in.size(), MAX_ITEMS); i++) {
                out.add(trim(in.get(i)));
            }
            if (in.size() > MAX_ITEMS) {
                out.add("…一共 " + in.size() + " 项，只列了前 " + MAX_ITEMS + " 项");
            }
            return out;
        }
        if (element.isJsonObject()) {
            JsonObject out = new JsonObject();
            element.getAsJsonObject().entrySet().forEach(e -> out.add(e.getKey(), trim(e.getValue())));
            return out;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() && element.getAsString().length() > MAX_STRING) {
            return new JsonPrimitive(element.getAsString().substring(0, MAX_STRING) + "…");
        }
        return element;
    }

    private static String id(JsonObject params, String key) throws ToolFailure {
        String value = Jsons.optString(params, key).replace("group:", "").replace("user:", "").strip();
        if (!value.matches("\\d{5,12}")) {
            throw new ToolFailure(key + " 不对：" + value);
        }
        return value;
    }

    private static boolean bool(JsonObject params, String key, boolean fallback) {
        String value = Jsons.optString(params, key).strip();
        return value.isEmpty() ? fallback : value.equalsIgnoreCase("true");
    }

    private static long number(JsonObject params, String key, long fallback) {
        try {
            String value = Jsons.optString(params, key).strip();
            return value.isEmpty() ? fallback : Math.round(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
