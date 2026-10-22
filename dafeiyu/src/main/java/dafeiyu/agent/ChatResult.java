package dafeiyu.agent;

import java.util.List;

public record ChatResult(
        String content,
        String reasoning,
        List<ToolCall> toolCalls,
        String model,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        int cachedTokens,
        String finishReason) {

    public ChatResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ChatMessage message() {
        return ChatMessage.assistant(content, reasoning, toolCalls);
    }
}
