package dafeiyu.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static dafeiyu.util.Texts.abbreviate;

public final class SessionMonitor {

    private static final int PREVIEW = 200;
    private static final int TOOL_OUTPUT = 4000;

    static final class Turn {
        final String id;
        final String session;
        final String source;
        final long startedAt = System.currentTimeMillis();
        final String prompt;
        final List<JsonObject> parts = new ArrayList<>();
        volatile boolean cancelled;
        volatile boolean saved;
        volatile String state = "queued";
        private Thread thread = Thread.currentThread();
        private boolean finished;

        Turn(String id, String session, String source, String prompt) {
            this.id = id;
            this.session = session;
            this.source = source;
            this.prompt = prompt;
        }

        boolean cancelled() {
            return cancelled;
        }
    }

    private final Map<String, Turn> turns = new ConcurrentHashMap<>();
    private final List<Consumer<JsonObject>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    public Runnable subscribe(Consumer<JsonObject> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    public int stop(String session) {
        int stopped = 0;
        for (Turn turn : turns.values()) {
            if (!turn.session.equals(session)) {
                continue;
            }
            synchronized (turn) {
                if (!turn.finished && !turn.cancelled) {
                    turn.cancelled = true;
                    turn.thread.interrupt();
                    stopped++;
                }
            }
        }
        return stopped;
    }

    public boolean busy(String session) {
        return turns.values().stream().anyMatch(t -> t.session.equals(session));
    }

    public JsonArray snapshot() {
        JsonArray list = new JsonArray();
        for (Turn turn : turns.values()) {
            JsonObject item = base("turn", turn);
            item.addProperty("state", turn.state);
            item.addProperty("source", turn.source);
            item.addProperty("prompt", turn.saved ? "" : turn.prompt);
            item.addProperty("startedAt", turn.startedAt);
            JsonArray parts = new JsonArray();
            synchronized (turn) {
                turn.parts.forEach(p -> parts.add(p.deepCopy()));
            }
            item.add("parts", parts);
            list.add(item);
        }
        return list;
    }

    Turn begin(ChatTurn chatTurn) {
        Turn turn = new Turn("t" + seq.incrementAndGet(), chatTurn.session(), chatTurn.source(),
                chatTurn.heard() ? "" : abbreviate(chatTurn.user().content(), PREVIEW));
        turns.put(turn.id, turn);
        JsonObject event = base("turn-start", turn);
        event.addProperty("source", turn.source);
        event.addProperty("prompt", turn.prompt);
        event.addProperty("startedAt", turn.startedAt);
        publish(event);
        return turn;
    }

    void running(Turn turn) {
        turn.state = "running";
        publish(base("turn-running", turn));
    }

    void end(Turn turn, ChatResult result, AgentException error) {
        synchronized (turn) {
            turn.finished = true;
            turn.thread = null;
        }
        turns.remove(turn.id);
        JsonObject event = base("turn-end", turn);
        event.addProperty("source", turn.source);
        event.addProperty("status", error == null ? "done" : error.kind() == AgentException.Kind.CANCELLED ? "stopped" : "error");
        if (result != null) {
            event.addProperty("content", result.content());
            event.addProperty("latencyMs", result.latencyMs());
        }
        if (error != null) {
            event.addProperty("error", error.getMessage());
        }
        publish(event);
    }

    StreamListener listener(Turn turn, StreamListener inner) {
        return new StreamListener() {
            @Override
            public void onSaved() {
                inner.onSaved();
                synchronized (turn) {
                    turn.saved = true;
                    turn.parts.clear();
                }
            }

            @Override
            public void onText(String delta) {
                inner.onText(delta);
                part(turn, "text", "delta", delta);
            }

            @Override
            public void onReasoning(String delta) {
                inner.onReasoning(delta);
                part(turn, "reasoning", "delta", delta);
            }

            @Override
            public void onCompaction(String reason) {
                inner.onCompaction(reason);
                part(turn, "compaction", "reason", reason);
            }

            @Override
            public void onRetry(int attempt, long delayMs, String error) {
                inner.onRetry(attempt, delayMs, error);
                JsonObject data = new JsonObject();
                data.addProperty("attempt", attempt);
                data.addProperty("delayMs", delayMs);
                data.addProperty("error", error);
                record(turn, "retry", data);
            }

            @Override
            public void onToolCall(ToolCall call) {
                inner.onToolCall(call);
                JsonObject data = new JsonObject();
                data.addProperty("id", call.id());
                data.addProperty("name", call.name());
                data.addProperty("arguments", call.arguments());
                record(turn, "tool-call", data);
            }

            @Override
            public void onToolResult(ToolCall call, String output, boolean error) {
                inner.onToolResult(call, output, error);
                JsonObject data = new JsonObject();
                data.addProperty("id", call.id());
                data.addProperty("output", abbreviate(output, TOOL_OUTPUT));
                data.addProperty("error", error);
                record(turn, "tool-result", data);
            }

            @Override
            public void onBlocked(String category, boolean byModel, String replacement) {
                inner.onBlocked(category, byModel, replacement);
                JsonObject data = new JsonObject();
                data.addProperty("category", category);
                data.addProperty("byModel", byModel);
                data.addProperty("replacement", replacement);
                record(turn, "blocked", data);
            }
        };
    }

    private void part(Turn turn, String type, String key, String value) {
        synchronized (turn) {
            JsonObject last = turn.parts.isEmpty() ? null : turn.parts.getLast();
            if (last != null && last.get("type").getAsString().equals(type) && last.has(key) && !type.equals("compaction")) {
                last.addProperty(key, last.get(key).getAsString() + value);
            } else {
                JsonObject part = new JsonObject();
                part.addProperty("type", type);
                part.addProperty(key, value);
                turn.parts.add(part);
            }
        }
        JsonObject event = base(type, turn);
        event.addProperty(key, value);
        publish(event);
    }

    private void record(Turn turn, String type, JsonObject data) {
        JsonObject part = data.deepCopy();
        part.addProperty("type", type);
        synchronized (turn) {
            if (type.equals("retry")) {
                dropCurrentStep(turn);
            }
            turn.parts.add(part);
        }
        JsonObject event = base(type, turn);
        data.entrySet().forEach(e -> event.add(e.getKey(), e.getValue()));
        publish(event);
    }

    private static void dropCurrentStep(Turn turn) {
        while (!turn.parts.isEmpty()) {
            String type = turn.parts.getLast().get("type").getAsString();
            if (!type.equals("text") && !type.equals("reasoning") && !type.equals("retry")) {
                break;
            }
            turn.parts.removeLast();
        }
    }

    private static JsonObject base(String type, Turn turn) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("session", turn.session);
        event.addProperty("turnId", turn.id);
        return event;
    }

    void heard(String session, String text, String userId, String attachments) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "heard");
        event.addProperty("session", session);
        event.addProperty("text", text);
        event.addProperty("userId", userId == null ? "" : userId);
        event.addProperty("attachments", attachments == null ? "" : attachments);
        event.addProperty("time", System.currentTimeMillis());
        publish(event);
    }

    void said(String session, String content) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "said");
        event.addProperty("session", session);
        event.addProperty("content", content);
        event.addProperty("time", System.currentTimeMillis());
        publish(event);
    }

    private void publish(JsonObject event) {
        for (Consumer<JsonObject> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
