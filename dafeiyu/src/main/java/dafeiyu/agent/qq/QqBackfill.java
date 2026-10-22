package dafeiyu.agent.qq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;

final class QqBackfill {

    private static final int PER_CHAT = 30;
    private static final int MAX_PEERS = 10;
    private static final int MAX_CHATS = 10;
    private static final long ACTIVE_SINCE_MS = 7 * 86_400_000L;
    private static final long MIN_RERUN_MS = 30_000;
    private static final long MAX_GAP_MS = 6 * 3_600_000L;
    private static final long PEERS_SINCE_MS = 7 * 86_400_000L;
    private static final long REPLY_WINDOW_MS = 10 * 60_000L;

    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long ranAt;

    private final ChatAgent agent;
    private final MessageArchive archive;
    private final QqOutbox outbox;
    private final LogBuffer log;

    QqBackfill(ChatAgent agent, MessageArchive archive, QqOutbox outbox, LogBuffer log) {
        this.agent = agent;
        this.archive = archive;
        this.outbox = outbox;
        this.log = log;
    }

    void run(BiConsumer<JsonObject, Boolean> ingest) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            long last = archive.lastIncomingAt();
            long now = System.currentTimeMillis();
            if (last <= 0 || now - ranAt < MIN_RERUN_MS) {
                return;
            }
            ranAt = now;
            long from = Math.max(last - 5_000, now - MAX_GAP_MS);
            String selfId = outbox.selfId();
            Set<String> known = archive.messageIdsSince(from - 60_000);
            List<JsonObject> missed = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (String groupId : archive.activeGroups(now - ACTIVE_SINCE_MS, MAX_CHATS)) {
                collect(missed, known, failed, "get_group_msg_history", "group_id", groupId, "group", from, selfId);
            }
            for (String userId : peers(now)) {
                collect(missed, known, failed, "get_friend_msg_history", "user_id", userId, "private", from, selfId);
            }
            if (!failed.isEmpty()) {
                log.warn("agent: catching up skipped " + failed.size() + " chat(s): " + String.join("; ", failed.subList(0, Math.min(3, failed.size()))));
            }
            if (missed.isEmpty()) {
                log.info("agent: nothing missed while offline");
                return;
            }
            missed.sort(Comparator.comparingLong(QqBackfill::millis));
            int replied = 0;
            for (JsonObject event : missed) {
                boolean react = now - millis(event) <= REPLY_WINDOW_MS;
                replied += react ? 1 : 0;
                archive.onEvent(event);
                ingest.accept(event, react);
            }
            log.info("agent: caught up on " + missed.size() + " message(s) received while offline (" + replied + " recent enough to answer)");
        } finally {
            running.set(false);
        }
    }

    private Set<String> peers(long now) {
        Set<String> peers = new LinkedHashSet<>(archive.privatePeers(now - PEERS_SINCE_MS, MAX_PEERS));
        peers.addAll(agent.personas().owners());
        return peers;
    }

    private void collect(List<JsonObject> into, Set<String> known, List<String> failed, String action, String key, String id, String type,
                         long from, String selfId) {
        JsonObject params = new JsonObject();
        try {
            params.addProperty(key, Long.parseLong(id));
        } catch (NumberFormatException e) {
            return;
        }
        params.addProperty("message_seq", 0);
        params.addProperty("count", PER_CHAT);
        params.addProperty("reverseOrder", false);
        JsonObject data;
        try {
            data = optObject(outbox.call(action, params, Duration.ofSeconds(10)), "data");
        } catch (ToolFailure | RuntimeException e) {
            failed.add(type + " " + id + ": " + e.getMessage());
            return;
        }
        if (data == null || !data.has("messages") || !data.get("messages").isJsonArray()) {
            return;
        }
        for (JsonElement element : data.getAsJsonArray("messages")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject event = element.getAsJsonObject();
            String sender = optString(event, "user_id");
            if (millis(event) < from || sender.isEmpty() || sender.equals(selfId) || !known.add(optString(event, "message_id"))) {
                continue;
            }
            event.addProperty("post_type", "message");
            event.addProperty("message_type", type);
            if (!event.has("self_id")) {
                event.addProperty("self_id", selfId);
            }
            if (type.equals("group") && !event.has("group_id")) {
                event.addProperty("group_id", id);
            }
            into.add(event);
        }
    }

    private static long millis(JsonObject event) {
        try {
            long t = event.get("time").getAsLong();
            return t < 10_000_000_000L ? t * 1000 : t;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
