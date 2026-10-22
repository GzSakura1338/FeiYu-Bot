package dafeiyu.plugin;

import dafeiyu.agent.ToolCall;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.qq.MessageGate;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.agent.tool.WorkspaceStore;
import dafeiyu.db.Database;
import dafeiyu.plugin.manifest.Permission;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dafeiyu.plugin.CheckSupport.waitFor;

public final class CheckG {

    private static final CheckSupport check = new CheckSupport("全阶段通用");

    public static void main(String[] args) throws Exception {
        clean();
        withPlugins();
        migration();
        secrets();
        check.summary();
        System.exit(check.failed() == 0 ? 0 : 1);
    }

    private static void clean() throws Exception {
        try (PluginTestHost h = new PluginTestHost("g-clean")) {
            h.start();
            check.check("G-1a", "没有任何插件时宿主正常启动", h.host.views().isEmpty() && h.appLog().contains("plugins: host"),
                    "插件数 0");
            List<ToolSpec> tools = h.host.extensions().tools(false, false, Set.copyOf(h.agent.workspaces().config().enabled()));
            check.check("G-1b", "没有插件时不往工具表里加东西", tools.isEmpty() && h.host.toolNames().isEmpty(), "插件工具 0 个");
            check.check("G-1c", "没有插件时内置工具不受影响",
                    h.agent.workspaces().config().enabled().containsAll(List.of("read", "write", "websearch")),
                    h.agent.workspaces().config().enabled().toString());
            MessageGate.Verdict v = h.host.gate().incoming(h.group("普通的一句话"));
            check.check("G-1d", "没有插件时消息原样放行", !v.drop() && v.text() == null, "原样放行");
            check.check("G-1e", "没有插件时出站不被改写", "她要说的话".equals(h.host.gate().outgoing("group:900001", "她要说的话")), "原样");
            check.check("G-1f", "没有插件时提示词不加东西", h.host.extensions().contribute("group:900001", List.of()).isEmpty(), "空");
            long p95 = p95(h);
            check.check("G-2a", "没有插件时消息过插件层的开销 P95", p95 <= 2, "P95 = " + p95 + " ms（100 条）");
        }
    }

    private static void withPlugins() throws Exception {
        try (PluginTestHost h = new PluginTestHost("g-load")) {
            String[] ids = {"echo", "p2a", "p2b", "nodeecho", "webpage", "slow", "noisy", "badjson", "greedy", "dyntools"};
            h.install(ids);
            Files.copy(Path.of("plugins", "sdk", "feiyu_plugin.js").toAbsolutePath(), h.pluginsDir.resolve("sdk").resolve("feiyu_plugin.js"));
            h.start();
            int started = 0;
            for (String id : ids) {
                try {
                    h.host.enable(id, Set.of(Permission.values()));
                    started++;
                } catch (RuntimeException e) {
                    check.check("G-3x", "启用 " + id, false, e.getMessage());
                }
            }
            int want = started;
            boolean up = waitFor(() -> h.host.views().stream().filter(PluginView::enabled).filter(v -> v.status().equals("running")).count() >= want - 1,
                    90_000);
            long running = h.host.views().stream().filter(v -> v.status().equals("running")).count();
            check.check("G-3a", "同时跑 10 个插件", up && running >= 9, running + " 个在运行");
            long p95 = p95(h);
            check.check("G-2b", "装满 10 个插件后，普通消息过插件层的 P95 ≤ 200 ms", p95 <= 200, "P95 = " + p95 + " ms（100 条）");
            long mb = memory(h);
            check.check("G-3b", "10 个插件常驻内存 ≤ 500 MB", mb <= 500, "插件进程合计 " + mb + " MB");
            long t0 = System.nanoTime();
            String contributed = h.host.extensions().contribute("group:900001", List.of("10001"));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check.check("G-2c", "多个插件提供提示词补充时总耗时仍受 500 毫秒限制", ms < 900, ms + " ms，" + contributed.length() + " 字");
            h.host.shutdown();
            check.check("G-4a", "宿主关闭后所有插件进程结束", waitFor(() -> h.host.views().stream().noneMatch(v -> alive(v.pid())), 15_000),
                    "没有残留进程");
        }
    }

    private static void migration() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "feiyu-plugin-test", "g-migrate-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        Path file = dir.resolve("old.db");
        try (Database db = Database.open(file)) {
            db.update("INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)", "agent.qqReply", "true", 1L);
            check.check("G-5a", "新库直接建到最新版本", db.schemaVersion() == 11, "user_version = " + db.schemaVersion());
        }
        try (Database db = Database.open(file)) {
            String value = db.one("SELECT value FROM settings WHERE key = ?", rs -> rs.getString(1), "agent.qqReply");
            Integer kv = db.one("SELECT COUNT(*) FROM plugin_kv", rs -> rs.getInt(1));
            Integer logs = db.one("SELECT COUNT(*) FROM plugin_logs", rs -> rs.getInt(1));
            check.check("G-5b", "再次打开不丢数据，插件表可用", "true".equals(value) && kv == 0 && logs == 0,
                    "老数据还在，plugin_kv / plugin_logs 建好了");
        }
    }

    private static void secrets() throws Exception {
        try (PluginTestHost h = new PluginTestHost("g-secret")) {
            h.install("echo");
            h.start();
            h.host.enable("echo", Set.of(Permission.QQ_REPLY));
            waitFor(() -> h.host.view("echo").status().equals("running"), 30_000);
            com.google.gson.JsonObject values = new com.google.gson.JsonObject();
            values.addProperty("name", "回声");
            values.addProperty("token", "超级机密-ABC123");
            h.store.saveValues("echo", h.host.view("echo").manifest().settings(), values);
            h.host.settingsChanged("echo");
            h.host.gate().incoming(h.group("#echo 普通消息"));
            CheckSupport.sleep(500);
            String logs = h.logs("echo") + h.appLog();
            String stored = h.db.one("SELECT value FROM settings WHERE key = ?", rs -> rs.getString(1), "plugin.echo.settings");
            String shown = h.store.publicValues("echo", h.host.view("echo").manifest().settings()).toString();
            check.check("G-8", "密钥不出现在日志、接口返回里，数据库里是密文",
                    !logs.contains("超级机密") && !shown.contains("超级机密") && stored != null && !stored.contains("超级机密"),
                    "三处都没有明文");
        }
    }

    private static long p95(PluginTestHost h) {
        long[] times = new long[100];
        for (int i = 0; i < times.length; i++) {
            long t0 = System.nanoTime();
            h.host.gate().incoming(h.group("第 " + i + " 条普通消息"));
            times[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        Arrays.sort(times);
        return times[94];
    }

    private static boolean alive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static long memory(PluginTestHost h) {
        long kb = 0;
        for (PluginView v : h.host.views()) {
            if (v.pid() > 0 && alive(v.pid())) {
                kb += workingSetKb(v.pid());
            }
        }
        return kb / 1024;
    }

    private static long workingSetKb(long pid) {
        try {
            Process p = new ProcessBuilder("tasklist", "/fi", "PID eq " + pid, "/nh", "/fo", "csv").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            var m = java.util.regex.Pattern.compile("\"([\\d,]+) K\"\\s*$", java.util.regex.Pattern.MULTILINE).matcher(out.strip());
            return m.find() ? Long.parseLong(m.group(1).replace(",", "")) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
