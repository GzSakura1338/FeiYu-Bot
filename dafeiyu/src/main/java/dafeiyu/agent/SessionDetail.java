package dafeiyu.agent;

import java.util.List;

public record SessionDetail(
        String session,
        String summary,
        String recent,
        int compactions,
        int measuredTokens,
        int totalTurns,
        List<Entry> turns) {

    public record Entry(long id, String role, String content, String reasoning, List<ToolCall> toolCalls, String toolCallId,
                        boolean toolError, long createdAt, String attachments) {}
}
