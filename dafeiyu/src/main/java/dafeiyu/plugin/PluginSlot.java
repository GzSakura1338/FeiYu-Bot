package dafeiyu.plugin;

import dafeiyu.plugin.manifest.PluginManifest;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

final class PluginSlot {

    static final String INVALID = "invalid";
    static final String INCOMPATIBLE = "incompatible";
    static final String STOPPED = "stopped";
    static final String NEEDS_APPROVAL = "needs-approval";
    static final String STARTING = "starting";
    static final String RUNNING = "running";
    static final String RESTARTING = "restarting";
    static final String CRASHED = "crashed";
    static final int SLOW_LIMIT = 3;
    static final long SLOW_PAUSE_MS = 5 * 60_000;

    final String id;
    final Path folder;
    final String origin;
    volatile PluginManifest manifest;
    volatile List<String> problems = List.of();
    volatile PluginProcess process;
    volatile String status = STOPPED;
    volatile String lastError = "";
    volatile List<PluginManifest.Tool> tools = List.of();
    volatile long startupMillis = -1;
    volatile long runningSince;
    final List<String> warnings = new CopyOnWriteArrayList<>();
    final Deque<Long> crashes = new ArrayDeque<>();
    final Deque<String> lastStderr = new ArrayDeque<>();
    final Deque<Long> sendTimes = new ArrayDeque<>();
    final Map<String, LocalDateTime> nextFire = new ConcurrentHashMap<>();
    int backoff;
    ScheduledFuture<?> restart;
    long folderStamp;
    long logSecond;
    int logCount;
    int logSuppressed;
    private int slowStreak;
    private long pausedUntil;

    PluginSlot(String id, Path folder, String origin) {
        this.id = id;
        this.folder = folder;
        this.origin = origin;
    }

    boolean running() {
        PluginProcess p = process;
        return RUNNING.equals(status) && p != null && p.alive();
    }

    String name() {
        PluginManifest m = manifest;
        return m == null ? id : m.name();
    }

    synchronized boolean paused(long now) {
        return now < pausedUntil;
    }

    synchronized boolean slow(long now) {
        slowStreak++;
        if (slowStreak >= SLOW_LIMIT) {
            slowStreak = 0;
            pausedUntil = now + SLOW_PAUSE_MS;
            return true;
        }
        return false;
    }

    synchronized void fast() {
        slowStreak = 0;
    }

    synchronized void resume() {
        slowStreak = 0;
        pausedUntil = 0;
    }

    synchronized void stderr(String line) {
        lastStderr.addLast(line);
        while (lastStderr.size() > 20) {
            lastStderr.removeFirst();
        }
    }

    synchronized String recentStderr() {
        return String.join("\n", lastStderr);
    }

    synchronized boolean admitLog(long now) {
        long second = now / 1000;
        if (second != logSecond) {
            logSecond = second;
            logCount = 0;
        }
        if (logCount >= PluginHost.LOG_LINES_PER_SECOND) {
            logSuppressed++;
            return false;
        }
        logCount++;
        return true;
    }

    synchronized int takeSuppressed() {
        int n = logSuppressed;
        logSuppressed = 0;
        return n;
    }
}
