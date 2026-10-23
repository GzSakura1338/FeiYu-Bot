package dafeiyu.agent;

import com.google.gson.Gson;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;

public final class SubagentSettings {

    public record Config(boolean enabled, boolean research, boolean build, int mindLimit, int qqLimit, int researchMinutes,
                         int buildMinutes, int dailyLimit, String profileId) {

        static final Config DEFAULT = new Config(false, true, false, 2, 2, 10, 30, 0, "");

        Config normalized() {
            return new Config(enabled, research, build, clamp(mindLimit, 1, 10), clamp(qqLimit, 1, 10), clamp(researchMinutes, 1, 120),
                    clamp(buildMinutes, 1, 240), clamp(dailyLimit, 0, 1000), profileId == null ? "" : profileId.strip());
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final String KEY = "agent.subagents";
    private static final Gson GSON = new Gson();

    private final Settings settings;
    private volatile Config config;

    SubagentSettings(Database db) {
        this.settings = new Settings(db);
        Config saved;
        try {
            saved = GSON.fromJson(settings.get(KEY), Config.class);
        } catch (RuntimeException e) {
            saved = null;
        }
        this.config = saved == null ? Config.DEFAULT : saved.normalized();
    }

    public Config config() {
        return config;
    }

    public synchronized Config update(Config next) {
        config = (next == null ? Config.DEFAULT : next).normalized();
        settings.put(KEY, GSON.toJson(config));
        return config;
    }
}
