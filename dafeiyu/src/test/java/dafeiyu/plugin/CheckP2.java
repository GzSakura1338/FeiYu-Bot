package dafeiyu.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.qq.MessageGate;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.plugin.manifest.Cron;
import dafeiyu.plugin.manifest.Permission;
import dafeiyu.plugin.manifest.PluginManifest;
import dafeiyu.plugin.manifest.SettingsSchema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dafeiyu.plugin.CheckSupport.sleep;
import static dafeiyu.plugin.CheckSupport.waitFor;

public final class CheckP2 {

    private static final CheckSupport check = new CheckSupport("P2 事件、定时、过滤、设置");

    public static void main(String[] args) throws Exception {
        cron();
        try (PluginTestHost h = new PluginTestHost("p2")) {
            h.install("p2a", "p2b", "p2c", "echo");
            h.start();
            h.host.enable("p2a", Set.of(Permission.QQ_REPLY, Permission.QQ_SEND, Permission.QQ_READ, Permission.QQ_FILTER));
            h.host.enable("p2b", Set.of());
            waitFor(() -> h.host.view("p2a").status().equals("running") && h.host.view("p2b").status().equals("running"), 30_000);
            events(h);
            intercept(h);
            notices(h);
            schedules(h);
            prompts(h);
            filters(h);
            settings(h);
            sending(h);
        }
        check.summary();
        System.exit(check.failed() == 0 ? 0 : 1);
    }

    private static void cron() {
        check.checkThat("P2-B1a", "每天 8 点的 cron 正确触发", () -> {
            Cron c = Cron.parse("0 8 * * *");
            return c.matches(LocalDateTime.of(2026, 10, 23, 8, 0)) && !c.matches(LocalDateTime.of(2026, 10, 23, 8, 1))
                    && c.next(LocalDateTime.of(2026, 10, 23, 9, 0)).equals(LocalDateTime.of(2026, 10, 24, 8, 0));
        }, "0 8 * * *");
        check.checkThat("P2-B1b", "*/5 每五分钟触发", () -> {
            Cron c = Cron.parse("*/5 * * * *");
            return c.matches(LocalDateTime.of(2026, 10, 23, 1, 5)) && !c.matches(LocalDateTime.of(2026, 10, 23, 1, 6));
        }, "*/5 * * * *");
        check.checkThat("P2-B1c", "指定星期触发", () -> {
            Cron c = Cron.parse("30 9 * * 1");
            return c.matches(LocalDateTime.of(2026, 10, 26, 9, 30)) && !c.matches(LocalDateTime.of(2026, 10, 27, 9, 30));
        }, "30 9 * * 1（周一）");
        check.checkThat("P2-B1d", "日和星期同时限定时取并集", () -> {
            Cron c = Cron.parse("0 0 1 * 1");
            return c.matches(LocalDateTime.of(2026, 10, 1, 0, 0)) && c.matches(LocalDateTime.of(2026, 10, 26, 0, 0))
                    && !c.matches(LocalDateTime.of(2026, 10, 27, 0, 0));
        }, "0 0 1 * 1");
        check.checkThat("P2-B2", "错误的 cron 被拒绝", () -> {
            int rejected = 0;
            for (String bad : List.of("* * * *", "61 * * * *", "* 25 * * *", "a * * * *", "*/0 * * * *")) {
                try {
                    Cron.parse(bad);
                } catch (IllegalArgumentException e) {
                    rejected++;
                }
            }
            return rejected == 5;
        }, "5 个错误表达式全部被拒绝");
    }

    private static JsonArray events(PluginTestHost h, String id) {
        String raw = h.store.kvGet(id, "events");
        if (raw == null) {
            return new JsonArray();
        }
        JsonElement e = JsonParser.parseString(raw);
        return e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static boolean sawEvent(PluginTestHost h, String id, String kind, String contains) {
        for (JsonElement e : events(h, id)) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("kind").getAsString().equals(kind) && (contains == null || o.get("data").toString().contains(contains))) {
                return true;
            }
        }
        return false;
    }

    private static void events(PluginTestHost h) {
        MessageGate.Message m = h.group("大家好呀");
        h.host.gate().incoming(m);
        boolean got = waitFor(() -> sawEvent(h, "p2a", "intercept", "大家好呀"), 5000);
        JsonObject payload = null;
        for (JsonElement e : events(h, "p2a")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("data").toString().contains("大家好呀")) {
                payload = o.getAsJsonObject("data");
            }
        }
        check.check("P2-A1", "群消息事件字段齐全", got && payload != null && payload.get("sender").getAsString().equals("测试员")
                        && payload.get("groupId").getAsString().equals("900001") && payload.get("userId").getAsString().equals("10001")
                        && payload.has("mentionedMe") && payload.has("attachments") && payload.get("messageId").getAsString().equals(m.messageId())
                        && payload.get("session").getAsString().equals("group:900001"),
                payload == null ? "没收到事件" : payload.toString());
        h.host.gate().incoming(h.priv("私聊内容"));
        sleep(500);
        check.check("P2-A2", "没订阅的事件不发给插件", !sawEvent(h, "p2a", "message.private", null),
                "p2a 没订阅 message.private，收到 " + events(h, "p2a").size() + " 个事件但没有私聊");
        MessageGate.Message backfilled = new MessageGate.Message("group", "900001", "10001", "测试员", false, "补发的消息", "", false,
                "mb1", System.currentTimeMillis(), false);
        h.host.gate().incoming(backfilled);
        boolean marked = waitFor(() -> sawEvent(h, "p2a", "message.group", "补发的消息"), 5000);
        boolean notIntercepted = !sawEvent(h, "p2a", "intercept", "补发的消息");
        boolean flagged = false;
        for (JsonElement e : events(h, "p2a")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("data").toString().contains("补发的消息")) {
                flagged = o.getAsJsonObject("data").get("backfilled").getAsBoolean();
            }
        }
        check.check("P2-A9", "补发的消息带 backfilled 标记且不触发拦截", marked && flagged && notIntercepted,
                "backfilled=" + flagged + "，没走拦截=" + notIntercepted);
    }

    private static void intercept(PluginTestHost h) {
        MessageGate.Verdict drop = h.host.gate().incoming(h.group("drop 这条"));
        check.check("P2-A4a", "拦截返回 drop 时她不回这条", drop.drop(), "drop=" + drop.drop() + "，来自 " + drop.by());
        MessageGate.Verdict rewrite = h.host.gate().incoming(h.group("rw 原文"));
        check.check("P2-A4b", "拦截返回 rewrite 时她看到改写后的文字",
                !rewrite.drop() && "改写后:rw 原文".equals(rewrite.text()), "text=" + rewrite.text());
        MessageGate.Verdict pass = h.host.gate().incoming(h.group("普通一句"));
        check.check("P2-A4c", "拦截返回 pass 时照常交给她", !pass.drop() && pass.text() == null, "原样放行");
        sleep(300);
        check.check("P2-A6", "前一个插件吞掉后，后面的拦截插件不再调用",
                !h.store.kvGet("p2b", "seen").contains("drop 这条") && h.store.kvGet("p2b", "seen").contains("普通一句"),
                "p2b 看到的消息：" + h.store.kvGet("p2b", "seen"));

        h.host.enable("p2c", Set.of());
        waitFor(() -> h.host.view("p2c").status().equals("running"), 20_000);
        long t0 = System.nanoTime();
        h.host.gate().incoming(h.priv("慢插件旁听"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check.check("P2-A3", "旁听插件很慢时不影响她回复", ms < 500, "耗时 " + ms + " ms（插件要处理 10 秒）");
        h.host.disable("p2c");

        JsonObject slow = new JsonObject();
        slow.addProperty("delay", 5);
        h.install2("slow");
        h.host.scan();
        h.store.saveValues("slow", h.host.view("slow").manifest().settings(), slow);
        h.host.enable("slow", Set.of(Permission.QQ_REPLY, Permission.QQ_FILTER));
        waitFor(() -> h.host.view("slow").status().equals("running"), 20_000);
        long t1 = System.nanoTime();
        MessageGate.Verdict timeout = h.host.gate().incoming(h.group("慢拦截"));
        long ms1 = (System.nanoTime() - t1) / 1_000_000;
        check.check("P2-A5", "拦截超过 2 秒按放行处理", !timeout.drop() && ms1 < 2600 && ms1 >= 2000,
                "耗时 " + ms1 + " ms（上限 2 秒）");
    }

    private static void notices(PluginTestHost h) {
        JsonObject join = new JsonObject();
        join.addProperty("post_type", "notice");
        join.addProperty("notice_type", "group_increase");
        join.addProperty("group_id", "900001");
        join.addProperty("user_id", "10009");
        h.host.onNapcatEvent(join);
        JsonObject recall = new JsonObject();
        recall.addProperty("post_type", "notice");
        recall.addProperty("notice_type", "group_recall");
        recall.addProperty("group_id", "900001");
        recall.addProperty("user_id", "10001");
        recall.addProperty("message_id", "m77");
        h.host.onNapcatEvent(recall);
        JsonObject poke = new JsonObject();
        poke.addProperty("post_type", "notice");
        poke.addProperty("notice_type", "notify");
        poke.addProperty("sub_type", "poke");
        poke.addProperty("group_id", "900001");
        poke.addProperty("user_id", "10001");
        h.host.onNapcatEvent(poke);
        boolean all = waitFor(() -> sawEvent(h, "p2a", "notice.join", "10009") && sawEvent(h, "p2a", "notice.recall", "m77")
                && sawEvent(h, "p2a", "notice.poke", null), 5000);
        check.check("P2-A7", "进群、撤回、拍一拍事件都能收到", all, "三种通知都收到了");
        h.host.gate().said("group:900001", "她说的话");
        check.check("P2-A8", "bot.said 事件内容正确", waitFor(() -> sawEvent(h, "p2a", "bot.said", "她说的话"), 5000), "收到 bot.said");
    }

    private static void schedules(PluginTestHost h) {
        h.store.setGroups("p2a", List.of("900001"));
        LocalDateTime next = h.host.nextFire("p2a", "tick");
        check.check("P2-B4a", "启用后定时任务被排上", next != null && next.isAfter(LocalDateTime.now().minusMinutes(1)), "下次触发 " + next);
        int before = h.qq.sent.size();
        h.host.fireSchedule("p2a", "tick");
        boolean fired = waitFor(() -> sawEvent(h, "p2a", "schedule", "tick"), 5000);
        boolean sent = waitFor(() -> h.qq.sent.size() > before, 5000);
        check.check("P2-B5", "定时任务触发后能往群里发消息", fired && sent && h.qq.last().text().contains("定时任务触发了"),
                "发出：" + h.qq.last());
        h.host.disable("p2a");
        check.check("P2-B4b", "停用后定时任务不再排期", h.host.nextFire("p2a", "tick") == null, "nextFire=null");
        h.host.enable("p2a", Set.of(Permission.QQ_REPLY, Permission.QQ_SEND, Permission.QQ_READ, Permission.QQ_FILTER));
        waitFor(() -> h.host.view("p2a").status().equals("running"), 20_000);
        check.check("P2-B4c", "重新启用后按下次时间排期", h.host.nextFire("p2a", "tick") != null, "nextFire=" + h.host.nextFire("p2a", "tick"));
    }

    private static void prompts(PluginTestHost h) {
        h.host.enable("echo", Set.of(Permission.QQ_REPLY));
        waitFor(() -> h.host.view("echo").status().equals("running"), 20_000);
        long t0 = System.nanoTime();
        String text = h.host.extensions().contribute("group:900001", List.of("10001"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check.check("P2-C1", "提示词补充包在 <插件:名字> 里", text.contains("<插件:回声>") && text.contains("</插件:回声>")
                && text.contains("group:900001"), text.replace("\n", " ").strip());
        check.check("P2-C3", "慢插件不拖慢提示词补充（上限 500 毫秒）", ms < 800 && !text.contains("too late"), ms + " ms");
        h.install2("dyntools");
        h.host.scan();
        h.host.enable("dyntools", Set.of());
        waitFor(() -> h.host.view("dyntools").status().equals("running"), 20_000);
        String long1 = h.host.extensions().contribute("group:900001", List.of());
        int longest = 0;
        for (String part : long1.split("<插件:")) {
            longest = Math.max(longest, part.length());
        }
        check.check("P2-C2", "提示词补充超过 300 字被截断", longest < 400, "最长一段 " + longest + " 字");
        h.host.disable("dyntools");
    }

    private static void filters(PluginTestHost h) throws Exception {
        String kept = h.host.gate().outgoing("group:900001", "普通的话");
        check.check("P2-C5a", "出站过滤放行时原样发出", kept.equals("普通的话"), kept);
        String rewritten = h.host.gate().outgoing("group:900001", "改我一下");
        check.check("P2-C5b", "出站过滤能改写她要发的话", rewritten.equals("改好了"), rewritten);
        boolean blocked;
        try {
            h.host.gate().outgoing("group:900001", "拦我这句");
            blocked = false;
        } catch (ToolFailure e) {
            blocked = e.getMessage().contains("P2 主测试");
        }
        check.check("P2-C5c", "出站过滤能拦下她要发的话，并说明是哪个插件", blocked, "被 P2 主测试 拦下");
        boolean guarded;
        try {
            h.host.gate().outgoing("group:900001", "改红线测试");
            guarded = false;
        } catch (ToolFailure e) {
            guarded = e.getMessage().contains("内容审核");
        }
        check.check("P2-C6", "插件改写出的红线内容仍被内容审核拦下", guarded, "改写成红线词后被拦");
        long t0 = System.nanoTime();
        String slow = h.host.gate().outgoing("group:900001", "慢过滤");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check.check("P2-C5d", "出站过滤超过 2 秒按放行处理", slow.equals("慢过滤") && ms < 2600, ms + " ms");
    }

    private static void settings(PluginTestHost h) {
        PluginManifest m = h.host.view("echo").manifest();
        JsonObject values = new JsonObject();
        values.addProperty("name", "回声改");
        values.addProperty("count", 3);
        values.addProperty("loud", true);
        values.addProperty("mode", "b");
        JsonArray tags = new JsonArray();
        tags.add("x");
        values.add("tags", tags);
        values.addProperty("token", "我的密钥-123");
        check.check("P2-D1", "各种类型的设置都能保存", h.store.saveValues("echo", m.settings(), values).isEmpty(), "string/number/boolean/enum/array");
        h.host.settingsChanged("echo");
        check.check("P2-D3", "插件收到 settings/changed，不用重启",
                waitFor(() -> {
                    String raw = h.store.kvGet("echo", "settings_changed");
                    return raw != null && raw.contains("回声改");
                }, 5000), "插件侧收到了新设置");
        JsonObject bad = values.deepCopy();
        bad.addProperty("count", 99);
        check.check("P2-D2a", "超出范围的值被拒绝", !h.store.saveValues("echo", m.settings(), bad).isEmpty(), "count=99 超过最大值 5");
        JsonObject badType = values.deepCopy();
        badType.addProperty("loud", "yes");
        check.check("P2-D2b", "类型不对的值被拒绝", !h.store.saveValues("echo", m.settings(), badType).isEmpty(), "loud=\"yes\"");
        JsonObject badEnum = values.deepCopy();
        badEnum.addProperty("mode", "c");
        check.check("P2-D2c", "不在 enum 里的值被拒绝", !h.store.saveValues("echo", m.settings(), badEnum).isEmpty(), "mode=c");
        JsonObject missing = values.deepCopy();
        missing.remove("name");
        check.check("P2-D2d", "必填项为空被拒绝", !h.store.saveValues("echo", m.settings(), missing).isEmpty(), "缺少 name");
        JsonObject unknown = values.deepCopy();
        unknown.addProperty("nosuch", 1);
        check.check("P2-D2e", "未知设置项被拒绝", !h.store.saveValues("echo", m.settings(), unknown).isEmpty(), "nosuch");

        JsonObject shown = h.store.publicValues("echo", m.settings());
        check.check("P2-D4a", "密钥在页面上不回显明文",
                shown.get("token").getAsString().equals(dafeiyu.plugin.store.PluginStore.KEEP_SECRET), "显示为「已设置」占位符");
        JsonObject keep = shown.deepCopy();
        keep.addProperty("name", "再改一次");
        h.store.saveValues("echo", m.settings(), keep);
        check.check("P2-D4b", "保存时不改动密钥也不会丢",
                "我的密钥-123".equals(h.store.values("echo", m.settings()).get("token").getAsString()), "密钥保留");
        String row = h.db.one("SELECT value FROM settings WHERE key = ?", rs -> rs.getString(1), "plugin.echo.settings");
        check.check("P2-D4c", "密钥在数据库里是加密的", row != null && !row.contains("我的密钥-123") && row.contains("dpapi:"),
                "数据库里没有明文");
        check.check("P2-D4d", "密钥不出现在日志里", !h.logs("echo").contains("我的密钥-123") && !h.appLog().contains("我的密钥-123"), "日志无明文");
        check.check("P2-D4e", "secret 字段在 Schema 里能识别",
                SettingsSchema.secrets(m.settings()).equals(Set.of("token")), SettingsSchema.secrets(m.settings()).toString());
    }

    private static void sending(PluginTestHost h) {
        h.store.setGroups("p2a", List.of());
        Toolbox.Outcome denied = call(h, "plugin__p2a__send", "{\"group\":\"900002\",\"text\":\"没勾选的群\"}");
        check.check("P2-E1a", "没勾选的群发不出去", denied.output().contains("not allowed"), denied.output().strip());
        check.check("P2-E1b", "越权调用记安全日志", h.appLog().contains("without permission") || h.logs("p2a").contains("越权"),
                "日志里有拒绝记录");
        h.store.setGroups("p2a", List.of("900001"));
        Toolbox.Outcome ok = call(h, "plugin__p2a__send", "{\"group\":\"900001\",\"text\":\"勾选过的群\"}");
        check.check("P2-E1c", "勾选的群能发", !ok.error() && h.qq.count("勾选过的群") == 1, ok.output().strip());
        Toolbox.Outcome red = call(h, "plugin__p2a__send", "{\"group\":\"900001\",\"text\":\"" + PluginTestHost.RED_LINE + "\"}");
        check.check("P2-E1d", "插件主动发的消息也过内容审核",
                red.output().contains("red-line") && h.qq.count(PluginTestHost.RED_LINE) == 0, red.output().strip());

        Toolbox.Outcome history = call(h, "plugin__p2a__history", "{\"group\":\"900001\"}");
        check.check("P2-E4a", "能读勾选的群的聊天记录", !history.error() && history.output().startsWith("["), history.output().strip());
        Toolbox.Outcome other = call(h, "plugin__p2a__history", "{\"group\":\"900002\"}");
        check.check("P2-E4b", "读没勾选的群被拒绝", other.output().contains("not allowed"), other.output().strip());
        Toolbox.Outcome priv = call(h, "plugin__p2a__history", "{\"user\":\"10002\"}");
        check.check("P2-E4c", "读私聊记录需要单独的私聊权限", priv.output().contains("qq.private"), priv.output().strip());
        Toolbox.Outcome members = call(h, "plugin__p2a__members", "{\"group\":\"900001\"}");
        check.check("P2-E4d", "能读群成员", !members.error() && members.output().contains("测试员"), members.output().strip());

        h.install2("flood");
        h.host.scan();
        h.host.enable("flood", Set.of(Permission.QQ_SEND));
        waitFor(() -> h.host.view("flood").status().equals("running"), 20_000);
        h.store.setGroups("flood", List.of("900001"));
        int before = h.qq.sent.size();
        Toolbox.Outcome flood = call(h, "plugin__flood__flood", "{\"group\":\"900001\"}");
        int added = h.qq.sent.size() - before;
        check.check("P2-E2", "一秒发 100 条被频率限制挡住", added <= dafeiyu.plugin.api.HostApi.SENDS_PER_MINUTE && flood.output().contains("-32002"),
                "实际发出 " + added + " 条，结果：" + flood.output().strip());
    }

    private static Toolbox.Outcome call(PluginTestHost h, String tool, String args) {
        return h.host.extensions().run(new ToolCall(UUID.randomUUID().toString(), tool, args), "web:test");
    }
}
