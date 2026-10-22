package dafeiyu.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.Extensions;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.qq.MessageGate;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.plugin.api.HostApi;
import dafeiyu.plugin.manifest.ArgsCheck;
import dafeiyu.plugin.manifest.Permission;
import dafeiyu.plugin.manifest.PluginManifest;
import dafeiyu.plugin.mcp.McpManager;
import dafeiyu.plugin.rpc.ToolResults;
import dafeiyu.plugin.store.PluginStore;
import dafeiyu.util.Jsons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class PluginDispatcher implements MessageGate, Extensions {

    static final String TOOL_PREFIX = "plugin__";
    static final int MAX_PROMPT = 300;
    static final long COMMAND_TIMEOUT_MS = 5_000;
    static final long INTERCEPT_TIMEOUT_MS = 2_000;
    static final long FILTER_TIMEOUT_MS = 2_000;
    static final long PROMPT_TIMEOUT_MS = 500;
    static final long CONTEXT_TTL_MS = 10 * 60_000;
    static final int REPLIES_PER_CONTEXT = 5;

    private record ReplyContext(String pluginId, String session, String messageId, long expires, int[] remaining, boolean[] cancelled) {}

    private final PluginHost host;
    private final ChatAgent agent;
    private final PluginStore store;
    private final Map<String, ReplyContext> contexts = new ConcurrentHashMap<>();
    private volatile Map<String, String> commandOwners = Map.of();
    private volatile Map<String, PluginManifest.Command> commands = Map.of();
    private volatile List<ToolInfo> catalog = List.of();

    PluginDispatcher(PluginHost host, ChatAgent agent, PluginStore store) {
        this.host = host;
        this.agent = agent;
        this.store = store;
    }

    void rebuild() {
        Map<String, PluginManifest.Command> table = new HashMap<>();
        Map<String, String> owners = new HashMap<>();
        List<ToolInfo> tools = new ArrayList<>();
        for (PluginSlot slot : host.sorted()) {
            slot.warnings.removeIf(w -> w.startsWith("命令"));
            PluginManifest m = slot.manifest;
            if (m == null || !slot.running()) {
                continue;
            }
            for (PluginManifest.Command c : m.commands()) {
                List<String> words = new ArrayList<>(c.aliases());
                words.addFirst(c.name());
                for (String word : words) {
                    String key = word.toLowerCase();
                    String holder = owners.get(key);
                    if (holder != null && !holder.equals(slot.id)) {
                        slot.warnings.add("命令「" + word + "」和插件「" + holder + "」冲突，这个插件的没有生效");
                        continue;
                    }
                    owners.put(key, slot.id);
                    table.put(key, c);
                }
            }
            for (PluginManifest.Tool t : slot.tools) {
                tools.add(new ToolInfo(toolName(slot.id, t.name()), slot.name() + " · " + t.name(), t.description(), "plugin:" + slot.id,
                        store.qqTools(slot.id)));
            }
        }
        McpManager mcp = host.mcp();
        if (mcp != null) {
            tools.addAll(mcp.catalog());
        }
        commandOwners = Map.copyOf(owners);
        commands = Map.copyOf(table);
        catalog = List.copyOf(tools);
    }

    static String toolName(String pluginId, String tool) {
        return TOOL_PREFIX + pluginId + "__" + tool;
    }

    String newContext(PluginSlot slot, String session, String messageId) {
        String token = UUID.randomUUID().toString();
        contexts.put(token, new ReplyContext(slot.id, session, messageId, System.currentTimeMillis() + CONTEXT_TTL_MS,
                new int[] {REPLIES_PER_CONTEXT}, new boolean[] {false}));
        return token;
    }

    HostApi.ReplyTarget useReply(String pluginId, String token) {
        ReplyContext c = token == null ? null : contexts.get(token);
        if (c == null || !c.pluginId().equals(pluginId) || c.expires() < System.currentTimeMillis() || c.cancelled()[0]) {
            return null;
        }
        synchronized (c) {
            if (c.remaining()[0] <= 0) {
                return null;
            }
            c.remaining()[0]--;
        }
        return new HostApi.ReplyTarget(c.session(), c.messageId());
    }

    void forget(String pluginId) {
        contexts.values().removeIf(c -> c.pluginId().equals(pluginId));
    }

    void expire() {
        long now = System.currentTimeMillis();
        contexts.values().removeIf(c -> c.expires() < now);
    }

    private void cancel(String token) {
        ReplyContext c = contexts.get(token);
        if (c != null) {
            c.cancelled()[0] = true;
        }
    }

    @Override
    public Verdict incoming(Message m) {
        Verdict verdict = Verdict.PASS;
        if (m.live()) {
            String[] owner = new String[1];
            Verdict command = command(m, owner);
            verdict = command != null ? command : intercept(m, owner[0]);
        }
        for (PluginSlot slot : subscribers("message." + m.type())) {
            if (m.live() && slot.manifest.intercept()) {
                continue;
            }
            slot.process.notify("event", messagePayload(m, m.text(), newContext(slot, m.session(), m.messageId())));
        }
        return verdict;
    }

    private Verdict intercept(Message m, String skip) {
        Verdict verdict = Verdict.PASS;
        String text = m.text();
        for (PluginSlot slot : subscribers("message." + m.type())) {
            if (!slot.manifest.intercept() || slot.id.equals(skip)) {
                continue;
            }
            JsonObject params = messagePayload(m, text, newContext(slot, m.session(), m.messageId()));
            try {
                JsonObject o = object(slot.process.call("event", params, INTERCEPT_TIMEOUT_MS)
                        .get(INTERCEPT_TIMEOUT_MS + 200, TimeUnit.MILLISECONDS));
                String action = Jsons.optString(o, "action");
                if (action.equals("drop")) {
                    host.pluginLog(slot, "info", "吞掉了一条消息，她不会回这条");
                    return new Verdict(true, null, slot.name());
                }
                String rewritten = Jsons.optString(o, "text");
                if (action.equals("rewrite") && !rewritten.isBlank()) {
                    text = rewritten;
                    verdict = new Verdict(false, text, slot.name());
                }
            } catch (TimeoutException e) {
                host.pluginLog(slot, "warn", "拦截处理超过 " + INTERCEPT_TIMEOUT_MS / 1000 + " 秒，按放行处理");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                host.pluginLog(slot, "warn", "拦截处理出错，按放行处理：" + PluginHost.describe(e));
            }
        }
        return verdict;
    }

    private Verdict command(Message m, String[] owner) {
        String prefix = store.prefix();
        String body = m.text().strip();
        while (body.startsWith("@你")) {
            body = body.substring(2).strip();
        }
        if (!body.startsWith(prefix)) {
            return null;
        }
        String rest = body.substring(prefix.length()).strip();
        if (rest.isEmpty()) {
            return null;
        }
        String word = rest.split("\\s+", 2)[0];
        String args = rest.substring(word.length()).strip();
        String ownerId = commandOwners.get(word.toLowerCase());
        PluginManifest.Command c = commands.get(word.toLowerCase());
        PluginSlot slot = ownerId == null ? null : host.slot(ownerId);
        if (c == null || slot == null || !slot.running() || !c.scope().contains(m.type())) {
            return null;
        }
        owner[0] = slot.id;
        String ctx = newContext(slot, m.session(), m.messageId());
        JsonObject params = messagePayload(m, m.text(), ctx);
        params.addProperty("name", c.name());
        params.addProperty("word", word);
        params.addProperty("args", args);
        JsonArray argv = new JsonArray();
        if (!args.isEmpty()) {
            for (String a : args.split("\\s+")) {
                argv.add(a);
            }
        }
        params.add("argv", argv);
        try {
            JsonElement r = slot.process.call("command/run", params, COMMAND_TIMEOUT_MS).get(COMMAND_TIMEOUT_MS + 200, TimeUnit.MILLISECONDS);
            JsonObject o = object(r);
            boolean handled = !o.has("handled") || !o.get("handled").isJsonPrimitive() || o.get("handled").getAsBoolean();
            if (!handled) {
                cancel(ctx);
                return null;
            }
            host.pluginLog(slot, "info", "处理了命令 " + prefix + word);
            return new Verdict(true, null, slot.name());
        } catch (TimeoutException e) {
            cancel(ctx);
            host.pluginLog(slot, "warn", "命令 " + prefix + word + " 超过 " + COMMAND_TIMEOUT_MS / 1000 + " 秒没处理完，交给她回复");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            cancel(ctx);
            host.pluginLog(slot, "warn", "命令处理出错，交给她回复：" + PluginHost.describe(e));
            return null;
        }
    }

    private JsonObject messagePayload(Message m, String text, String ctx) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "message." + m.type());
        p.addProperty("session", m.session());
        p.addProperty("groupId", m.groupId());
        p.addProperty("userId", m.userId());
        p.addProperty("sender", m.sender());
        p.addProperty("owner", m.owner());
        p.addProperty("text", text);
        p.add("attachments", array(m.attachments()));
        p.addProperty("mentionedMe", m.mentionedMe());
        p.addProperty("messageId", m.messageId());
        p.addProperty("time", m.time());
        p.addProperty("backfilled", !m.live());
        p.addProperty("ctx", ctx);
        return p;
    }

    private static JsonArray array(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonArray();
        }
        try {
            JsonElement e = JsonParser.parseString(raw);
            return e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
        } catch (RuntimeException e) {
            return new JsonArray();
        }
    }

    private static JsonObject object(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private List<PluginSlot> subscribers(String event) {
        return host.sorted().stream().filter(PluginSlot::running).filter(s -> s.manifest.subscribes(event))
                .sorted(Comparator.comparingInt((PluginSlot s) -> s.manifest.priority()).thenComparing(s -> s.id)).toList();
    }

    void broadcast(String event, JsonObject payload) {
        for (PluginSlot slot : subscribers(event)) {
            if (event.equals("bot.thought") && !store.approved(slot.id).contains(Permission.MIND_READ)) {
                continue;
            }
            JsonObject p = payload.deepCopy();
            p.addProperty("type", event);
            if (p.has("session") && !p.has("ctx")) {
                p.addProperty("ctx", newContext(slot, p.get("session").getAsString(), Jsons.optString(p, "messageId")));
            }
            slot.process.notify("event", p);
        }
    }

    void notice(JsonObject event) {
        if (!Jsons.optString(event, "post_type").equals("notice")) {
            return;
        }
        String sub = Jsons.optString(event, "sub_type");
        String name = switch (Jsons.optString(event, "notice_type")) {
            case "group_increase" -> "notice.join";
            case "group_decrease" -> "notice.leave";
            case "group_recall", "friend_recall" -> "notice.recall";
            case "notify" -> sub.equals("poke") ? "notice.poke" : null;
            default -> null;
        };
        if (name == null) {
            return;
        }
        String groupId = Jsons.optString(event, "group_id");
        String userId = Jsons.optString(event, "user_id");
        JsonObject p = new JsonObject();
        p.addProperty("groupId", groupId);
        p.addProperty("userId", userId);
        p.addProperty("operatorId", Jsons.optString(event, "operator_id"));
        p.addProperty("targetId", Jsons.optString(event, "target_id"));
        p.addProperty("messageId", Jsons.optString(event, "message_id"));
        p.addProperty("subType", sub);
        p.addProperty("session", groupId.isEmpty() || groupId.equals("0") ? "private:" + userId : "group:" + groupId);
        p.addProperty("time", System.currentTimeMillis());
        broadcast(name, p);
    }

    @Override
    public String outgoing(String session, String text) throws ToolFailure {
        String current = text;
        List<PluginSlot> filters = host.sorted().stream().filter(PluginSlot::running).filter(s -> s.manifest.filter())
                .filter(s -> store.approved(s.id).contains(Permission.QQ_FILTER))
                .sorted(Comparator.comparingInt((PluginSlot s) -> s.manifest.priority()).thenComparing(s -> s.id)).toList();
        for (PluginSlot slot : filters) {
            JsonObject params = new JsonObject();
            params.addProperty("session", session);
            params.addProperty("text", current);
            JsonObject o;
            try {
                o = object(slot.process.call("filter/outgoing", params, FILTER_TIMEOUT_MS).get(FILTER_TIMEOUT_MS + 200, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                host.pluginLog(slot, "warn", "出站过滤超过 " + FILTER_TIMEOUT_MS / 1000 + " 秒，按放行处理");
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                host.pluginLog(slot, "warn", "出站过滤出错，按放行处理：" + PluginHost.describe(e));
                continue;
            }
            String action = Jsons.optString(o, "action");
            if (action.equals("drop")) {
                host.pluginLog(slot, "info", "拦下了她要发的一句话");
                host.appLog().info("plugins: " + slot.id + " blocked an outgoing message in " + session);
                note(session, "（你刚才要发的一句话被插件「" + slot.name() + "」拦下了，没有发出去）");
                throw new ToolFailure("这句话被插件「" + slot.name() + "」拦下了");
            }
            String rewritten = Jsons.optString(o, "text");
            if (action.equals("rewrite") && !rewritten.isBlank()) {
                ContentGuard.Verdict v = agent.guard().review(agent.store().active(), "", rewritten);
                if (v != null) {
                    host.pluginLog(slot, "warn", "改写后的内容没通过内容审核（" + v.category().label() + "），没有发");
                    note(session, "（你刚才要发的一句话被插件「" + slot.name() + "」改写后没通过内容审核，没有发出去）");
                    throw new ToolFailure("插件「" + slot.name() + "」改写后的内容没通过内容审核");
                }
                current = rewritten;
            }
        }
        return current;
    }

    private void note(String session, String text) {
        try {
            agent.hear(session, text);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void said(String session, String text) {
        JsonObject p = new JsonObject();
        p.addProperty("session", session);
        p.addProperty("text", text);
        p.addProperty("time", System.currentTimeMillis());
        broadcast("bot.said", p);
    }

    @Override
    public void thought(String text) {
        JsonObject p = new JsonObject();
        p.addProperty("text", text);
        p.addProperty("time", System.currentTimeMillis());
        broadcast("bot.thought", p);
    }

    @Override
    public List<ToolSpec> tools(boolean qq, boolean mind, Set<String> enabled) {
        List<ToolSpec> out = new ArrayList<>();
        for (PluginSlot slot : host.sorted()) {
            if (!slot.running() || (qq && !store.qqTools(slot.id))) {
                continue;
            }
            for (PluginManifest.Tool t : slot.tools) {
                String name = toolName(slot.id, t.name());
                if (enabled.contains(name) && (!mind || t.mind())) {
                    out.add(new ToolSpec(name, "[插件 " + slot.name() + "] " + t.description(), t.parameters().deepCopy()));
                }
            }
        }
        McpManager mcp = host.mcp();
        if (mcp != null) {
            out.addAll(mcp.tools(qq, mind, enabled));
        }
        return out;
    }

    @Override
    public boolean owns(String toolName) {
        return toolName.startsWith(TOOL_PREFIX) || toolName.startsWith(McpManager.PREFIX);
    }

    @Override
    public Toolbox.Outcome run(ToolCall call, String session) {
        if (call.name().startsWith(McpManager.PREFIX)) {
            McpManager mcp = host.mcp();
            return mcp == null ? new Toolbox.Outcome("MCP 没有启用", true) : mcp.run(call);
        }
        String rest = call.name().substring(TOOL_PREFIX.length());
        int cut = rest.indexOf("__");
        if (cut <= 0) {
            return new Toolbox.Outcome("Unknown tool: " + call.name(), true);
        }
        PluginSlot slot = host.slot(rest.substring(0, cut));
        String toolName = rest.substring(cut + 2);
        if (slot == null || !slot.running()) {
            return new Toolbox.Outcome("插件已停用或没在运行，这个工具现在用不了：" + call.name(), true);
        }
        PluginManifest.Tool tool = slot.tools.stream().filter(t -> t.name().equals(toolName)).findFirst().orElse(null);
        if (tool == null) {
            return new Toolbox.Outcome("插件「" + slot.name() + "」没有这个工具：" + toolName, true);
        }
        JsonObject args = Jsons.parseObject(call.arguments());
        if (args == null) {
            return new Toolbox.Outcome("Invalid arguments for " + call.name() + ": expected a JSON object", true);
        }
        String problem = ArgsCheck.check(tool.parameters(), args);
        if (problem != null) {
            return new Toolbox.Outcome("Invalid arguments for " + call.name() + ": " + problem, true);
        }
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", args);
        params.addProperty("session", session);
        long timeout = tool.timeout() * 1000L;
        try {
            return ToolResults.outcome(slot.process.call("tool/call", params, timeout).get(timeout + 500, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            host.pluginLog(slot, "warn", "工具 " + toolName + " 超过 " + tool.timeout() + " 秒没返回");
            return new Toolbox.Outcome("Tool " + call.name() + " timed out after " + tool.timeout() + " s", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Toolbox.Outcome("Tool call interrupted", true);
        } catch (ExecutionException e) {
            host.pluginLog(slot, "warn", "工具 " + toolName + " 出错：" + PluginHost.describe(e));
            return new Toolbox.Outcome("Tool " + call.name() + " failed: " + PluginHost.describe(e), true);
        }
    }

    @Override
    public String contribute(String session, List<String> people) {
        List<PluginSlot> list = host.sorted().stream().filter(PluginSlot::running).filter(s -> s.manifest.prompt())
                .sorted(Comparator.comparingInt((PluginSlot s) -> s.manifest.priority()).thenComparing(s -> s.id)).toList();
        if (list.isEmpty()) {
            return "";
        }
        Map<PluginSlot, CompletableFuture<JsonElement>> calls = new LinkedHashMap<>();
        JsonArray who = new JsonArray();
        people.forEach(who::add);
        for (PluginSlot slot : list) {
            JsonObject params = new JsonObject();
            params.addProperty("session", session);
            params.add("people", who.deepCopy());
            calls.put(slot, slot.process.call("prompt/contribute", params, PROMPT_TIMEOUT_MS));
        }
        long deadline = System.currentTimeMillis() + PROMPT_TIMEOUT_MS;
        StringBuilder out = new StringBuilder();
        for (Map.Entry<PluginSlot, CompletableFuture<JsonElement>> e : calls.entrySet()) {
            try {
                JsonElement r = e.getValue().get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                String text = r == null || r.isJsonNull() ? "" : r.isJsonPrimitive() ? r.getAsString() : Jsons.optString(object(r), "text");
                text = text.strip();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.length() > MAX_PROMPT) {
                    text = text.substring(0, MAX_PROMPT) + "…";
                }
                String name = e.getKey().name().replaceAll("[<>]", "");
                out.append("<插件:").append(name).append(">\n").append(text.replace("</插件", "＜/插件")).append("\n</插件:").append(name).append(">\n\n");
            } catch (TimeoutException ex) {
                host.pluginLog(e.getKey(), "warn", "提示词补充超过 " + PROMPT_TIMEOUT_MS + " 毫秒，这轮跳过");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException ex) {
                host.pluginLog(e.getKey(), "warn", "提示词补充出错：" + PluginHost.describe(ex));
            }
        }
        return out.toString();
    }

    @Override
    public List<ToolInfo> catalog() {
        return catalog;
    }

    List<String> toolNames() {
        return catalog.stream().map(ToolInfo::name).toList();
    }
}
