package dafeiyu.agent.model;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.protocol.ChatRequest;
import dafeiyu.agent.protocol.Protocols;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dafeiyu.util.Texts.abbreviate;

public final class ModelFailover {

    public static final long PROBE_EVERY_MS = TimeUnit.MINUTES.toMillis(30);
    private static final Set<Kind> TRIGGERS = EnumSet.of(Kind.QUOTA, Kind.AUTH, Kind.RATE_LIMIT, Kind.PROVIDER_INTERNAL, Kind.TRANSPORT,
            Kind.TIMEOUT, Kind.INCOMPLETE_STREAM, Kind.UNKNOWN);

    private final AgentStore store;
    private volatile Consumer<String> log = line -> {};
    private ScheduledExecutorService scheduler;

    public ModelFailover(AgentStore store) {
        this.store = store;
    }

    public void setLog(Consumer<String> log) {
        this.log = log == null ? line -> {} : log;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("model-failover").factory());
        scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.MINUTES);
    }

    public boolean failedOver() {
        return store.failover() != null;
    }

    public boolean ready(ModelProfile profile) {
        ModelProfile primary = store.primary();
        return profile != null && primary != null && primary.id().equals(profile.id()) && store.fallbackId() != null && store.failover() == null;
    }

    public boolean handle(AgentException e, ModelProfile used) {
        if (e == null || !TRIGGERS.contains(e.kind()) || used == null) {
            return false;
        }
        ModelProfile primary = store.primary();
        if (primary == null || !primary.id().equals(used.id()) || store.fallbackId() == null) {
            return false;
        }
        if (store.failover() != null) {
            return true;
        }
        String reason = e.kind().name().toLowerCase() + ": " + abbreviate(String.valueOf(e.getMessage()), 200);
        if (!store.failOver(reason)) {
            return false;
        }
        ModelProfile backup = store.active();
        log.accept("model: " + primary.name() + " failed (" + reason + "), switched to backup " + (backup == null ? "?" : backup.name())
                + "; will probe it every 30 minutes");
        return true;
    }

    private void tick() {
        try {
            AgentStore.Failover f = store.failover();
            if (f != null && System.currentTimeMillis() - f.probedAt() >= PROBE_EVERY_MS) {
                probeNow();
            }
        } catch (RuntimeException e) {
            log.accept("model: probing the main model crashed: " + e);
        }
    }

    public synchronized String probeNow() {
        ModelProfile primary = store.primary();
        if (primary == null) {
            return "没有选中的主模型";
        }
        String result;
        try {
            Protocols.get(primary.protocol()).stream(primary,
                    ChatRequest.of(List.of(ChatMessage.user("连通性测试，回复 ok 两个字母就行")), "", 256), StreamListener.NONE);
            result = "";
        } catch (AgentException e) {
            result = e.kind() == Kind.EMPTY_REPLY ? "" : e.kind().name().toLowerCase() + ": " + abbreviate(String.valueOf(e.getMessage()), 200);
        }
        if (result.isEmpty()) {
            if (store.restorePrimary()) {
                log.accept("model: main model " + primary.name() + " answered again, switched back from the backup");
            }
            return "";
        }
        if (store.failover() == null) {
            boolean switched = store.failOver(result);
            log.accept("model: probe of main model " + primary.name() + " failed (" + result + ")" + (switched ? ", switched to the backup" : ""));
            return result;
        }
        store.probed(result);
        log.accept("model: main model " + primary.name() + " still failing (" + result + "), staying on the backup");
        return result;
    }

    public boolean restore() {
        boolean was = store.restorePrimary();
        if (was) {
            log.accept("model: switched back to the main model by hand");
        }
        return was;
    }
}
