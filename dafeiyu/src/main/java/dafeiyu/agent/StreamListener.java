package dafeiyu.agent;

public interface StreamListener {

    StreamListener NONE = new StreamListener() {};

    default void onText(String delta) {}

    default void onReasoning(String delta) {}

    default void onCompaction(String reason) {}

    default void onRetry(int attempt, long delayMs, String error) {}

    default void onToolCall(ToolCall call) {}

    default void onToolResult(ToolCall call, String output, boolean error) {}

    default void onBlocked(String category, boolean byModel, String replacement) {}

    default void onSaved() {}
}
