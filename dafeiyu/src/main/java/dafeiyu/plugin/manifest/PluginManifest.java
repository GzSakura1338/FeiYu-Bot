package dafeiyu.plugin.manifest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record PluginManifest(String id, String name, String version, String author, String description, String host,
                             String command, List<String> args, Map<String, String> env, Set<Permission> permissions,
                             List<Command> commands, Set<String> events, boolean intercept, int priority, List<Tool> tools,
                             int toolTimeout, List<Schedule> schedules, boolean prompt, boolean filter, JsonObject settings,
                             JsonObject raw) {

    public record Command(String name, List<String> aliases, String description, Set<String> scope) {

        public boolean matches(String word) {
            return name.equalsIgnoreCase(word) || aliases.stream().anyMatch(a -> a.equalsIgnoreCase(word));
        }
    }

    public record Tool(String name, String description, JsonObject parameters, boolean mind, int timeout) {}

    public record Schedule(String id, String cron, String description, Cron parsed) {}

    public static final String FILE = "plugin.json";
    public static final int MAX_TOOLS = 20;
    public static final int MAX_TOOL_DESCRIPTION = 1000;
    public static final int MAX_TOOL_TIMEOUT = 600;
    public static final int DEFAULT_TOOL_TIMEOUT = 60;
    public static final Set<String> EVENTS = Set.of("message.group", "message.private", "notice.poke", "notice.join", "notice.leave",
            "notice.recall", "bot.said", "bot.thought", "bot.connected", "bot.disconnected");
    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{1,39}");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?");
    private static final Pattern HOST = Pattern.compile("(>=|>|=|\\^)?\\s*(\\d+\\.\\d+\\.\\d+)");
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern COMMAND_NAME = Pattern.compile("[^\\s#/]{1,20}");

    public static final class Invalid extends Exception {
        private final List<String> problems;

        public Invalid(List<String> problems) {
            super(String.join("；", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    public static PluginManifest read(Path folder) throws Invalid {
        Path file = folder.resolve(FILE);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Invalid(List.of("读不到 " + FILE + "：" + e.getMessage()));
        }
        return parse(text, folder.getFileName().toString());
    }

    public static PluginManifest parse(String text, String folderName) throws Invalid {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw new Invalid(List.of(FILE + " 必须是一个 JSON 对象"));
            }
            json = element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new Invalid(List.of(FILE + " 不是合法的 JSON：" + rootMessage(e)));
        }
        List<String> problems = new ArrayList<>();
        String id = string(json, "id", problems, true);
        if (!id.isEmpty() && !ID.matcher(id).matches()) {
            problems.add("id「" + id + "」只能用小写字母、数字、- 和 _，2 到 40 个字符");
        }
        if (folderName != null && !id.isEmpty() && !id.equals(folderName)) {
            problems.add("id「" + id + "」和文件夹名「" + folderName + "」不一致");
        }
        String name = string(json, "name", problems, true);
        if (name.length() > 40) {
            problems.add("name 太长（最多 40 个字）");
        }
        String version = string(json, "version", problems, true);
        if (!version.isEmpty() && !VERSION.matcher(version).matches()) {
            problems.add("version「" + version + "」要写成 1.2.3 这样的格式");
        }
        String author = string(json, "author", problems, false);
        String description = string(json, "description", problems, false);
        if (description.length() > 300) {
            problems.add("description 太长（最多 300 个字）");
        }
        String host = string(json, "host", problems, false);
        if (!host.isEmpty() && !HOST.matcher(host).matches()) {
            problems.add("host「" + host + "」要写成 >=0.2.0 这样的格式");
        }
        String command = "";
        List<String> args = new ArrayList<>();
        Map<String, String> env = new LinkedHashMap<>();
        if (!json.has("run") || !json.get("run").isJsonObject()) {
            problems.add("缺少 run（怎么启动插件，例如 {\"command\": \"python\", \"args\": [\"main.py\"]}）");
        } else {
            JsonObject run = json.getAsJsonObject("run");
            command = string(run, "command", problems, true, "run.command");
            for (String arg : strings(run, "args", problems, "run.args")) {
                args.add(arg);
            }
            if (run.has("env")) {
                if (!run.get("env").isJsonObject()) {
                    problems.add("run.env 必须是对象");
                } else {
                    for (Map.Entry<String, JsonElement> e : run.getAsJsonObject("env").entrySet()) {
                        if (!e.getValue().isJsonPrimitive()) {
                            problems.add("run.env." + e.getKey() + " 必须是字符串");
                        } else {
                            env.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                }
            }
        }
        Set<Permission> permissions = new LinkedHashSet<>();
        for (String key : strings(json, "permissions", problems, "permissions")) {
            Permission.of(key).ifPresentOrElse(permissions::add, () -> problems.add("不认识的权限「" + key + "」"));
        }
        List<Command> commands = new ArrayList<>();
        Set<String> commandWords = new HashSet<>();
        for (JsonObject c : objects(json, "commands", problems)) {
            String cname = string(c, "name", problems, true, "commands[].name");
            if (!cname.isEmpty() && !COMMAND_NAME.matcher(cname).matches()) {
                problems.add("命令名「" + cname + "」不能有空格、# 或 /，最多 20 个字");
            }
            List<String> aliases = strings(c, "aliases", problems, "commands[].aliases");
            for (String word : concat(cname, aliases)) {
                if (!word.isEmpty() && !commandWords.add(word.toLowerCase())) {
                    problems.add("命令「" + word + "」重复了");
                }
            }
            Set<String> scope = new LinkedHashSet<>(strings(c, "scope", problems, "commands[].scope"));
            if (scope.isEmpty()) {
                scope.addAll(List.of("group", "private"));
            }
            for (String s : scope) {
                if (!s.equals("group") && !s.equals("private")) {
                    problems.add("命令「" + cname + "」的 scope 只能是 group 或 private");
                }
            }
            commands.add(new Command(cname, List.copyOf(aliases), string(c, "description", problems, false), Set.copyOf(scope)));
        }
        Set<String> events = new LinkedHashSet<>();
        for (String event : strings(json, "events", problems, "events")) {
            if (!EVENTS.contains(event)) {
                problems.add("不认识的事件「" + event + "」");
            }
            events.add(event);
        }
        boolean intercept = bool(json, "intercept");
        if (intercept && events.stream().noneMatch(e -> e.startsWith("message."))) {
            problems.add("intercept 为 true 时，events 里要订阅 message.group 或 message.private");
        }
        if (events.contains("bot.thought") && !permissions.contains(Permission.MIND_READ)) {
            problems.add("订阅 bot.thought 需要 mind.read 权限");
        }
        int priority = integer(json, "priority", 100, problems);
        int toolTimeout = integer(json, "toolTimeout", DEFAULT_TOOL_TIMEOUT, problems);
        if (toolTimeout < 1 || toolTimeout > MAX_TOOL_TIMEOUT) {
            problems.add("toolTimeout 要在 1 到 " + MAX_TOOL_TIMEOUT + " 秒之间");
        }
        List<Tool> tools = tools(objects(json, "tools", problems), toolTimeout, problems);
        List<Schedule> schedules = new ArrayList<>();
        Set<String> scheduleIds = new HashSet<>();
        for (JsonObject s : objects(json, "schedules", problems)) {
            String sid = string(s, "id", problems, true, "schedules[].id");
            if (!sid.isEmpty() && !scheduleIds.add(sid)) {
                problems.add("定时任务 id「" + sid + "」重复了");
            }
            String cron = string(s, "cron", problems, true, "schedules[].cron");
            Cron parsed = null;
            if (!cron.isEmpty()) {
                try {
                    parsed = Cron.parse(cron);
                } catch (IllegalArgumentException e) {
                    problems.add("定时任务「" + sid + "」的 cron「" + cron + "」不对：" + e.getMessage());
                }
            }
            schedules.add(new Schedule(sid, cron, string(s, "description", problems, false), parsed));
        }
        boolean prompt = bool(json, "prompt");
        boolean filter = bool(json, "filter");
        if (filter && !permissions.contains(Permission.QQ_FILTER)) {
            problems.add("filter 为 true 时需要 qq.filter 权限");
        }
        JsonObject settings = new JsonObject();
        if (json.has("settings")) {
            if (!json.get("settings").isJsonObject()) {
                problems.add("settings 必须是 JSON Schema 对象");
            } else {
                settings = json.getAsJsonObject("settings");
                problems.addAll(SettingsSchema.check(settings));
            }
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new PluginManifest(id, name, version, author, description, host, command, List.copyOf(args), Map.copyOf(env),
                Set.copyOf(permissions), List.copyOf(commands), Set.copyOf(events), intercept, priority, List.copyOf(tools), toolTimeout,
                List.copyOf(schedules), prompt, filter, settings, json);
    }

    public static List<Tool> tools(List<JsonObject> list, int defaultTimeout, List<String> problems) {
        List<Tool> tools = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (list.size() > MAX_TOOLS) {
            problems.add("工具太多了（" + list.size() + " 个），一个插件最多 " + MAX_TOOLS + " 个");
            return tools;
        }
        for (JsonObject t : list) {
            String tname = string(t, "name", problems, true, "tools[].name");
            if (!tname.isEmpty() && !TOOL_NAME.matcher(tname).matches()) {
                problems.add("工具名「" + tname + "」只能用字母、数字、- 和 _，最多 40 个字符");
            }
            if (!tname.isEmpty() && !names.add(tname)) {
                problems.add("工具名「" + tname + "」重复了");
            }
            String tdesc = string(t, "description", problems, true, "tools[].description");
            if (tdesc.length() > MAX_TOOL_DESCRIPTION) {
                problems.add("工具「" + tname + "」的说明太长（" + tdesc.length() + " 字，最多 " + MAX_TOOL_DESCRIPTION + "）");
            }
            JsonObject parameters = t.has("parameters") && t.get("parameters").isJsonObject() ? t.getAsJsonObject("parameters") : emptySchema();
            if (!parameters.has("type")) {
                parameters = parameters.deepCopy();
                parameters.addProperty("type", "object");
            }
            if (parameters.toString().length() > 8000) {
                problems.add("工具「" + tname + "」的参数定义太长");
            }
            int timeout = integer(t, "timeout", defaultTimeout, problems);
            if (timeout < 1 || timeout > MAX_TOOL_TIMEOUT) {
                problems.add("工具「" + tname + "」的 timeout 要在 1 到 " + MAX_TOOL_TIMEOUT + " 秒之间");
            }
            tools.add(new Tool(tname, tdesc, parameters, bool(t, "mind"), timeout));
        }
        return tools;
    }

    public boolean hostSatisfied(String hostVersion) {
        if (host.isEmpty()) {
            return true;
        }
        var m = HOST.matcher(host.strip());
        if (!m.matches()) {
            return false;
        }
        String op = m.group(1) == null ? ">=" : m.group(1);
        int cmp = PluginVersion.parse(hostVersion).compareTo(PluginVersion.parse(m.group(2)));
        return switch (op) {
            case ">" -> cmp > 0;
            case "=" -> cmp == 0;
            case "^" -> cmp >= 0 && PluginVersion.parse(hostVersion).major() == PluginVersion.parse(m.group(2)).major();
            default -> cmp >= 0;
        };
    }

    public boolean subscribes(String event) {
        return events.contains(event);
    }

    private static JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    private static List<String> concat(String first, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private static String string(JsonObject json, String key, List<String> problems, boolean required) {
        return string(json, key, problems, required, key);
    }

    private static String string(JsonObject json, String key, List<String> problems, boolean required, String label) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            if (required) {
                problems.add("缺少 " + label);
            }
            return "";
        }
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            problems.add(label + " 必须是字符串");
            return "";
        }
        String text = value.getAsString().strip();
        if (required && text.isEmpty()) {
            problems.add(label + " 不能是空的");
        }
        return text;
    }

    private static List<String> strings(JsonObject json, String key, List<String> problems, String label) {
        List<String> out = new ArrayList<>();
        if (!json.has(key)) {
            return out;
        }
        if (!json.get(key).isJsonArray()) {
            problems.add(label + " 必须是数组");
            return out;
        }
        for (JsonElement e : json.getAsJsonArray(key)) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                problems.add(label + " 里只能放字符串");
            } else {
                out.add(e.getAsString().strip());
            }
        }
        return out;
    }

    private static List<JsonObject> objects(JsonObject json, String key, List<String> problems) {
        List<JsonObject> out = new ArrayList<>();
        if (!json.has(key)) {
            return out;
        }
        if (!json.get(key).isJsonArray()) {
            problems.add(key + " 必须是数组");
            return out;
        }
        JsonArray array = json.getAsJsonArray(key);
        for (JsonElement e : array) {
            if (!e.isJsonObject()) {
                problems.add(key + " 里只能放对象");
            } else {
                out.add(e.getAsJsonObject());
            }
        }
        return out;
    }

    private static int integer(JsonObject json, String key, int fallback, List<String> problems) {
        if (!json.has(key)) {
            return fallback;
        }
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            problems.add(key + " 必须是数字");
            return fallback;
        }
        return value.getAsInt();
    }

    private static boolean bool(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isBoolean() && json.get(key).getAsBoolean();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? e.toString() : t.getMessage();
    }
}
