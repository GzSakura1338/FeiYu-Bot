package dafeiyu.agent.protocol;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class StreamCollector {

    static final class PartialCall {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }

    private final StreamListener listener;
    private final long start = System.nanoTime();
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, PartialCall> calls = new TreeMap<>();
    private String model;
    private String finishReason = "";
    private boolean finished;
    int promptTokens = -1;
    int completionTokens = -1;
    int cachedTokens = -1;

    StreamCollector(StreamListener listener, String model) {
        this.listener = listener;
        this.model = model;
    }

    void text(String delta) {
        if (delta != null && !delta.isEmpty()) {
            text.append(delta);
            listener.onText(delta);
        }
    }

    void reasoning(String delta) {
        if (delta != null && !delta.isEmpty()) {
            reasoning.append(delta);
            listener.onReasoning(delta);
        }
    }

    PartialCall call(int index) {
        return calls.computeIfAbsent(index, i -> new PartialCall());
    }

    void addCall(String id, String name, String arguments) {
        PartialCall call = call(calls.size());
        call.id = id;
        call.name = name;
        call.arguments.append(arguments);
    }

    int callCount() {
        return calls.size();
    }

    void model(String value) {
        if (value != null && !value.isEmpty()) {
            model = value;
        }
    }

    void finish(String reason) {
        finished = true;
        if (reason != null && !reason.isEmpty()) {
            finishReason = reason;
        }
    }

    String finishReason() {
        return finishReason;
    }

    ChatResult result(String lengthReason) throws AgentException {
        if (!finished) {
            throw new AgentException(Kind.INCOMPLETE_STREAM, "the stream ended before the reply finished");
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        for (Map.Entry<Integer, PartialCall> e : calls.entrySet()) {
            PartialCall c = e.getValue();
            if (!c.name.isEmpty()) {
                toolCalls.add(new ToolCall(c.id.isEmpty() ? "call_" + e.getKey() : c.id, c.name, c.arguments.toString()));
            }
        }
        String content = text.toString().strip();
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new AgentException(Kind.EMPTY_REPLY, finishReason.equals(lengthReason)
                    ? "empty reply: output hit the token limit (reasoning may have used it all), raise it or leave it empty"
                    : "empty reply from model");
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        return new ChatResult(content, reasoning.toString().strip(), toolCalls, model, latencyMs,
                promptTokens, completionTokens, cachedTokens, finishReason);
    }
}
