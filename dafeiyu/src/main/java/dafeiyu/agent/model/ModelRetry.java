package dafeiyu.agent.model;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.StreamListener;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

public final class ModelRetry {

    @FunctionalInterface
    public interface Attempt<T> {
        T run() throws AgentException;
    }

    private static final long BASE_MS = 2_000;
    private static final long GAP_MAX_MS = 10_000;
    private static final int MAX_RETRIES = 10;
    private static final int MAX_TIMEOUT_RETRIES = 3;

    private final StreamListener listener;
    private final BooleanSupplier cancelled;
    private int retries;
    private int timeouts;

    public ModelRetry(StreamListener listener, BooleanSupplier cancelled) {
        this.listener = listener;
        this.cancelled = cancelled;
    }

    public <T> T run(Attempt<T> attempt) throws AgentException {
        while (true) {
            try {
                return attempt.run();
            } catch (AgentException e) {
                backoff(e);
            }
        }
    }

    public void backoff(AgentException e) throws AgentException {
        if (cancelled.getAsBoolean()) {
            throw AgentException.cancelled();
        }
        long delay = decide(e);
        if (delay < 0) {
            throw e;
        }
        listener.onRetry(retries, delay, e.getMessage());
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AgentException(Kind.LOCAL, "interrupted while waiting to retry");
        }
    }

    private long decide(AgentException e) {
        if (!e.retryable() || retries >= MAX_RETRIES) {
            return -1;
        }
        if (e.kind() == Kind.TIMEOUT && ++timeouts > MAX_TIMEOUT_RETRIES) {
            return -1;
        }
        long gap = Math.min(BASE_MS << Math.min(retries, 20), GAP_MAX_MS);
        retries++;
        long jittered = Math.round(gap * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4));
        return Math.max(jittered, e.retryAfterMs());
    }
}
