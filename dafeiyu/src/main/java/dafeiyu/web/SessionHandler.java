package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.SessionDetail;
import dafeiyu.agent.SessionMonitor;
import dafeiyu.agent.SessionSummary;
import dafeiyu.agent.memory.Reflection;
import dafeiyu.agent.mind.Mind;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.persona.Persona;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.db.DatabaseException;
import dafeiyu.log.LogBuffer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class SessionHandler {

    private static final String PREFIX = "/api/agent/sessions";
    private static final Pattern SESSION = Pattern.compile("(group|private):\\d{1,15}|web:[A-Za-z0-9_-]{1,40}|mind:self");
    private static final int DETAIL_LIMIT = 300;
    private static final int MAX_MESSAGE_LENGTH = 4000;

    private record SendRequest(String message, Boolean deliver) {}

    private record CommandRequest(String command, String args) {}

    private final Gson gson = new Gson();
    private final ChatAgent agent;
    private final QqChatBridge bridge;
    private final QqDirectory directory;
    private final LogBuffer log;

    SessionHandler(ChatAgent agent, QqChatBridge bridge, QqDirectory directory, LogBuffer log) {
        this.agent = agent;
        this.bridge = bridge;
        this.directory = directory;
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        try {
            if (!method.equals("GET") && !Requests.isJson(ex)) {
                HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/json"));
            } else if ((rest.isEmpty() || rest.equals("/")) && method.equals("GET")) {
                HttpResponses.json(ex, list());
            } else if (rest.equals("/stream") && method.equals("GET")) {
                stream(ex);
            } else if (rest.equals("/forward") && method.equals("GET")) {
                forward(ex);
            } else if (rest.endsWith("/stop") && method.equals("POST")) {
                String session = session(rest.substring(1, rest.length() - "/stop".length()));
                int stopped = agent.stop(session);
                if (stopped > 0) {
                    log.info("agent [" + session + "] stopped from the console");
                }
                JsonObject body = new JsonObject();
                body.addProperty("stopped", stopped);
                HttpResponses.json(ex, body.toString());
            } else if (rest.endsWith("/send") && method.equals("POST")) {
                send(ex, session(rest.substring(1, rest.length() - "/send".length())));
            } else if (rest.endsWith("/command") && method.equals("POST")) {
                command(ex, session(rest.substring(1, rest.length() - "/command".length())));
            } else if (rest.length() > 1 && method.equals("GET")) {
                HttpResponses.json(ex, detail(session(rest.substring(1))));
            } else if (rest.length() > 1 && method.equals("DELETE")) {
                String session = session(rest.substring(1));
                agent.reset(session);
                log.info("agent [" + session + "] memory cleared from the console");
                HttpResponses.json(ex, "{}");
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

    private void forward(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getRawQuery();
        String id = raw == null || !raw.startsWith("id=") ? "" : java.net.URLDecoder.decode(raw.substring(3), java.nio.charset.StandardCharsets.UTF_8);
        try {
            JsonArray lines = new JsonArray();
            for (dafeiyu.agent.qq.QqActions.ForwardLine l : bridge.actions().forward(id)) {
                JsonObject o = new JsonObject();
                o.addProperty("name", l.name());
                o.addProperty("userId", l.userId());
                o.addProperty("time", l.time());
                o.addProperty("text", l.text());
                o.addProperty("attachments", l.attachments());
                lines.add(o);
            }
            JsonObject body = new JsonObject();
            body.add("lines", lines);
            HttpResponses.json(ex, body.toString());
        } catch (dafeiyu.agent.tool.ToolFailure e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        }
    }

    private String list() {
        QqDirectory.Names names = directory.names();
        JsonArray active = agent.monitor().snapshot();
        Set<String> busy = new HashSet<>();
        active.forEach(t -> busy.add(t.getAsJsonObject().get("session").getAsString()));
        List<SessionSummary> stored = agent.sessions();
        Set<String> known = stored.stream().map(SessionSummary::session).collect(Collectors.toSet());
        JsonArray sessions = new JsonArray();
        SessionSummary mind = stored.stream().filter(s -> s.session().equals(Mind.SESSION)).findFirst()
                .orElse(new SessionSummary(Mind.SESSION, 0, 0, 0, 0, false, "", "", ""));
        sessions.add(item(mind, names, busy.contains(Mind.SESSION)));
        for (String session : busy) {
            if (!known.contains(session) && !session.startsWith("mind:")) {
                sessions.add(item(new SessionSummary(session, 0, System.currentTimeMillis(), 0, 0, false, "", "", ""), names, true));
            }
        }
        for (SessionSummary s : stored) {
            if (!s.session().startsWith("mind:")) {
                sessions.add(item(s, names, busy.contains(s.session())));
            }
        }
        JsonObject body = new JsonObject();
        body.add("sessions", sessions);
        body.add("active", active);
        body.add("mood", gson.toJsonTree(agent.mood().current(agent.personas().activeId())));
        return body.toString();
    }

    private JsonObject item(SessionSummary s, QqDirectory.Names names, boolean busy) {
        JsonObject item = gson.toJsonTree(s).getAsJsonObject();
        String kind = s.session().substring(0, s.session().indexOf(':'));
        item.addProperty("kind", kind);
        item.addProperty("target", s.session().substring(s.session().indexOf(':') + 1));
        item.addProperty("name", name(s.session(), kind.equals("web") ? (s.title().isBlank() ? "新对话" : s.title()) : names.of(s.session())));
        item.addProperty("busy", busy);
        return item;
    }

    private String detail(String session) {
        SessionDetail detail = agent.session(session, DETAIL_LIMIT);
        JsonObject body = gson.toJsonTree(detail).getAsJsonObject();
        body.addProperty("name", name(session, session.startsWith("web:") ? "新对话" : directory.names().of(session)));
        body.addProperty("kind", session.substring(0, session.indexOf(':')));
        body.addProperty("target", session.substring(session.indexOf(':') + 1));
        if (session.startsWith("group:")) {
            body.add("speakers", gson.toJsonTree(directory.speakers(session.substring(6))));
        }
        body.add("self", gson.toJsonTree(directory.self(session)));
        JsonArray active = new JsonArray();
        for (JsonElement t : agent.monitor().snapshot()) {
            if (t.getAsJsonObject().get("session").getAsString().equals(session)) {
                active.add(t);
            }
        }
        body.add("active", active);
        body.addProperty("contextTokens", agent.contextTokens(session));
        body.addProperty("contextWindow", agent.contextWindow());
        body.addProperty("compactAt", agent.compactAt(session));
        return body.toString();
    }

    private void send(HttpExchange ex, String session) throws IOException {
        if (session.equals(Mind.SESSION)) {
            throw new IllegalArgumentException("内心里不能发消息");
        }
        SendRequest req = Requests.readJson(ex, SendRequest.class);
        String message = req.message() == null ? "" : req.message().strip();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("消息不能超过 " + MAX_MESSAGE_LENGTH + " 字");
        }
        if (agent.store().active() == null) {
            throw new IllegalArgumentException("还没有选择模型");
        }
        boolean deliver = !session.startsWith("web:") && !Boolean.FALSE.equals(req.deliver());
        bridge.consoleTurn(session, message, deliver);
        HttpResponses.json(ex, 202, "{}");
    }

    private void command(HttpExchange ex, String session) throws IOException {
        CommandRequest req = Requests.readJson(ex, CommandRequest.class);
        String name = req.command() == null ? "" : req.command().strip().toLowerCase(Locale.ROOT);
        String args = req.args() == null ? "" : req.args().strip();
        JsonObject body = new JsonObject();
        switch (name) {
            case "clear" -> {
                agent.reset(session);
                log.info("agent [" + session + "] /clear from the console");
                body.addProperty("message", "已清空上下文，机器人会忘掉之前聊过的内容");
            }
            case "compact" -> {
                if (agent.store().active() == null) {
                    throw new IllegalArgumentException("还没有选择模型");
                }
                try {
                    ChatAgent.Compacted result = agent.compact(session);
                    log.info("agent [" + session + "] /compact: ~" + result.before() + " -> ~" + result.after() + " tokens");
                    body.addProperty("message", "已压缩上下文：约 " + tokens(result.before()) + " → " + tokens(result.after()) + " token");
                } catch (AgentException e) {
                    HttpResponses.json(ex, 502, error(e.kind() == AgentException.Kind.CANCELLED ? "压缩已停止" : "压缩失败：" + e.getMessage()));
                    return;
                }
            }
            case "stop" -> {
                int stopped = agent.stop(session);
                body.addProperty("message", stopped > 0 ? "已停止正在进行的回复" : "这个会话现在没有在回复");
            }
            case "status" -> body.addProperty("message", status(session));
            case "reflect" -> {
                if (session.startsWith("web:") || session.equals(Mind.SESSION)) {
                    throw new IllegalArgumentException("/reflect 只能在 QQ 会话里用");
                }
                try {
                    Reflection.Outcome outcome = agent.reflection().reflect(session);
                    body.addProperty("message", outcome == null ? "这个会话正在回顾中，稍等一下" : MemoryHandler.describe(outcome));
                } catch (AgentException e) {
                    HttpResponses.json(ex, 502, error("回顾失败：" + e.getMessage()));
                    return;
                }
            }
            case "say" -> {
                if (session.equals(Mind.SESSION)) {
                    throw new IllegalArgumentException("/say 只能在 QQ 会话里用");
                }
                if (args.isEmpty()) {
                    throw new IllegalArgumentException("用法：/say 要发的话");
                }
                if (args.length() > MAX_MESSAGE_LENGTH) {
                    throw new IllegalArgumentException("消息不能超过 " + MAX_MESSAGE_LENGTH + " 字");
                }
                try {
                    bridge.say(session, args);
                } catch (ToolFailure e) {
                    HttpResponses.json(ex, 502, error("发送失败：" + e.getMessage()));
                    return;
                }
                body.addProperty("message", "已用机器人的身份发到 QQ");
            }
            default -> throw new IllegalArgumentException("没有这个命令：/" + name + "，输入 /help 查看可用命令");
        }
        HttpResponses.json(ex, body.toString());
    }

    private String status(String session) {
        SessionDetail detail = agent.session(session, 1);
        ModelProfile model = agent.store().active();
        Persona persona = agent.personas().active();
        List<String> lines = new java.util.ArrayList<>();
        lines.add("会话：" + directory.names().of(session) + "（" + session + "）");
        lines.add("记录：" + detail.totalTurns() + " 条" + (detail.summary() == null || detail.summary().isBlank() ? "" : "，另有压缩摘要"));
        lines.add("上下文：" + (detail.measuredTokens() > 0 ? "约 " + tokens(detail.measuredTokens()) + " token（上次实测）" : "还没有实测数据"));
        lines.add("压缩次数：" + detail.compactions());
        lines.add("模型：" + (model == null ? "未选择" : model.name() + "（" + model.model() + "）"));
        lines.add("人格：" + (persona == null ? "不使用人格" : persona.name()));
        lines.add("状态：" + (agent.monitor().busy(session) ? "正在回复" : "空闲"));
        return String.join("\n", lines);
    }

    private static String name(String session, String fallback) {
        return session.equals(Mind.SESSION) ? "内心" : fallback;
    }

    private static String tokens(long n) {
        return n >= 10_000 ? Math.round(n / 1000.0) + "k" : n >= 1000 ? String.format("%.1fk", n / 1000.0) : String.valueOf(n);
    }

    private void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        BlockingQueue<JsonObject> queue = new LinkedBlockingQueue<>(10_000);
        SessionMonitor monitor = agent.monitor();
        Runnable unsubscribe = monitor.subscribe(queue::offer);
        try (SseWriter sse = new SseWriter(ex.getResponseBody())) {
            sse.send("ready", new JsonObject());
            while (true) {
                JsonObject event = queue.poll(15, TimeUnit.SECONDS);
                if (event == null) {
                    sse.comment("ping");
                } else {
                    sse.send(event.get("type").getAsString(), event);
                }
            }
        } catch (UncheckedIOException | IOException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            unsubscribe.run();
        }
    }

    private static String session(String raw) {
        if (!SESSION.matcher(raw).matches()) {
            throw new IllegalArgumentException("会话格式不对");
        }
        return raw;
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body.toString();
    }
}
