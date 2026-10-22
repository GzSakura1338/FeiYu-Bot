package dafeiyu.agent.qq;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Predicate;

import static dafeiyu.util.Texts.abbreviate;

final class GroupState {

    record Line(String messageId, String userId, String speaker, String text, long time, boolean addressed, List<String> imageUrls, boolean seen) {

        Line asSeen() {
            return seen ? this : new Line(messageId, userId, speaker, text, time, addressed, imageUrls, true);
        }
    }

    private static final int MAX_LINES = 40;
    private static final long MAX_AGE_MS = 30 * 60 * 1000;
    private static final int QUOTE_CHARS = 30;
    private static final long BURST_WINDOW_MS = 60_000;
    private static final int BURST_LIMIT = 3;
    private static final long STREAK_GAP_MS = 3 * 60_000;
    private static final int STREAK_LIMIT = 4;
    private static final long STREAK_COOLDOWN_MS = 5 * 60_000;

    final String groupId;
    private final Deque<Line> buffer = new ArrayDeque<>();
    private final Map<String, String> speakers = lru(200);
    private final Map<String, String> names = lru(200);
    private final Map<String, String> recent = lru(300);
    private final Map<String, Boolean> botMessages = lru(100);
    private final Deque<Long> botTimes = new ArrayDeque<>();
    private long lastBotAt;
    private String streakUser = "";
    private int streak;
    private long streakAt;
    private long coolingUntil;
    boolean busy;
    boolean pendingAddressed;
    ScheduledFuture<?> ambient;
    long ambientDeadline;
    ScheduledFuture<?> calm;
    boolean calmAddressed;

    GroupState(String groupId) {
        this.groupId = groupId;
    }

    synchronized String label(String name, String userId, boolean owner, Predicate<String> reserved) {
        String clean = QqMessages.displayName(name);
        if (clean.isEmpty()) {
            return userId;
        }
        if (owner) {
            return clean;
        }
        String holder = speakers.get(clean);
        return (holder != null && !holder.equals(userId)) || reserved.test(clean) ? clean + "#" + tail(userId) : clean;
    }

    synchronized String mentionName(String userId, String hint, boolean owner, Predicate<String> reserved) {
        String known = names.get(userId);
        return known != null ? known : label(hint, userId, owner, reserved);
    }

    synchronized void heard(String messageId, String userId, String name, String line) {
        speakers.put(name, userId);
        names.put(userId, name);
        if (!messageId.isEmpty()) {
            recent.put(messageId, line);
        }
    }

    synchronized long calmIn() {
        long now = System.currentTimeMillis();
        List<Long> burst = botTimes.stream().filter(t -> now - t < BURST_WINDOW_MS).toList();
        if (burst.size() < BURST_LIMIT) {
            return 0;
        }
        return burst.get(burst.size() - BURST_LIMIT) + BURST_WINDOW_MS - now;
    }

    synchronized int spokenWithin(long ms) {
        long now = System.currentTimeMillis();
        return (int) botTimes.stream().filter(t -> now - t < ms).count();
    }

    synchronized String quote(String messageId) {
        String line = recent.get(messageId);
        return "（回复 " + (line == null ? "一条消息" : abbreviate(line.replace('\n', ' '), QUOTE_CHARS)) + "）";
    }

    synchronized void repliedTo(String userId) {
        long now = System.currentTimeMillis();
        streak = userId.equals(streakUser) && now - streakAt < STREAK_GAP_MS ? streak + 1 : 1;
        streakUser = userId;
        streakAt = now;
        if (streak >= STREAK_LIMIT) {
            coolingUntil = now + STREAK_COOLDOWN_MS;
        }
    }

    synchronized boolean talkingWith(String userId, long withinMs) {
        return userId.equals(streakUser) && System.currentTimeMillis() - streakAt < withinMs;
    }

    synchronized boolean cooling(String userId) {
        return userId.equals(streakUser) && System.currentTimeMillis() < coolingUntil;
    }

    synchronized boolean isBotMessage(String messageId) {
        return !messageId.isEmpty() && botMessages.containsKey(messageId);
    }

    synchronized void spoke(String messageId, String text) {
        long now = System.currentTimeMillis();
        lastBotAt = now;
        botTimes.addLast(now);
        while (now - botTimes.peekFirst() > 3_600_000) {
            botTimes.pollFirst();
        }
        if (messageId != null && !messageId.isEmpty()) {
            botMessages.put(messageId, true);
            recent.put(messageId, "你：" + text);
        }
    }

    synchronized void add(Line line) {
        buffer.addLast(line);
        prune();
    }

    synchronized List<Line> drain() {
        prune();
        List<Line> lines = new ArrayList<>(buffer);
        buffer.clear();
        return lines;
    }

    synchronized void restore(List<Line> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            buffer.addFirst(lines.get(i).asSeen());
        }
        prune();
    }

    synchronized boolean hasNewLines() {
        return buffer.stream().anyMatch(l -> !l.seen());
    }

    synchronized String know(String userId, String name, Predicate<String> reserved) {
        String known = names.get(userId);
        if (known != null) {
            return known;
        }
        String label = label(name, userId, false, reserved);
        speakers.putIfAbsent(label, userId);
        names.put(userId, label);
        return label;
    }

    synchronized Map<String, String> speakers() {
        return Map.copyOf(speakers);
    }

    synchronized long lastSpoke() {
        return lastBotAt;
    }

    synchronized Map<String, String> roster(List<String> userIds) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String qq : userIds) {
            String name = names.get(qq);
            if (name != null) {
                out.put(name, qq);
            }
        }
        return out;
    }

    private void prune() {
        long now = System.currentTimeMillis();
        while (!buffer.isEmpty() && (buffer.size() > MAX_LINES || now - buffer.peekFirst().time() > MAX_AGE_MS)) {
            buffer.pollFirst();
        }
    }

    private static String tail(String userId) {
        return userId.length() <= 4 ? userId : userId.substring(userId.length() - 4);
    }

    private static <V> Map<String, V> lru(int max) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        };
    }
}
