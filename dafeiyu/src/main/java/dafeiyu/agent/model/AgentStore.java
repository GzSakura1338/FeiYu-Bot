package dafeiyu.agent.model;

import com.google.gson.JsonParseException;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentStore {

    private static final String ACTIVE_KEY = "agent.active";
    private static final String HOUSEKEEPING_KEY = "agent.housekeeping";
    private static final String QQ_REPLY_KEY = "agent.qqReply";
    private static final String GROUP_MODE_KEY = "agent.groupMode";
    private static final String WAKE_WORDS_KEY = "agent.wakeWords";
    private static final String FREE_WILL_KEY = "agent.idleChat";
    private static final String MIND_PACE_KEY = "agent.mindPace";
    private static final List<String> PACES = List.of("fast", "normal", "slow", "custom");
    private static final String MIND_PACE_SECONDS_KEY = "agent.mindPaceSeconds";
    private static final String MIND_NIGHT_KEY = "agent.mindNight";
    public static final int MIN_PACE_SECONDS = 15;
    public static final int MAX_PACE_SECONDS = 3600;
    private static final int DEFAULT_PACE_SECONDS = 300;
    private static final String EFFORT_KEY = "agent.reasoningEffort";
    private static final String REPLYING_KEY = "agent.replying";
    private static final String PACING_KEY = "agent.pacing";
    private static final String BLOCK_ENABLED_KEY = "agent.block.enabled";
    private static final String BLOCK_THRESHOLD_KEY = "agent.block.threshold";
    private static final String BLOCK_LIST_KEY = "agent.block.list";
    private static final String BLOCK_STRIKES_KEY = "agent.block.strikes";
    public static final Pacing DEFAULT_PACING = new Pacing(2_000, 4);
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    private static final List<String> EFFORTS = List.of("default", "none", "low", "medium", "high", "max");
    private static final List<String> DEFAULT_WAKE_WORDS = List.of("小鲸鱼", "大肥鱼");
    private static final String COLUMNS = "id, name, protocol, base_url, api_key, model, temperature, max_tokens, context_window";

    private final Database db;
    private final Settings settings;
    private volatile boolean qqReply;
    private volatile boolean groupMode;

    public AgentStore(Database db) {
        this.db = db;
        this.settings = new Settings(db);
        this.qqReply = "true".equals(settings.get(QQ_REPLY_KEY));
        this.groupMode = "true".equals(settings.get(GROUP_MODE_KEY));
    }

    public synchronized List<ModelProfile> profiles() {
        return db.list("SELECT " + COLUMNS + " FROM model_profiles ORDER BY sort, created_at", AgentStore::map);
    }

    public synchronized ModelProfile get(String id) {
        return db.one("SELECT " + COLUMNS + " FROM model_profiles WHERE id = ?", AgentStore::map, id);
    }

    public synchronized String activeId() {
        String id = settings.get(ACTIVE_KEY);
        return id == null || id.isEmpty() ? null : id;
    }

    public synchronized ModelProfile active() {
        String id = activeId();
        return id == null ? null : get(id);
    }

    public synchronized ModelProfile add(ModelProfile profile) {
        ModelProfile created = profile.withId(UUID.randomUUID().toString().substring(0, 8));
        db.update("""
                INSERT INTO model_profiles (id, name, protocol, base_url, api_key, model, temperature,
                                            max_tokens, context_window, sort, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort), 0) + 1 FROM model_profiles), ?)
                """, created.id(), created.name(), created.protocol(), created.baseUrl(), created.apiKey(), created.model(),
                created.temperature(), created.maxTokens(), created.contextWindow(),
                System.currentTimeMillis());
        if (active() == null) {
            setActive(created.id());
        }
        return created;
    }

    public synchronized boolean update(ModelProfile profile) {
        return db.update("""
                UPDATE model_profiles SET name = ?, protocol = ?, base_url = ?, api_key = ?, model = ?,
                                          temperature = ?, max_tokens = ?, context_window = ?
                WHERE id = ?
                """, profile.name(), profile.protocol(), profile.baseUrl(), profile.apiKey(), profile.model(),
                profile.temperature(), profile.maxTokens(), profile.contextWindow(), profile.id()) > 0;
    }

    public synchronized boolean remove(String id) {
        return db.update("DELETE FROM model_profiles WHERE id = ?", id) > 0;
    }

    public Boolean vision(String id) {
        return capability("vision", id);
    }

    public void setVision(String id, Boolean supported) {
        setCapability("vision", id, supported);
    }

    public Boolean video(String id) {
        return capability("video", id);
    }

    public void setVideo(String id, Boolean supported) {
        setCapability("video", id, supported);
    }

    public Boolean systemRole(String id) {
        return capability("system_role", id);
    }

    public void setSystemRole(String id, Boolean supported) {
        setCapability("system_role", id, supported);
    }

    public Boolean toolCall(String id) {
        return capability("tool_call", id);
    }

    public void setToolCall(String id, Boolean supported) {
        setCapability("tool_call", id, supported);
    }

    public void resetCapabilities(String id) {
        setVision(id, null);
        setSystemRole(id, null);
        setToolCall(id, null);
    }

    private synchronized Boolean capability(String column, String id) {
        Integer value = db.one("SELECT " + column + " FROM model_profiles WHERE id = ?", rs -> {
            int v = rs.getInt(1);
            return rs.wasNull() ? null : v;
        }, id);
        return value == null ? null : value == 1;
    }

    private synchronized void setCapability(String column, String id, Boolean supported) {
        db.update("UPDATE model_profiles SET " + column + " = ? WHERE id = ?", supported == null ? null : supported ? 1 : 0, id);
    }

    public synchronized void setActive(String id) {
        settings.put(ACTIVE_KEY, id);
    }

    public boolean qqReply() {
        return qqReply;
    }

    public boolean groupMode() {
        return groupMode;
    }

    public synchronized void setGroupMode(boolean enabled) {
        settings.put(GROUP_MODE_KEY, String.valueOf(enabled));
        groupMode = enabled;
    }

    public boolean freeWill() {
        return "true".equals(settings.get(FREE_WILL_KEY));
    }

    public synchronized void setFreeWill(boolean enabled) {
        settings.put(FREE_WILL_KEY, String.valueOf(enabled));
    }

    public String mindPace() {
        String value = settings.get(MIND_PACE_KEY);
        return value == null || !PACES.contains(value) ? "normal" : value;
    }

    public synchronized void setMindPace(String pace) {
        if (!PACES.contains(pace)) {
            throw new IllegalArgumentException("思考节奏只能是 fast、normal、slow 或 custom");
        }
        settings.put(MIND_PACE_KEY, pace);
    }

    public int mindPaceSeconds() {
        try {
            String value = settings.get(MIND_PACE_SECONDS_KEY);
            return value == null ? DEFAULT_PACE_SECONDS : Math.clamp(Integer.parseInt(value.strip()), MIN_PACE_SECONDS, MAX_PACE_SECONDS);
        } catch (NumberFormatException e) {
            return DEFAULT_PACE_SECONDS;
        }
    }

    public synchronized void setMindPaceSeconds(int seconds) {
        if (seconds < MIN_PACE_SECONDS || seconds > MAX_PACE_SECONDS) {
            throw new IllegalArgumentException("自定义间隔要在 " + MIN_PACE_SECONDS + " 秒到 " + MAX_PACE_SECONDS / 60 + " 分钟之间");
        }
        settings.put(MIND_PACE_SECONDS_KEY, String.valueOf(seconds));
    }

    public record MindNight(boolean enabled, String start, String end, int seconds) {

        public MindNight {
            start = time(start, "00:00");
            end = time(end, "08:00");
            seconds = seconds <= 0 ? 0 : Math.clamp(seconds, MIN_PACE_SECONDS, MAX_PACE_SECONDS);
        }

        private static String time(String value, String fallback) {
            try {
                return value == null ? fallback : LocalTime.parse(value.strip()).toString();
            } catch (DateTimeParseException e) {
                return fallback;
            }
        }

        public boolean covers(LocalTime now) {
            LocalTime from = LocalTime.parse(start);
            LocalTime to = LocalTime.parse(end);
            if (!enabled || from.equals(to)) {
                return false;
            }
            return from.isBefore(to) ? !now.isBefore(from) && now.isBefore(to) : !now.isBefore(from) || now.isBefore(to);
        }

        public long millisUntilEnd(LocalDateTime now) {
            LocalDateTime end = now.toLocalDate().atTime(LocalTime.parse(this.end));
            if (!end.isAfter(now)) {
                end = end.plusDays(1);
            }
            return Duration.between(now, end).toMillis();
        }
    }

    public MindNight mindNight() {
        String raw = settings.get(MIND_NIGHT_KEY);
        if (raw != null && !raw.isBlank()) {
            try {
                MindNight saved = GSON.fromJson(raw, MindNight.class);
                if (saved != null) {
                    return new MindNight(saved.enabled(), saved.start(), saved.end(), saved.seconds());
                }
            } catch (JsonParseException ignored) {
            }
        }
        return new MindNight(false, "00:00", "08:00", 0);
    }

    public synchronized MindNight setMindNight(MindNight night) {
        MindNight clean = new MindNight(night.enabled(), night.start(), night.end(), night.seconds());
        settings.put(MIND_NIGHT_KEY, GSON.toJson(clean));
        return clean;
    }

    public boolean blockEnabled() {
        return Boolean.parseBoolean(settings.get(BLOCK_ENABLED_KEY));
    }

    public void setBlockEnabled(boolean on) {
        settings.put(BLOCK_ENABLED_KEY, Boolean.toString(on));
    }

    public int blockThreshold() {
        try {
            return Math.max(0, Integer.parseInt(settings.get(BLOCK_THRESHOLD_KEY)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public void setBlockThreshold(int n) {
        settings.put(BLOCK_THRESHOLD_KEY, Integer.toString(Math.max(0, n)));
    }

    public synchronized Map<String, Map<String, Object>> blockList() {
        return blockJson(BLOCK_LIST_KEY);
    }

    public synchronized void setBlockList(Map<String, Map<String, Object>> list) {
        settings.put(BLOCK_LIST_KEY, GSON.toJson(list == null ? Map.of() : list));
    }

    public synchronized Map<String, Integer> blockStrikes() {
        Map<String, Integer> out = new LinkedHashMap<>();
        String raw = settings.get(BLOCK_STRIKES_KEY);
        if (raw != null && !raw.isBlank()) {
            try {
                Map<String, Integer> parsed = GSON.fromJson(raw,
                        com.google.gson.reflect.TypeToken.getParameterized(Map.class, String.class, Integer.class).getType());
                if (parsed != null) {
                    parsed.forEach((k, v) -> out.put(k, v == null ? 0 : v));
                }
            } catch (JsonParseException ignored) {
            }
        }
        return out;
    }

    public synchronized void setBlockStrikes(Map<String, Integer> strikes) {
        settings.put(BLOCK_STRIKES_KEY, GSON.toJson(strikes == null ? Map.of() : strikes));
    }

    private Map<String, Map<String, Object>> blockJson(String key) {
        String raw = settings.get(key);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (raw != null && !raw.isBlank()) {
            try {
                Map<String, Map<String, Object>> parsed = GSON.fromJson(raw,
                        com.google.gson.reflect.TypeToken.getParameterized(Map.class, String.class, Map.class).getType());
                if (parsed != null) {
                    out.putAll(parsed);
                }
            } catch (JsonParseException ignored) {
            }
        }
        return out;
    }

    public record Replying(String session, String userId, String messageId, String speaker, String line, long at) {}

    public synchronized void markReplying(Replying replying) {
        Map<String, Replying> all = replyingMap();
        all.put(replying.session(), replying);
        settings.put(REPLYING_KEY, GSON.toJson(all.values()));
    }

    public synchronized void clearReplying(String session) {
        Map<String, Replying> all = replyingMap();
        if (all.remove(session) != null) {
            settings.put(REPLYING_KEY, GSON.toJson(all.values()));
        }
    }

    public synchronized List<Replying> takeReplying() {
        List<Replying> all = List.copyOf(replyingMap().values());
        settings.put(REPLYING_KEY, "[]");
        return all;
    }

    private Map<String, Replying> replyingMap() {
        Map<String, Replying> out = new LinkedHashMap<>();
        String raw = settings.get(REPLYING_KEY);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        try {
            Replying[] list = GSON.fromJson(raw, Replying[].class);
            if (list != null) {
                for (Replying r : list) {
                    if (r != null && r.session() != null) {
                        out.put(r.session(), r);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    public record Housekeeping(boolean enabled, int messagesDays, int usageDays, String schedule, long lastRun) {

        public static final Housekeeping OFF = new Housekeeping(false, 90, 365, "manual", 0L);

        public Housekeeping {
            messagesDays = Math.max(7, Math.min(3650, messagesDays));
            usageDays = Math.max(7, Math.min(3650, usageDays));
            schedule = schedule == null || schedule.isBlank()
                    ? "manual" : switch (schedule) {
                        case "daily", "weekly", "monthly" -> schedule;
                        default -> "manual";
                    };
        }

        public boolean due(long now) {
            if (!enabled || schedule.equals("manual")) {
                return false;
            }
            long gap = switch (schedule) {
                case "daily" -> 86_400_000L;
                case "weekly" -> 7 * 86_400_000L;
                case "monthly" -> 30 * 86_400_000L;
                default -> Long.MAX_VALUE;
            };
            return now - lastRun >= gap;
        }
    }

    public record Pacing(int intervalMs, int maxPieces) {

        public String problem() {
            if (intervalMs < 500 || intervalMs > 30_000) return "分段间隔要在 0.5～30 秒之间";
            if (maxPieces < 1 || maxPieces > 8) return "最多分几条要在 1～8 之间";
            return null;
        }
    }

    public Housekeeping housekeeping() {
        String raw = settings.get(HOUSEKEEPING_KEY);
        if (raw != null && !raw.isBlank()) {
            try {
                Housekeeping saved = GSON.fromJson(raw, Housekeeping.class);
                if (saved != null) {
                    return saved;
                }
            } catch (JsonParseException ignored) {
            }
        }
        return Housekeeping.OFF;
    }

    public synchronized Housekeeping setHousekeeping(Housekeeping cfg) {
        settings.put(HOUSEKEEPING_KEY, GSON.toJson(cfg));
        return cfg;
    }

    public synchronized void markHousekeepingRun() {
        Housekeeping cur = housekeeping();
        settings.put(HOUSEKEEPING_KEY, GSON.toJson(
                new Housekeeping(cur.enabled(), cur.messagesDays(), cur.usageDays(), cur.schedule(), System.currentTimeMillis())));
    }

    public Pacing pacing() {
        try {
            Pacing saved = GSON.fromJson(settings.get(PACING_KEY), Pacing.class);
            return saved == null || saved.problem() != null ? DEFAULT_PACING : saved;
        } catch (RuntimeException e) {
            return DEFAULT_PACING;
        }
    }

    public synchronized void setPacing(Pacing pacing) {
        String problem = pacing.problem();
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        settings.put(PACING_KEY, GSON.toJson(pacing));
    }

    public String reasoningEffort() {
        String value = settings.get(EFFORT_KEY);
        return value == null || !EFFORTS.contains(value) ? "default" : value;
    }

    public synchronized void setReasoningEffort(String effort) {
        if (!EFFORTS.contains(effort)) {
            throw new IllegalArgumentException("思考强度只能是 " + String.join("、", EFFORTS));
        }
        settings.put(EFFORT_KEY, effort);
    }

    public List<String> wakeWords() {
        String value = settings.get(WAKE_WORDS_KEY);
        return value == null || value.isBlank() ? DEFAULT_WAKE_WORDS : List.of(value.split(","));
    }

    public synchronized void setQqReply(boolean enabled) {
        settings.put(QQ_REPLY_KEY, String.valueOf(enabled));
        qqReply = enabled;
    }

    private static ModelProfile map(ResultSet rs) throws SQLException {
        double temperature = rs.getDouble("temperature");
        Double temp = rs.wasNull() ? null : temperature;
        int context = rs.getInt("context_window");
        Integer window = rs.wasNull() ? null : context;
        return new ModelProfile(rs.getString("id"), rs.getString("name"), rs.getString("protocol"), rs.getString("base_url"),
                rs.getString("api_key"), rs.getString("model"), temp, rs.getInt("max_tokens"), window);
    }
}
