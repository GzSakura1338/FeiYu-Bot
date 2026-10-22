package dafeiyu.agent.model;

import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class AgentStore {

    private static final String ACTIVE_KEY = "agent.active";
    private static final String QQ_REPLY_KEY = "agent.qqReply";
    private static final String GROUP_MODE_KEY = "agent.groupMode";
    private static final String WAKE_WORDS_KEY = "agent.wakeWords";
    private static final String FREE_WILL_KEY = "agent.idleChat";
    private static final String MIND_PACE_KEY = "agent.mindPace";
    private static final List<String> PACES = List.of("fast", "normal", "slow");
    private static final String EFFORT_KEY = "agent.reasoningEffort";
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
            throw new IllegalArgumentException("思考节奏只能是 fast、normal 或 slow");
        }
        settings.put(MIND_PACE_KEY, pace);
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
