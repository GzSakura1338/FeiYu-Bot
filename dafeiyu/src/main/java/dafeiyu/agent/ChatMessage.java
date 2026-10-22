package dafeiyu.agent;

import java.util.List;

public record ChatMessage(
        String role,
        String content,
        String reasoning,
        List<ImagePart> images,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName,
        boolean toolError) {

    public ChatMessage {
        content = content == null ? "" : content;
        reasoning = reasoning == null ? "" : reasoning;
        images = images == null ? List.of() : List.copyOf(images);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolCallId = toolCallId == null ? "" : toolCallId;
        toolName = toolName == null ? "" : toolName;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, "", null, null, null, null, false);
    }

    public static ChatMessage user(String content) {
        return user(content, List.of());
    }

    public static ChatMessage user(String content, List<ImagePart> images) {
        return new ChatMessage("user", content, "", images, null, null, null, false);
    }

    public static ChatMessage assistant(String content, String reasoning) {
        return assistant(content, reasoning, List.of());
    }

    public static ChatMessage assistant(String content, String reasoning, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, reasoning, null, toolCalls, null, null, false);
    }

    public static ChatMessage tool(ToolCall call, String output, boolean error) {
        return new ChatMessage("tool", output, "", null, null, call.id(), call.name(), error);
    }

    public boolean isUser() {
        return role.equals("user");
    }

    public boolean isAssistant() {
        return role.equals("assistant");
    }

    public boolean isTool() {
        return role.equals("tool");
    }

    public ChatMessage withoutImages(String replacement) {
        if (images.isEmpty()) {
            return this;
        }
        StringBuilder text = new StringBuilder(content);
        for (int i = 0; i < images.size(); i++) {
            text.append(text.isEmpty() ? "" : "\n").append(replacement);
        }
        return new ChatMessage(role, text.toString(), reasoning, null, toolCalls, toolCallId, toolName, toolError);
    }

    public ChatMessage withoutReasoning() {
        return reasoning.isEmpty() ? this
                : new ChatMessage(role, content, "", images, toolCalls, toolCallId, toolName, toolError);
    }
}
