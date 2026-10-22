package dafeiyu.onebot;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class PendingActions {

    record Pending(String echo, CompletableFuture<JsonObject> future) {}

    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    Pending register() {
        String echo = "c" + seq.incrementAndGet();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(echo, future);
        future.whenComplete((r, e) -> pending.remove(echo));
        return new Pending(echo, future);
    }

    boolean complete(String echo, JsonObject response) {
        CompletableFuture<JsonObject> future = pending.remove(echo);
        return future != null && future.complete(response);
    }

    void failAll(String reason) {
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException(reason)));
        pending.clear();
    }
}
