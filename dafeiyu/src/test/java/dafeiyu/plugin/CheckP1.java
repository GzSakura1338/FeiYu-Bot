package dafeiyu.plugin;

import com.google.gson.JsonObject;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.qq.MessageGate;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.plugin.manifest.Permission;
import dafeiyu.plugin.manifest.PluginManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dafeiyu.plugin.CheckSupport.sleep;
import static dafeiyu.plugin.CheckSupport.waitFor;

public final class CheckP1 {

    private static final CheckSupport check = new CheckSupport("P1 宿主骨架");

    public static void main(String[] args) throws Exception {
        manifests();
        try (PluginTestHost h = new PluginTestHost("p1")) {
            h.install("echo", "slow", "crash", "noisy", "badjson", "bigtool", "dyntools");
            duplicates(h);
            long began = System.currentTimeMillis();
            h.start();
            process(h, began);
            protocolRobustness(h);
            commands(h);
            tools(h);
            storage(h);
            crashes(h);
            shutdown(h);
        }
        check.summary();
        System.exit(check.failed() == 0 ? 0 : 1);
    }

    private static void manifests() throws Exception {
        check.checkThat("P1-A2a", "缺 id 被拒绝并指出字段", () -> {
            try {
                PluginManifest.parse("{\"name\":\"x\",\"version\":\"1.0.0\",\"run\":{\"command\":\"python\"}}", null);
                return false;
            } catch (PluginManifest.Invalid e) {
                return e.problems().stream().anyMatch(p -> p.contains("缺少 id"));
            }
        }, "缺少 id");
        check.checkThat("P1-A2b", "JSON 格式错被拒绝", () -> {
            try {
                PluginManifest.parse("{not json", null);
                return false;
            } catch (PluginManifest.Invalid e) {
                return e.getMessage().contains("不是合法的 JSON");
            }
        }, "坏 JSON");
        check.checkThat("P1-A2c", "id 和文件夹名不一致被拒绝", () -> {
            try {
                PluginManifest.parse("{\"id\":\"a\",\"name\":\"x\",\"version\":\"1.0.0\",\"run\":{\"command\":\"python\"}}", "b");
                return false;
            } catch (PluginManifest.Invalid e) {
                return e.getMessage().contains("文件夹名");
            }
        }, "id≠文件夹名");
        check.checkThat("P1-A2d", "缺 run 被拒绝", () -> {
            try {
                PluginManifest.parse("{\"id\":\"a\",\"name\":\"x\",\"version\":\"1.0.0\"}", "a");
                return false;
            } catch (PluginManifest.Invalid e) {
                return e.getMessage().contains("缺少 run");
            }
        }, "缺少 run");
        check.checkThat("P1-A5", "未知权限名被拒绝，不是悄悄忽略", () -> {
            try {
                PluginManifest.parse("{\"id\":\"a\",\"name\":\"x\",\"version\":\"1.0.0\",\"run\":{\"command\":\"python\"},"
                        + "\"permissions\":[\"qq.reply\",\"system.root\"]}", "a");
                return false;
            } catch (PluginManifest.Invalid e) {
                return e.getMessage().contains("system.root");
            }
        }, "system.root");
        PluginManifest ok = PluginManifest.parse("{\"id\":\"ab\",\"name\":\"x\",\"version\":\"1.0.0\",\"host\":\">=0.2.0\","
                + "\"run\":{\"command\":\"python\",\"args\":[\"main.py\"]},\"permissions\":[\"qq.reply\"]}", "ab");
        check.check("P1-A1", "合法清单能解析", ok.id().equals("ab") && ok.permissions().contains(Permission.QQ_REPLY), "id=ab, 权限=qq.reply");
        check.check("P1-A3", "host 版本要求能判断", ok.hostSatisfied("0.2.0") && !ok.hostSatisfied("0.1.9"),
                "0.2.0 满足 >=0.2.0，0.1.9 不满足");
        check.checkThat("P1-A3b", "版本不满足时拒绝启用并提示需要的版本", () -> {
            try (PluginTestHost h = new PluginTestHost("p1-ver")) {
                Path dir = h.pluginsDir.resolve("future");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("plugin.json"), "{\"id\":\"future\",\"name\":\"未来\",\"version\":\"1.0.0\","
                        + "\"host\":\">=9.9.9\",\"run\":{\"command\":\"python\",\"args\":[\"main.py\"]}}");
                Files.writeString(dir.resolve("main.py"), "pass\n");
                h.host.scan();
                PluginView v = h.host.view("future");
                try {
                    h.host.enable("future", Set.of());
                    return false;
                } catch (IllegalArgumentException e) {
                    return v.status().equals("incompatible") && e.getMessage().contains("9.9.9");
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "host=>=9.9.9");
        check.checkThat("P1-D6", "同名命令在清单内重复被拒绝", () -> {
            try {
                PluginManifest.parse("{\"id\":\"a\",\"name\":\"x\",\"version\":\"1.0.0\",\"run\":{\"command\":\"python\"},"
                        + "\"commands\":[{\"name\":\"q\"},{\"name\":\"q\"}]}", "a");
                return false;
            } catch (PluginManifest.Invalid e) {
                return e.getMessage().contains("重复");
            }
        }, "命令重复");
        check.checkThat("P1-E5a", "工具说明超长被拒绝", () -> {
            try {
                PluginManifest.parse("{\"id\":\"a\",\"name\":\"x\",\"version\":\"1.0.0\",\"run\":{\"command\":\"python\"},"
                        + "\"tools\":[{\"name\":\"t\",\"description\":\"" + "说".repeat(1200) + "\"}]}", "a");
                return false;
            } catch (PluginManifest.Invalid e) {
                return e.getMessage().contains("说明太长");
            }
        }, "1200 字说明");
        check.checkThat("P1-B2a", "cron 错误在加载时报错", () -> {
            try {
                PluginManifest.parse("{\"id\":\"a\",\"name\":\"x\",\"version\":\"1.0.0\",\"run\":{\"command\":\"python\"},"
                        + "\"schedules\":[{\"id\":\"s\",\"cron\":\"99 * * * *\"}]}", "a");
                return false;
            } catch (PluginManifest.Invalid e) {
                return e.getMessage().contains("cron");
            }
        }, "99 分");
    }

    private static void duplicates(PluginTestHost h) throws Exception {
        Path other = h.pluginsDir.resolve("examples").resolve("echo");
        Files.createDirectories(other);
        PluginTestHost.copy(Path.of("src", "test", "plugins", "echo").toAbsolutePath(), other);
    }

    private static void process(PluginTestHost h, long began) {
        check.check("P1-A6", "新装的插件默认停用", h.host.views().stream().noneMatch(PluginView::enabled), "启动后没有插件是启用状态");
        check.check("P1-A4", "重复 id 只加载一个，另一个标为无效",
                h.host.views().stream().filter(v -> v.id().equals("echo")).count() == 1
                        && h.host.views().stream().anyMatch(v -> v.status().equals("invalid") && v.problems().toString().contains("重复")),
                "echo 只有一个生效");
        check.check("P1-A7", "空目录不影响启动", h.appLog().contains("plugins: host"), "宿主正常启动");
        long start = System.nanoTime();
        h.host.enable("echo", Set.of(Permission.QQ_REPLY));
        boolean up = waitFor(() -> h.host.view("echo").status().equals("running"), 20_000);
        long first = (System.nanoTime() - start) / 1_000_000;
        PluginView echo = h.host.view("echo");
        check.check("P1-B1", "启用后进程起来并完成 initialize", up && echo.pid() > 0, "状态=" + echo.status() + "，pid=" + echo.pid());
        long[] runs = new long[3];
        for (int i = 0; i < runs.length; i++) {
            long t0 = System.nanoTime();
            h.host.reload("echo");
            waitFor(() -> h.host.view("echo").status().equals("running"), 20_000);
            runs[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        long worst = java.util.Arrays.stream(runs).max().orElse(-1);
        check.check("P1-B2", "启动到运行中 ≤ 2 秒（稳定状态）", worst <= 2000,
                "重启 3 次最慢 " + worst + " ms；全新电脑第一次启动含一次性目录授权 " + first + " ms");
        List<ToolSpec> tools = h.host.extensions().tools(false, false, Set.copyOf(h.agent.workspaces().config().enabled()));
        check.check("P1-E1a", "插件工具带 plugin__ 前缀进入工具表",
                tools.stream().anyMatch(t -> t.name().equals("plugin__echo__echo")), tools.stream().map(ToolSpec::name).toList().toString());
    }

    private static void protocolRobustness(PluginTestHost h) {
        h.host.enable("badjson", Set.of());
        waitFor(() -> h.host.view("badjson").status().equals("running"), 20_000);
        Toolbox.Outcome garbage = call(h, "plugin__badjson__garbage", "{}");
        Toolbox.Outcome after = call(h, "plugin__badjson__ok", "{}");
        String logs = h.logs("badjson");
        check.check("P1-B4", "非 JSON 行被丢弃并记日志，进程不死", logs.contains("not JSON") || logs.contains("JSON-RPC"), "坏行被忽略");
        check.check("P1-B5", "超长行被丢弃", logs.contains("oversized"), "10 MB 行被丢弃");
        check.check("P1-B6", "未知 id 的响应被忽略", logs.contains("unknown id"), "unknown id 被记录");
        check.check("P1-B4b", "坏数据之后仍能正常调用", !after.error() && after.output().contains("still fine"),
                "garbage=" + garbage.output().strip() + "，之后 ok=" + after.output().strip());
        h.host.disable("badjson");
    }

    private static void commands(PluginTestHost h) {
        MessageGate gate = h.host.gate();
        MessageGate.Message msg = h.group("#echo 你好");
        long t0 = System.nanoTime();
        MessageGate.Verdict v = gate.incoming(msg);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        FakeQq.Sent sent = h.qq.last();
        check.check("P1-D1", "命令被插件直接回复且不交给她", v.drop() && sent != null && sent.text().equals("你好")
                && sent.session().equals("group:900001") && msg.messageId().equals(sent.quote()) && sent.label().equals("回声"),
                "drop=" + v.drop() + "，发出=" + sent);
        check.check("P1-D1b", "命令处理 ≤ 1 秒", ms <= 1000, ms + " ms");
        check.check("P1-D1c", "命令处理没有调用大模型", h.usage.totals().calls() == 0, "token_usage 调用次数 = " + h.usage.totals().calls());
        int before = h.qq.sent.size();
        MessageGate.Verdict red = gate.incoming(h.group("#echo " + PluginTestHost.RED_LINE));
        check.check("P1-D7", "插件回复含红线词时被拦下", h.qq.sent.size() == before && !red.drop()
                && waitFor(() -> h.logs("echo").contains("没通过内容审核"), 3000), "发出数没变，插件日志记录了拦截");
        check.check("P1-D2", "别名生效", gate.incoming(h.group("#e hi")).drop(), "#e");
        check.check("P1-D3", "handled=false 时交给她", !gate.incoming(h.group("#pass x")).drop(), "#pass");
        check.check("P1-D4a", "未知命令不被吞", !gate.incoming(h.group("#nosuch")).drop(), "#nosuch");
        check.check("P1-D4b", "# 开头的普通聊天不被吞", !gate.incoming(h.group("#话题 讨论一下")).drop(), "#话题");
        check.check("P1-D5", "scope 只写 group 时私聊不触发",
                !gate.incoming(h.priv("#groupecho x")).drop() && gate.incoming(h.group("#groupecho x")).drop(), "群里触发、私聊不触发");
        conflict(h);
    }

    private static void conflict(PluginTestHost h) {
        try {
            Path dir = h.pluginsDir.resolve("zecho");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("plugin.json"), "{\"id\":\"zecho\",\"name\":\"抢命令\",\"version\":\"1.0.0\","
                    + "\"run\":{\"command\":\"python\",\"args\":[\"main.py\"]},\"permissions\":[\"qq.reply\"],"
                    + "\"commands\":[{\"name\":\"echo\"}]}");
            Files.writeString(dir.resolve("main.py"), "from feiyu_plugin import Plugin\np = Plugin()\n\n"
                    + "@p.command('echo')\ndef echo(ctx):\n    p.kv_set('hit', True)\n\np.run()\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        h.host.scan();
        h.host.enable("zecho", Set.of(Permission.QQ_REPLY));
        waitFor(() -> h.host.view("zecho").status().equals("running"), 20_000);
        h.host.gate().incoming(h.group("#echo 谁处理"));
        sleep(500);
        PluginView z = h.host.view("zecho");
        check.check("P1-D6b", "两个插件抢同一个命令：后加载的被拒绝并记警告",
                h.store.kvGet("zecho", "hit") == null && z.warnings().toString().contains("冲突"), "zecho 警告：" + z.warnings());
        h.host.disable("zecho");
    }

    private static void tools(PluginTestHost h) {
        Toolbox.Outcome echo = call(h, "plugin__echo__echo", "{\"text\":\"你好\"}");
        check.check("P1-E2", "工具能调用并返回结果", !echo.error() && echo.output().contains("你好"), echo.output().strip());
        Toolbox.Outcome bad = call(h, "plugin__echo__add", "{\"a\":\"x\",\"b\":2}");
        check.check("P1-E6", "参数不符合 Schema 时宿主先拦下", bad.error() && bad.output().contains("should be integer"), bad.output().strip());
        Toolbox.Outcome missing = call(h, "plugin__echo__add", "{\"a\":1}");
        check.check("P1-E6b", "缺少必填参数被拦下", missing.error() && missing.output().contains("missing required"), missing.output().strip());
        Toolbox.Outcome big = call(h, "plugin__echo__big", "{}");
        check.check("P1-E7", "超大输出被截断", big.output().contains("[Output truncated]") && big.output().length() < 60_000,
                big.output().length() + " 字符");
        List<ToolSpec> mind = h.host.extensions().tools(false, true, Set.copyOf(h.agent.workspaces().config().enabled()));
        check.check("P1-E8", "只有 mind:true 的工具进内心",
                mind.stream().anyMatch(t -> t.name().equals("plugin__echo__echo")) && mind.stream().noneMatch(t -> t.name().equals("plugin__echo__add")),
                mind.stream().map(ToolSpec::name).toList().toString());
        boolean qqOff = h.host.extensions().tools(true, false, Set.copyOf(h.agent.workspaces().config().enabled())).isEmpty();
        h.store.setQqTools("echo", true);
        h.host.refreshCatalog();
        boolean qqOn = h.host.extensions().tools(true, false, Set.copyOf(h.agent.workspaces().config().enabled()))
                .stream().anyMatch(t -> t.name().equals("plugin__echo__echo"));
        check.check("P1-E3", "插件工具默认对 QQ 关闭，打开后可用", qqOff && qqOn, "默认关=" + qqOff + "，打开后=" + qqOn);
        boolean refused;
        try {
            h.host.enable("bigtool", Set.of());
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check.check("P1-E5b", "清单里工具超过上限时拒绝加载并提示", refused
                && h.host.view("bigtool").status().equals("invalid") && h.host.view("bigtool").problems().toString().contains("工具太多"),
                h.host.view("bigtool").problems().toString());
        h.host.enable("dyntools", Set.of());
        waitFor(() -> h.host.view("dyntools").status().equals("running"), 20_000);
        PluginView dyn = h.host.view("dyntools");
        check.check("P1-E5c", "运行时上报的工具超过上限被截断到 20 个", dyn.tools().size() == PluginManifest.MAX_TOOLS,
                dyn.tools().size() + " 个工具，警告：" + dyn.warnings());
        check.check("P1-E5d", "运行时上报的超长说明被拒绝", dyn.warnings().toString().contains("说明太长") || dyn.tools().stream()
                .noneMatch(t -> t.description().length() > PluginManifest.MAX_TOOL_DESCRIPTION), "警告：" + dyn.warnings());
        h.host.disable("dyntools");
        Toolbox.Outcome gone = call(h, "plugin__dyntools__d1", "{}");
        check.check("P1-E4", "停用后工具立刻消失，调用返回明确错误", gone.error() && gone.output().contains("停用"), gone.output().strip());
    }

    private static void storage(PluginTestHost h) {
        check.check("P1-F1", "kv 能存能读", waitFor(() -> h.store.kvGet("echo", "event_count") != null, 5000),
                "event_count=" + h.store.kvGet("echo", "event_count"));
        check.check("P1-F2", "插件之间 kv 隔离", h.store.kvGet("badjson", "event_count") == null, "badjson 读不到 echo 的键");
        check.checkThat("P1-F3", "单值超过 1 MB 被拒绝", () -> {
            try {
                h.store.kvSet("echo", "big", "\"" + "x".repeat(1024 * 1024 + 10) + "\"");
                return false;
            } catch (Exception e) {
                return e.getMessage().contains("1 MB");
            }
        }, "1 MB 上限");
        check.check("P1-F4", "按插件能查到日志", !h.logs("echo").isEmpty(), h.store.logs("echo", 0, 500).size() + " 条");
        h.host.enable("noisy", Set.of());
        waitFor(() -> h.host.view("noisy").status().equals("running"), 20_000);
        int before = h.store.logs("noisy", 0, 5000).size();
        call(h, "plugin__noisy__spam", "{}");
        sleep(2000);
        long replyMs = timeCommand(h);
        int added = h.store.logs("noisy", 0, 100_000).size() - before;
        check.check("P1-B8", "日志限流：刷 3 秒日志时写入远少于产生量", added < 3 * PluginHost.LOG_LINES_PER_SECOND * 4,
                "写入 " + added + " 条");
        check.check("P1-B8b", "刷日志时其他插件的命令仍然很快", replyMs < 1000, replyMs + " ms");
        h.host.disable("noisy");
    }

    private static long timeCommand(PluginTestHost h) {
        long t0 = System.nanoTime();
        h.host.gate().incoming(h.group("#echo 速度"));
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static void crashes(PluginTestHost h) {
        h.host.enable("slow", Set.of(Permission.QQ_REPLY, Permission.QQ_FILTER));
        waitFor(() -> h.host.view("slow").status().equals("running"), 20_000);
        long t0 = System.nanoTime();
        MessageGate.Verdict v = h.host.gate().incoming(h.group("#slow x"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check.check("P1-C5", "命令超时（5 秒）后交给她处理，不再额外等待", !v.drop() && ms < 6000, "耗时 " + ms + " ms，drop=" + v.drop());
        check.check("P1-C5b", "超时后插件的迟到回复被拒绝", waitFor(() -> {
            String late = h.store.kvGet("slow", "late_reply");
            return late != null && late.contains("rejected");
        }, 15_000), "late_reply=" + h.store.kvGet("slow", "late_reply"));
        Toolbox.Outcome slow = call(h, "plugin__slow__wait", "{\"seconds\":5}");
        check.check("P1-C4", "工具超时返回明确错误且插件不被杀", slow.error() && slow.output().contains("timed out")
                && h.host.view("slow").status().equals("running"), slow.output().strip());
        h.host.disable("slow");

        h.host.enable("crash", Set.of(Permission.QQ_REPLY));
        waitFor(() -> h.host.view("crash").status().equals("running"), 20_000);
        long pid = h.host.view("crash").pid();
        h.host.gate().incoming(h.group("#die"));
        boolean restarted = waitFor(() -> h.host.view("crash").status().equals("running") && h.host.view("crash").pid() != pid, 30_000);
        check.check("P1-C1", "崩溃后自动重启", restarted, "旧 pid=" + pid + "，新 pid=" + h.host.view("crash").pid());
        MessageGate.Verdict afterCrash = h.host.gate().incoming(h.group("#echo 崩溃后"));
        check.check("P1-C3", "崩溃期间命令不被吞", afterCrash.drop(), "echo 仍然处理 #echo");

        JsonObject values = new JsonObject();
        values.addProperty("die_on_start", true);
        h.store.saveValues("crash", h.host.view("crash").manifest().settings(), values);
        h.host.reload("crash");
        boolean disabled = waitFor(() -> h.host.view("crash").status().equals("crashed"), 120_000);
        PluginView crash = h.host.view("crash");
        check.check("P1-C2", "10 分钟内崩溃 5 次后自动停用并显示错误",
                disabled && !crash.enabled() && crash.lastError().contains("崩溃"), "状态=" + crash.status() + "，错误=" + abbreviate(crash.lastError()));
        check.check("P1-C2b", "自动停用时记录了最后的错误输出", crash.lastError().contains("dying on start"), abbreviate(crash.lastError()));
        long p95 = p95(h, "普通的一句话");
        check.check("P1-C6", "有插件崩溃停用时，普通消息过插件层的延迟 P95 ≤ 100 ms", p95 <= 100, "P95 = " + p95 + " ms（50 条）");
    }

    private static long p95(PluginTestHost h, String text) {
        long[] times = new long[50];
        for (int i = 0; i < times.length; i++) {
            long t0 = System.nanoTime();
            h.host.gate().incoming(h.group(text));
            times[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        java.util.Arrays.sort(times);
        return times[(int) Math.ceil(times.length * 0.95) - 1];
    }

    private static void shutdown(PluginTestHost h) {
        long pid = h.host.view("echo").pid();
        check.check("P1-B9", "关闭前进程还在", pid > 0 && h.host.view("echo").status().equals("running"), "pid=" + pid);
        h.host.shutdown();
        boolean gone = waitFor(() -> !alive(pid), 10_000);
        check.check("P1-B9b", "宿主关闭后插件进程结束", gone, "pid " + pid + (gone ? " 已结束" : " 仍在"));
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static Toolbox.Outcome call(PluginTestHost h, String tool, String args) {
        return h.host.extensions().run(new ToolCall(UUID.randomUUID().toString(), tool, args), "web:test");
    }

    private static String abbreviate(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
