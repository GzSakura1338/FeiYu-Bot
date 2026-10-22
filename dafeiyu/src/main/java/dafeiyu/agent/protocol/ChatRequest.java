package dafeiyu.agent.protocol;

import dafeiyu.agent.ChatMessage;

import java.util.List;

public record ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, boolean toolChoiceNone, String cacheKey, int maxOutput,
                          String effort, String purpose) {

    public ChatRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        effort = effort == null || effort.equals("default") ? "" : effort;
        purpose = purpose == null ? "" : purpose;
    }

    public ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, boolean toolChoiceNone, String cacheKey, int maxOutput, String effort) {
        this(messages, tools, toolChoiceNone, cacheKey, maxOutput, effort, "");
    }

    public ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, boolean toolChoiceNone, String cacheKey, int maxOutput) {
        this(messages, tools, toolChoiceNone, cacheKey, maxOutput, "", "");
    }

    public static ChatRequest of(List<ChatMessage> messages, String cacheKey, int maxOutput) {
        return new ChatRequest(messages, List.of(), false, cacheKey, maxOutput);
    }

    public String cacheKey(int maxLength) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return null;
        }
        return cacheKey.length() <= maxLength ? cacheKey : cacheKey.substring(0, maxLength);
    }

    public int lastUserIndex() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isUser()) {
                return i;
            }
        }
        return -1;
    }
}
