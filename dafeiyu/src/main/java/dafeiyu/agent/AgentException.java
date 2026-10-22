package dafeiyu.agent;

public class AgentException extends Exception {

    public enum Kind {
        CONTEXT_OVERFLOW,
        PAYLOAD_TOO_LARGE,
        CONTENT_POLICY,
        QUOTA,
        AUTH,
        RATE_LIMIT,
        PROVIDER_INTERNAL,
        INVALID_REQUEST,
        TRANSPORT,
        TIMEOUT,
        INCOMPLETE_STREAM,
        EMPTY_REPLY,
        UNKNOWN,
        LOCAL,
        CANCELLED
    }

    public static AgentException cancelled() {
        return new AgentException(Kind.CANCELLED, "stopped from the console");
    }

    private final Kind kind;
    private final long retryAfterMs;
    private final Boolean shouldRetry;

    public AgentException(String message) {
        this(Kind.LOCAL, message);
    }

    public AgentException(Kind kind, String message) {
        this(kind, message, -1, null);
    }

    public AgentException(Kind kind, String message, long retryAfterMs, Boolean shouldRetry) {
        super(message);
        this.kind = kind;
        this.retryAfterMs = retryAfterMs;
        this.shouldRetry = shouldRetry;
    }

    public Kind kind() {
        return kind;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }

    public boolean retryable() {
        if (shouldRetry != null) {
            return shouldRetry;
        }
        return switch (kind) {
            case RATE_LIMIT, PROVIDER_INTERNAL, TRANSPORT, TIMEOUT, INCOMPLETE_STREAM, UNKNOWN -> true;
            default -> false;
        };
    }

    public boolean isContextOverflow() {
        return kind == Kind.CONTEXT_OVERFLOW;
    }
}
