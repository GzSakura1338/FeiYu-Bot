package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.ChatTurn;
import dafeiyu.agent.Extensions;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.SubagentSettings;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.model.AgentStore;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.model.ModelLimits;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.protocol.Protocols;
import dafeiyu.agent.tool.FileSink;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.agent.tool.WorkspaceStore;
import dafeiyu.db.DatabaseException;
import dafeiyu.log.LogBuffer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import static dafeiyu.util.Texts.abbreviate;

final class AgentHandler {

    private record QqRequest(Boolean enabled) {}

    private record EffortRequest(String effort) {}

    private record SandboxTestRequest(Boolean network, Boolean direct) {}

    private record ModelsRequest(String protocol, String baseUrl, String apiKey, String profileId) {}

    private record ChatRequest(String session, String message, List<String> images) {}

    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final int MAX_IMAGES = 4;
    private static final long MAX_WEB_FILE = 20L * 1024 * 1024;
    private static final int TOOL_OUTPUT_PREVIEW = 4000;

    private final Gson gson = new Gson();
    private final ChatAgent agent;
    private final AgentStore store;
    private final LogBuffer log;


    AgentHandler(ChatAgent agent, LogBuffer log) {
        this.agent = agent;
        this.store = agent.store();
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/api/agent".length());
        String method = ex.getRequestMethod();
        try {
            if (!method.equals("GET") && !Requests.isJson(ex)) {
                HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/json"));
            } else if (path.isEmpty() || path.equals("/")) {
                if (method.equals("GET")) {
                    HttpResponses.json(ex, state());
                } else {
                    HttpResponses.json(ex, 405, error("不支持的请求方法"));
                }
            } else if (path.equals("/profiles") && method.equals("POST")) {
                create(ex);
            } else if (path.startsWith("/profiles/") && path.endsWith("/activate") && method.equals("POST")) {
                activate(ex, path.substring("/profiles/".length(), path.length() - "/activate".length()));
            } else if (path.startsWith("/profiles/") && path.endsWith("/probe-vision") && method.equals("POST")) {
                probeVision(ex, path.substring("/profiles/".length(), path.length() - "/probe-vision".length()));
            } else if (path.startsWith("/profiles/") && method.equals("PUT")) {
                update(ex, path.substring("/profiles/".length()));
            } else if (path.startsWith("/profiles/") && method.equals("DELETE")) {
                delete(ex, path.substring("/profiles/".length()));
            } else if (path.equals("/qq") && method.equals("PUT")) {
                setQq(ex);
            } else if (path.equals("/group-mode") && method.equals("PUT")) {
                Boolean enabled = Requests.readJson(ex, QqRequest.class).enabled();
                if (enabled == null) {
                    throw new IllegalArgumentException("缺少 enabled");
                }
                if (enabled != store.groupMode()) {
                    store.setGroupMode(enabled);
                    log.info("agent: group chat mode " + (enabled ? "ON" : "OFF"));
                }
                HttpResponses.json(ex, state());
            } else if (path.equals("/effort") && method.equals("PUT")) {
                String effort = Requests.readJson(ex, EffortRequest.class).effort();
                if (effort == null) {
                    throw new IllegalArgumentException("缺少 effort");
                }
                store.setReasoningEffort(effort);
                log.info("agent: reasoning effort set to " + effort);
                HttpResponses.json(ex, state());
            } else if (path.equals("/pacing") && method.equals("PUT")) {
                AgentStore.Pacing pacing = Requests.readJson(ex, AgentStore.Pacing.class);
                store.setPacing(pacing);
                log.info("agent: send pacing set to " + pacing.intervalMs() + " ms, up to " + pacing.maxPieces() + " piece(s)");
                HttpResponses.json(ex, state());
            } else if (path.equals("/models") && method.equals("POST")) {
                models(ex);
            } else if (path.equals("/chat") && method.equals("POST")) {
                chat(ex);
            } else if (path.equals("/limits") && method.equals("GET")) {
                limits(ex);
            } else if (path.equals("/reset") && method.equals("POST")) {
                agent.reset(webSession(Requests.readJson(ex, ChatRequest.class).session()));
                HttpResponses.json(ex, "{}");
            } else if (path.equals("/subagents") && method.equals("GET")) {
                HttpResponses.json(ex, subagents());
            } else if (path.equals("/subagents") && method.equals("PUT")) {
                SubagentSettings.Config saved = agent.tasks().subagents().update(Requests.readJson(ex, SubagentSettings.Config.class));
                agent.tasks().limitsChanged();
                log.info("agent: subagents " + (saved.enabled() ? "ON" : "off") + " (research " + saved.research() + ", build " + saved.build()
                        + ", mind " + saved.mindLimit() + " / qq " + saved.qqLimit() + " at once, " + saved.researchMinutes() + "/" + saved.buildMinutes()
                        + " min, daily " + saved.dailyLimit() + ")");
                HttpResponses.json(ex, subagents());
            } else if (path.equals("/workspace") && method.equals("GET")) {
                HttpResponses.json(ex, workspace());
            } else if (path.equals("/workspace") && method.equals("PUT")) {
                updateWorkspace(ex);
            } else if (path.equals("/workspace/test") && method.equals("POST")) {
                SandboxTestRequest test = Requests.readJson(ex, SandboxTestRequest.class);
                boolean network = Boolean.TRUE.equals(test.network());
                boolean direct = Boolean.TRUE.equals(test.direct());
                JsonObject body = new JsonObject();
                try {
                    if (direct && !agent.workspaces().config().shellDirect()) {
                        throw new ToolFailure("「沙盒外执行」还没打开");
                    }
                    body.addProperty("output", Toolbox.sandboxTest(agent.workspaces().workspace(), network, direct));
                    body.addProperty("ok", true);
                } catch (ToolFailure e) {
                    body.addProperty("output", e.getMessage());
                    body.addProperty("ok", false);
                }
                HttpResponses.json(ex, body.toString());
            } else {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            }
        } catch (IllegalArgumentException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        } catch (DatabaseException e) {
            log.error(e.getMessage());
            HttpResponses.json(ex, 500, error(e.getMessage()));
        }
    }

    private void create(HttpExchange ex) throws IOException {
        ModelProfile created = store.add(readProfile(ex));
        log.info("agent: model profile added: " + created.name() + " (" + created.model() + ")");
        HttpResponses.json(ex, state());
    }

    private void update(HttpExchange ex, String id) throws IOException {
        ModelProfile old = require(id);
        ModelProfile next = readProfile(ex).withId(id);
        if (next.apiKey().isEmpty()) {
            next = next.withApiKey(old.apiKey());
        }
        store.update(next);
        if (!next.protocol().equals(old.protocol()) || !next.baseUrl().equals(old.baseUrl()) || !next.model().equals(old.model())
                || !next.apiKey().equals(old.apiKey())) {
            store.resetCapabilities(id);
        }
        log.info("agent: model profile updated: " + next.name() + " (" + next.model() + ")");
        HttpResponses.json(ex, state());
    }

    private void probeVision(HttpExchange ex, String id) throws IOException {
        ModelProfile profile = require(id);
        try {
            boolean supported = agent.probeVision(id);
            log.info("agent: vision probe for " + profile.name() + ": " + (supported ? "supported" : "not supported"));
            HttpResponses.json(ex, state());
        } catch (AgentException e) {
            HttpResponses.json(ex, 502, error(e.getMessage()));
        }
    }

    private void delete(HttpExchange ex, String id) throws IOException {
        ModelProfile profile = require(id);
        boolean active = id.equals(store.activeId());
        if (active && store.qqReply()) {
            throw new IllegalArgumentException("QQ 自动回复开启时不能删除正在使用的模型，请先关闭或切换到其他模型");
        }
        store.remove(id);
        if (active) {
            List<ModelProfile> rest = store.profiles();
            store.setActive(rest.isEmpty() ? "" : rest.getFirst().id());
        }
        log.info("agent: model profile deleted: " + profile.name());
        HttpResponses.json(ex, state());
    }

    private void activate(HttpExchange ex, String id) throws IOException {
        ModelProfile profile = require(id);
        if (!id.equals(store.activeId())) {
            store.setActive(id);
            log.info("agent: now using " + profile.name() + " (" + profile.model() + ")");
        }
        HttpResponses.json(ex, state());
    }

    private void setQq(HttpExchange ex) throws IOException {
        Boolean enabled = Requests.readJson(ex, QqRequest.class).enabled();
        if (enabled == null) {
            throw new IllegalArgumentException("缺少 enabled");
        }
        if (enabled && store.active() == null) {
            throw new IllegalArgumentException("开启 QQ 自动回复前要先添加并选择一个模型");
        }
        if (enabled != store.qqReply()) {
            store.setQqReply(enabled);
            log.info("agent: QQ auto-reply " + (enabled ? "ON" : "OFF"));
        }
        HttpResponses.json(ex, state());
    }

    private void models(HttpExchange ex) throws IOException {
        ModelsRequest req = Requests.readJson(ex, ModelsRequest.class);
        if (!Protocols.exists(req.protocol())) {
            throw new IllegalArgumentException("请选择协议");
        }
        try {
            List<String> models = agent.listModels(req.protocol(), req.baseUrl(), req.apiKey(), req.profileId());
            JsonObject body = new JsonObject();
            body.add("models", gson.toJsonTree(models));
            HttpResponses.json(ex, body.toString());
        } catch (AgentException e) {
            HttpResponses.json(ex, 502, error(e.getMessage()));
        }
    }

    private void chat(HttpExchange ex) throws IOException {
        ChatRequest req = Requests.readJson(ex, ChatRequest.class);
        String message = req.message() == null ? "" : req.message().strip();
        List<String> dataUrls = req.images() == null ? List.of() : req.images().stream().filter(Objects::nonNull).toList();
        if (message.isEmpty() && dataUrls.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("消息不能超过 " + MAX_MESSAGE_LENGTH + " 字");
        }
        if (dataUrls.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("一次最多 " + MAX_IMAGES + " 张图片");
        }
        List<ImagePart> images = new ArrayList<>();
        StringBuilder text = new StringBuilder(message);
        for (String dataUrl : dataUrls) {
            try {
                images.add(ImageLoader.fromDataUrl(dataUrl));
            } catch (AgentException e) {
                throw new IllegalArgumentException("图片无法读取：" + e.getMessage());
            }
            text.append(text.isEmpty() ? "" : "\n").append("[图片]");
        }
        String session = webSession(req.session());
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        try (SseWriter sse = new SseWriter(ex.getResponseBody())) {
            try {
                ChatTurn turn = new ChatTurn(session, ChatMessage.user(text.toString(), images), null, files(sse), "web");
                ChatResult result = agent.chat(turn, listener(sse));
                sse.send("done", gson.toJsonTree(result));
            } catch (AgentException e) {
                sse.send("error", e.getMessage());
            } catch (UncheckedIOException e) {
                log.info("agent: web chat stream closed by the browser");
            } catch (RuntimeException e) {
                log.error("agent: web chat failed: " + e);
                sse.send("error", "内部错误：" + e.getMessage());
            }
        } catch (UncheckedIOException ignored) {
        }
    }

    private StreamListener listener(SseWriter sse) {
        return new StreamListener() {
            @Override
            public void onText(String delta) {
                sse.send("text", delta);
            }

            @Override
            public void onReasoning(String delta) {
                sse.send("reasoning", delta);
            }

            @Override
            public void onCompaction(String reason) {
                sse.send("compaction", reason);
            }

            @Override
            public void onRetry(int attempt, long delayMs, String error) {
                JsonObject data = new JsonObject();
                data.addProperty("attempt", attempt);
                data.addProperty("delayMs", delayMs);
                data.addProperty("error", error);
                sse.send("retry", data);
            }

            @Override
            public void onToolCall(ToolCall call) {
                JsonObject data = new JsonObject();
                data.addProperty("id", call.id());
                data.addProperty("name", call.name());
                data.addProperty("arguments", call.arguments());
                sse.send("tool-call", data);
            }

            @Override
            public void onToolResult(ToolCall call, String output, boolean error) {
                JsonObject data = new JsonObject();
                data.addProperty("id", call.id());
                data.addProperty("output", abbreviate(output, TOOL_OUTPUT_PREVIEW));
                data.addProperty("error", error);
                sse.send("tool-result", data);
            }

            @Override
            public void onBlocked(String category, boolean byModel, String replacement) {
                JsonObject data = new JsonObject();
                data.addProperty("category", category);
                data.addProperty("byModel", byModel);
                data.addProperty("replacement", replacement);
                sse.send("blocked", data);
            }
        };
    }

    private static FileSink files(SseWriter sse) {
        return (file, name) -> {
            byte[] bytes;
            try {
                if (Files.size(file) > MAX_WEB_FILE) {
                    throw new ToolFailure("File is too large to show in the web console (limit " + MAX_WEB_FILE / 1024 / 1024 + " MB)");
                }
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new ToolFailure("Unable to read " + name + ": " + e.getMessage());
            }
            String mime;
            try {
                mime = Files.probeContentType(file);
            } catch (IOException e) {
                mime = null;
            }
            mime = mime == null ? "application/octet-stream" : mime;
            JsonObject data = new JsonObject();
            data.addProperty("name", name);
            data.addProperty("size", bytes.length);
            data.addProperty("mime", mime);
            data.addProperty("url", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
            sse.send("file", data);
            return "Sent file " + name + " to the user.";
        };
    }

    private void limits(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String model = "";
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("model=")) {
                    model = URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
                }
            }
        }
        HttpResponses.json(ex, gson.toJson(ModelLimits.lookup(model)));
    }

    private String workspace() {
        WorkspaceStore workspaces = agent.workspaces();
        JsonObject body = gson.toJsonTree(workspaces.config()).getAsJsonObject();
        body.addProperty("problem", workspaces.problem());
        List<String> names = new ArrayList<>(Toolbox.names());
        List<Extensions.ToolInfo> extra = agent.extensions().catalog();
        extra.forEach(t -> names.add(t.name()));
        body.add("tools", gson.toJsonTree(names));
        body.add("extraTools", gson.toJsonTree(extra));
        return body.toString();
    }

    private String subagents() {
        JsonObject body = gson.toJsonTree(agent.tasks().subagents().config()).getAsJsonObject();
        JsonArray profiles = new JsonArray();
        for (ModelProfile p : store.profiles()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", p.id());
            item.addProperty("name", p.name());
            item.addProperty("model", p.model());
            profiles.add(item);
        }
        body.add("profiles", profiles);
        body.addProperty("workspace", agent.workspaces().workspace() != null);
        body.addProperty("today", agent.tasks().spawnedToday());
        return body.toString();
    }

    private void updateWorkspace(HttpExchange ex) throws IOException {
        WorkspaceStore.Config next = Requests.readJson(ex, WorkspaceStore.Config.class);
        WorkspaceStore.Config saved = agent.workspaces().update(next);
        log.info("agent: workspace " + (saved.dir().isEmpty() ? "cleared" : "set to " + saved.dir())
                + ", tools " + String.join(",", saved.enabled()) + ", QQ access " + saved.qqAccess()
                + ", shell network " + (saved.shellNetwork() ? "on" : "off")
                + ", shell outside sandbox " + (saved.shellDirect() ? "ON for owners + " + saved.directUsers().size() + " QQ user(s)" : "off"));
        HttpResponses.json(ex, workspace());
    }

    private ModelProfile readProfile(HttpExchange ex) throws IOException {
        ModelProfile profile = Requests.readJson(ex, ModelProfile.class);
        String problem = profile.validate();
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return profile;
    }

    private ModelProfile require(String id) {
        ModelProfile profile = store.get(id);
        if (profile == null) {
            throw new IllegalArgumentException("模型配置不存在，可能已被删除");
        }
        return profile;
    }

    private static String webSession(String session) {
        if (session == null || !session.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("session 格式不对");
        }
        return "web:" + session;
    }

    private String state() {
        JsonArray profiles = new JsonArray();
        for (ModelProfile p : store.profiles()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", p.id());
            item.addProperty("name", p.name());
            item.addProperty("protocol", p.protocol());
            item.addProperty("baseUrl", p.baseUrl());
            item.addProperty("model", p.model());
            item.addProperty("temperature", p.temperature());
            item.addProperty("maxTokens", p.maxTokens());
            item.addProperty("contextWindow", p.contextWindow());
            item.add("limits", gson.toJsonTree(ModelLimits.resolve(p)));
            item.addProperty("vision", store.vision(p.id()));
            item.addProperty("systemRole", store.systemRole(p.id()));
            item.addProperty("toolCall", store.toolCall(p.id()));
            item.addProperty("hasApiKey", !p.apiKey().isEmpty());
            item.addProperty("apiKeyHint", maskKey(p.apiKey()));
            profiles.add(item);
        }
        JsonObject body = new JsonObject();
        body.addProperty("qqReply", store.qqReply());
        body.addProperty("groupMode", store.groupMode());
        body.add("wakeWords", gson.toJsonTree(store.wakeWords()));
        body.add("mood", gson.toJsonTree(agent.mood().current(agent.personas().activeId())));
        body.addProperty("freeWill", store.freeWill());
        body.addProperty("reasoningEffort", store.reasoningEffort());
        body.add("pacing", gson.toJsonTree(store.pacing()));
        body.addProperty("active", store.activeId());
        body.add("profiles", profiles);
        return body.toString();
    }

    private static String maskKey(String key) {
        if (key.isEmpty()) {
            return "";
        }
        return key.length() <= 10 ? "••••" : key.substring(0, 3) + "…" + key.substring(key.length() - 4);
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body.toString();
    }
}
