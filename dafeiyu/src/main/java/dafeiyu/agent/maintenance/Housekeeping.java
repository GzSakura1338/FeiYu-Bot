package dafeiyu.agent.maintenance;

import dafeiyu.agent.model.AgentStore;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.db.Database;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 历史记录清理（消息归档 + 用量日志）。
 *
 * <p>两条底线：
 * <ol>
 *   <li><b>默认只演练</b>：{@link #plan()} 只统计会删多少条，{@link #run()} 才真删，且必须显式要求；</li>
 *   <li><b>硬下限</b>：不管设置里写多少天，最近 {@link #HARD_FLOOR_DAYS} 天内的记录永远不删 ——
 *       模型判断错也伤不到眼前的数据。</li>
 * </ol>
 *
 * <p>另外「记录清理」总开关关着时，两个入口都直接拒绝，不看调用方是谁。
 */
public final class Housekeeping {

    /** 硬下限：最近这么多天的记录谁都删不了。 */
    public static final int HARD_FLOOR_DAYS = 7;

    /** 演练结果：按当前设置会删掉多少条。 */
    public record Plan(long messages, long usage, int messagesDays, int usageDays, String note) {}

    /** 实际执行结果。 */
    public record Result(long messages, long usage, int messagesDays, int usageDays) {}

    private final Database db;
    private final AgentStore store;

    public Housekeeping(Database db, AgentStore store) {
        this.db = db;
        this.store = store;
    }

    public Plan plan() throws ToolFailure {
        AgentStore.Housekeeping cfg = store.housekeeping();
        if (!cfg.enabled()) {
            throw new ToolFailure("记录清理关着：要让主人到「设置 › Agent › 记录清理」里打开");
        }
        int md = Math.max(HARD_FLOOR_DAYS, cfg.messagesDays());
        int ud = Math.max(HARD_FLOOR_DAYS, cfg.usageDays());
        String note = md != cfg.messagesDays() || ud != cfg.usageDays()
                ? "（设置写的是 " + cfg.messagesDays() + "/" + cfg.usageDays() + " 天，但最近 "
                        + HARD_FLOOR_DAYS + " 天的不能删，按较大的算）"
                : "";
        return new Plan(count("messages", "time", md), count("token_usage", "at", ud), md, ud, note);
    }

    public Result run() throws ToolFailure {
        Plan p = plan();
        long m = cut("messages", "time", p.messagesDays());
        long u = cut("token_usage", "at", p.usageDays());
        store.markHousekeepingRun();
        return new Result(m, u, p.messagesDays(), p.usageDays());
    }

    /** 只统计，不删。 */
    public long pending(String table, String column, int days) {
        return count(table, column, days);
    }

    private long count(String table, String column, int days) {
        Long n = db.one("SELECT COUNT(*) FROM " + table + " WHERE " + column + " < ?", rs -> rs.getLong(1), since(days));
        return n == null ? 0L : n;
    }

    private long cut(String table, String column, int days) {
        return db.update("DELETE FROM " + table + " WHERE " + column + " < ?", since(days));
    }

    private static long since(int days) {
        return LocalDate.now().minusDays(days).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
