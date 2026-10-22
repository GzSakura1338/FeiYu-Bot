package dafeiyu.agent;

public record SessionSummary(
        String session,
        int turns,
        long lastActive,
        int compactions,
        int measuredTokens,
        boolean hasCheckpoint,
        String preview,
        String previewRole,
        String title) {}
