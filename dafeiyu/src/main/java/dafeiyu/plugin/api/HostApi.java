package dafeiyu.plugin.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.memory.MemoryStore;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.persona.Persona;
import dafeiyu.agent.protocol.ChatRequest;
import dafeiyu.agent.protocol.Protocols;
import dafeiyu.agent.qq.QqMessages;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.usage.UsageStore;
import dafeiyu.message.MessageArchive;
import dafeiyu.message.StoredMessage;
import dafeiyu.plugin.manifest.Permission;
import dafeiyu.plugin.rpc.RpcChannel.RpcError;
import dafeiyu.plugin.store.PluginStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static dafeiyu.util.Jsons.optString;

public final class HostApi {

    public record Caller(String id, String name, Set<Permission> granted) {}

    public record ReplyTarget(String session, String messageId) {}

    public interface Services {
        ReplyTarget useReply(String pluginId, String ctx);

        void log(String pluginId, String level, String text);

        void notifyWeb(String pluginId, String text);

        void denied(String pluginId, String method, Permission missing);

        void toolsChanged(String pluginId);
    }

    public static final int SENDS_PER_MINUTE = 10;
    public static final int SENDS_PER_DAY = 100;
    private static final int MAX_LLM_OUTPUT = 4096;
    private static final Set<String> LEVELS = Set.of("debug", "info", "warn", "error");

    private final Services services;
    private final ChatAgent agent;
    private final QqPort qq;
    private final MessageArchive archive;
    private final UsageStore usage;
    private final PluginStore store;
    private final Map<String, Deque<Long>> sends = new ConcurrentHashMap<>();

    public HostApi(Services services, ChatAgent agent, QqPort qq, MessageArchive archive, UsageStore usage, PluginStore store) {
        this.services = services;
        this.agent = agent;
        this.qq = qq;
        this.archive = archive;
        this.usage = usage;
        this.store = store;
    }

    public JsonElement handle(Caller caller, String method, JsonObject params) throws RpcError {
        return switch (method) {
            case "qq/reply" -> reply(caller, params);
            case "qq/send" -> send(caller, params);
            case "qq/history" -> history(caller, params);
            case "qq/groups" -> groups(caller);
            case "qq/members" -> members(caller, params);
            case "memory/recall" -> recall(caller, params);
            case "memory/add" -> remember(caller, params);
            case "llm/chat" -> llm(caller, params);
            case "kv/get" -> kvGet(caller, params);
            case "kv/set" -> kvSet(caller, params);
            case "kv/delete" -> ok(store.kvDelete(caller.id(), key(params)));
            case "kv/list" -> kvList(caller, params);
            case "log" -> log(caller, params);
            case "notify" -> notifyWeb(caller, params);
            case "tools/changed" -> {
                services.toolsChanged(caller.id());
                yield JsonNull.INSTANCE;
            }
            default -> throw new RpcError(RpcError.NO_METHOD, "unknown method " + method);
        };
    }

    private void need(Caller caller, String method, Permission permission) throws RpcError {
        if (!caller.granted().contains(permission)) {
            services.denied(caller.id(), method, permission);
            throw new RpcError(RpcError.DENIED, "permission denied: " + permission.key() + " is required for " + method);
        }
    }

    private JsonElement reply(Caller caller, JsonObject params) throws RpcError {
        need(caller, "qq/reply", Permission.QQ_REPLY);
        ReplyTarget target = services.useReply(caller.id(), optString(params, "ctx"));
        if (target == null) {
            throw new RpcError(RpcError.FAILED, "this message can no longer be replied to (unknown, expired, used up or already handled)");
        }
        return deliver(caller, target.session(), params, target.session().startsWith("group:") ? target.messageId() : null);
    }

    private JsonElement send(Caller caller, JsonObject params) throws RpcError {
        String groupId = optString(params, "groupId").strip();
        String userId = optString(params, "userId").strip();
        String session;
        if (!groupId.isEmpty()) {
            need(caller, "qq/send", Permission.QQ_SEND);
            requireGroup(caller, "qq/send", groupId);
            session = "group:" + groupId;
        } else if (!userId.isEmpty()) {
            need(caller, "qq/send", Permission.QQ_PRIVATE);
            if (!userId.matches("\\d{5,12}")) {
                throw new RpcError(RpcError.BAD_PARAMS, "userId is not a QQ number");
            }
            session = "private:" + userId;
        } else {
            throw new RpcError(RpcError.BAD_PARAMS, "groupId or userId is required");
        }
        limit(caller);
        return deliver(caller, session, params, null);
    }

    private void limit(Caller caller) throws RpcError {
        long now = System.currentTimeMillis();
        Deque<Long> times = sends.computeIfAbsent(caller.id(), k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 86_400_000L) {
                times.removeFirst();
            }
            long lastMinute = times.stream().filter(t -> now - t < 60_000).count();
            if (lastMinute >= SENDS_PER_MINUTE || times.size() >= SENDS_PER_DAY) {
                services.log(caller.id(), "warn", "主动发消息太频繁，被限制了（每分钟 " + SENDS_PER_MINUTE + " 条、每天 " + SENDS_PER_DAY + " 条）");
                throw new RpcError(RpcError.LIMITED, "send rate limit reached (" + SENDS_PER_MINUTE + "/minute, " + SENDS_PER_DAY + "/day)");
            }
            times.addLast(now);
        }
    }

    private JsonElement deliver(Caller caller, String session, JsonObject params, String quote) throws RpcError {
        String text = optString(params, "text");
        String image = optString(params, "image");
        if (text.isBlank() && image.isBlank()) {
            throw new RpcError(RpcError.BAD_PARAMS, "text or image is required");
        }
        if (!image.isBlank() && !image.startsWith("http://") && !image.startsWith("https://") && !image.startsWith("base64://")) {
            throw new RpcError(RpcError.BAD_PARAMS, "image must be an http(s) URL or base64://");
        }
        if (!text.isBlank()) {
            ContentGuard guard = agent.guard();
            ContentGuard.Verdict verdict = guard.scan(text);
            if (verdict == null && guard.suspicious(text)) {
                verdict = guard.review(agent.store().active(), "", text);
            }
            if (verdict != null) {
                services.log(caller.id(), "warn", "要发的内容没通过内容审核（" + verdict.category().label() + "），没有发");
                throw new RpcError(RpcError.FAILED, "content blocked by the red-line check (" + verdict.category().label() + ")");
            }
        }
        try {
            String sent = qq.send(session, text, image, quote, caller.name());
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("text", sent);
            return out;
        } catch (ToolFailure e) {
            throw new RpcError(RpcError.FAILED, e.getMessage());
        }
    }

    private void requireGroup(Caller caller, String method, String groupId) throws RpcError {
        if (!store.groups(caller.id()).contains(groupId)) {
            services.denied(caller.id(), method + " " + groupId, null);
            throw new RpcError(RpcError.DENIED, "group " + groupId + " is not allowed for this plugin (pick it in the plugin settings)");
        }
    }

    private JsonElement history(Caller caller, JsonObject params) throws RpcError {
        String groupId = optString(params, "groupId").strip();
        String userId = optString(params, "userId").strip();
        int count = params.has("count") ? Math.max(1, Math.min(100, params.get("count").getAsInt())) : 20;
        List<StoredMessage> rows;
        if (!groupId.isEmpty()) {
            need(caller, "qq/history", Permission.QQ_READ);
            requireGroup(caller, "qq/history", groupId);
            rows = archive.conversation(true, groupId, count);
        } else if (!userId.isEmpty()) {
            need(caller, "qq/history", Permission.QQ_PRIVATE);
            rows = archive.conversation(false, userId, count);
        } else {
            throw new RpcError(RpcError.BAD_PARAMS, "groupId or userId is required");
        }
        JsonArray out = new JsonArray();
        for (StoredMessage m : rows) {
            JsonObject item = new JsonObject();
            item.addProperty("time", m.time());
            item.addProperty("direction", m.direction());
            item.addProperty("userId", m.userId());
            item.addProperty("sender", m.senderName());
            item.addProperty("text", QqMessages.plain(m.content() == null ? "" : m.content()));
            item.addProperty("messageId", m.messageId());
            out.add(item);
        }
        return out;
    }

    private JsonElement groups(Caller caller) throws RpcError {
        need(caller, "qq/groups", Permission.QQ_READ);
        Map<String, String> names = qq.groupNames();
        JsonArray out = new JsonArray();
        for (String id : store.groups(caller.id())) {
            JsonObject item = new JsonObject();
            item.addProperty("groupId", id);
            item.addProperty("name", names.getOrDefault(id, ""));
            out.add(item);
        }
        return out;
    }

    private JsonElement members(Caller caller, JsonObject params) throws RpcError {
        need(caller, "qq/members", Permission.QQ_READ);
        String groupId = optString(params, "groupId").strip();
        requireGroup(caller, "qq/members", groupId);
        try {
            return qq.members(groupId);
        } catch (ToolFailure | RuntimeException e) {
            throw new RpcError(RpcError.FAILED, e.getMessage());
        }
    }

    private JsonElement recall(Caller caller, JsonObject params) throws RpcError {
        need(caller, "memory/recall", Permission.MEMORY_READ);
        String scope = scope(optString(params, "about"), true);
        Persona persona = persona();
        Set<String> owners = Set.copyOf(agent.personas().owners());
        String session = "plugin:" + caller.id();
        JsonArray out = new JsonArray();
        for (MemoryStore.Memory m : agent.memories().list(persona.id(), scope)) {
            if (out.size() >= 30) {
                break;
            }
            if (!MemoryStore.visible(m, session, owners)) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("text", MemoryStore.line(m));
            item.addProperty("updatedAt", m.updatedAt());
            out.add(item);
        }
        return out;
    }

    private JsonElement remember(Caller caller, JsonObject params) throws RpcError {
        need(caller, "memory/add", Permission.MEMORY_WRITE);
        String scope = scope(optString(params, "about"), false);
        String text = optString(params, "text").strip();
        if (text.isEmpty()) {
            throw new RpcError(RpcError.BAD_PARAMS, "text is required");
        }
        if (agent.guard().scan(text) != null) {
            throw new RpcError(RpcError.FAILED, "this text did not pass the red-line check");
        }
        try {
            agent.memories().add(persona().id(), scope, "", text, "plugin:" + caller.id());
        } catch (IllegalArgumentException e) {
            throw new RpcError(RpcError.BAD_PARAMS, e.getMessage());
        }
        services.log(caller.id(), "info", "往她的记忆里添了一条（" + scope + "）");
        return ok(true);
    }

    private static String scope(String about, boolean diary) throws RpcError {
        String scope = about.strip();
        if (scope.equals("self") || (diary && scope.equals("diary")) || MemoryStore.validScope(scope) && !scope.equals("diary")) {
            return scope;
        }
        throw new RpcError(RpcError.BAD_PARAMS, "about must be self" + (diary ? ", diary" : "") + ", group:<id> or user:<qq>");
    }

    private Persona persona() throws RpcError {
        Persona persona = agent.personas().active();
        if (persona == null) {
            throw new RpcError(RpcError.FAILED, "no persona is selected");
        }
        return persona;
    }

    private JsonElement llm(Caller caller, JsonObject params) throws RpcError {
        need(caller, "llm/chat", Permission.LLM);
        String purpose = "plugin:" + caller.id();
        long used = usage.tokensToday(purpose);
        int budget = store.llmBudget(caller.id());
        if (used >= budget) {
            services.log(caller.id(), "warn", "今天的大模型额度用完了（" + used + " / " + budget + " token）");
            throw new RpcError(RpcError.LIMITED, "daily token budget used up (" + used + " / " + budget + ")");
        }
        ModelProfile profile = agent.store().active();
        if (profile == null) {
            throw new RpcError(RpcError.FAILED, "no model is selected");
        }
        List<ChatMessage> messages = new ArrayList<>();
        String system = optString(params, "system");
        if (!system.isBlank()) {
            messages.add(ChatMessage.system(system));
        }
        if (params.has("messages") && params.get("messages").isJsonArray()) {
            for (JsonElement e : params.getAsJsonArray("messages")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                String role = optString(e.getAsJsonObject(), "role");
                String content = optString(e.getAsJsonObject(), "content");
                messages.add(role.equals("assistant") ? ChatMessage.assistant(content, "") : ChatMessage.user(content));
            }
        } else {
            String prompt = optString(params, "prompt");
            if (prompt.isBlank()) {
                throw new RpcError(RpcError.BAD_PARAMS, "prompt or messages is required");
            }
            messages.add(ChatMessage.user(prompt));
        }
        int max = params.has("maxTokens") ? Math.max(16, Math.min(MAX_LLM_OUTPUT, params.get("maxTokens").getAsInt())) : 1024;
        try {
            ChatRequest request = new ChatRequest(messages, List.of(), false, purpose, max, "", purpose);
            ChatResult result = Protocols.get(profile.protocol()).stream(profile, request, StreamListener.NONE);
            JsonObject out = new JsonObject();
            out.addProperty("text", result.content());
            out.addProperty("promptTokens", result.promptTokens());
            out.addProperty("completionTokens", result.completionTokens());
            return out;
        } catch (AgentException e) {
            throw new RpcError(RpcError.FAILED, "model call failed: " + e.getMessage());
        }
    }

    private JsonElement kvGet(Caller caller, JsonObject params) throws RpcError {
        String raw = store.kvGet(caller.id(), key(params));
        JsonObject out = new JsonObject();
        if (raw == null) {
            out.add("value", JsonNull.INSTANCE);
        } else {
            try {
                out.add("value", JsonParser.parseString(raw));
            } catch (RuntimeException e) {
                out.addProperty("value", raw);
            }
        }
        return out;
    }

    private JsonElement kvSet(Caller caller, JsonObject params) throws RpcError {
        if (!params.has("value")) {
            throw new RpcError(RpcError.BAD_PARAMS, "value is required");
        }
        store.kvSet(caller.id(), key(params), params.get("value").toString());
        return ok(true);
    }

    private JsonElement kvList(Caller caller, JsonObject params) {
        JsonArray out = new JsonArray();
        store.kvKeys(caller.id(), optString(params, "prefix")).forEach(out::add);
        return out;
    }

    private JsonElement log(Caller caller, JsonObject params) {
        String level = optString(params, "level");
        services.log(caller.id(), LEVELS.contains(level) ? level : "info", optString(params, "text"));
        return JsonNull.INSTANCE;
    }

    private JsonElement notifyWeb(Caller caller, JsonObject params) throws RpcError {
        String text = optString(params, "text").strip();
        if (text.isEmpty()) {
            throw new RpcError(RpcError.BAD_PARAMS, "text is required");
        }
        services.notifyWeb(caller.id(), text.length() > 200 ? text.substring(0, 200) + "…" : text);
        return JsonNull.INSTANCE;
    }

    private static String key(JsonObject params) throws RpcError {
        String key = optString(params, "key");
        if (key.isEmpty()) {
            throw new RpcError(RpcError.BAD_PARAMS, "key is required");
        }
        return key;
    }

    private static JsonElement ok(boolean value) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", value);
        return out;
    }
}
