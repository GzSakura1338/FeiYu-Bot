package dafeiyu.plugin;

import dafeiyu.plugin.install.PluginInstaller;
import dafeiyu.plugin.manifest.Permission;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static dafeiyu.plugin.CheckSupport.waitFor;

public final class CheckP5 {

    private static final CheckSupport check = new CheckSupport("P5 安装与分发");
    private static final String MANIFEST = """
            {"id": "demo", "name": "演示插件", "version": "%s", "author": "test", "host": ">=0.2.0",
             "run": {"command": "python", "args": ["main.py"]}, "permissions": [%s],
             "commands": [{"name": "demo", "description": "演示"}]}""";
    private static final String MAIN = """
            from feiyu_plugin import Plugin
            p = Plugin()

            @p.command('demo')
            def demo(ctx):
                p.kv_set('ran', '%s')
                ctx.reply('演示 %s')

            p.run()
            """;

    public static void main(String[] args) throws Exception {
        try (PluginTestHost h = new PluginTestHost("p5")) {
            h.start();
            install(h);
            upgrade(h);
            malicious(h);
            uninstall(h);
            git(h);
            nodeSdk(h);
            webPage(h);
        }
        check.summary();
        System.exit(check.failed() == 0 ? 0 : 1);
    }

    private static byte[] zip(Consumer<ZipOutputStream> body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            body.accept(zip);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bytes.toByteArray();
    }

    private static void write(ZipOutputStream zip, String name, String content) {
        try {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] plugin(String version, String permissions, String prefix) {
        return zip(zip -> {
            write(zip, prefix + "plugin.json", MANIFEST.formatted(version, permissions));
            write(zip, prefix + "main.py", MAIN.formatted(version, version));
            write(zip, prefix + "README.md", "# 演示插件\n\n版本 " + version);
        });
    }

    private static void install(PluginTestHost h) throws IOException {
        PluginInstaller.Staged staged = h.installer.stageZip(plugin("1.0.0", "\"qq.reply\"", ""), "上传的压缩包");
        check.check("P5-1a", "上传后先显示清单和权限，不直接启用",
                staged.manifest().id().equals("demo") && staged.newPermissions().contains(Permission.QQ_REPLY)
                        && h.host.views().stream().noneMatch(v -> v.id().equals("demo")),
                "待确认：" + staged.manifest().name() + " " + staged.manifest().version() + "，新权限 " + staged.newPermissions());
        h.installer.confirm(staged.token(), Set.of(Permission.QQ_REPLY), true);
        boolean running = waitFor(() -> h.host.view("demo").status().equals("running"), 30_000);
        check.check("P5-1b", "确认后落盘并启用", running && Files.isRegularFile(h.pluginsDir.resolve("demo").resolve("plugin.json")),
                "状态=" + h.host.view("demo").status());
        h.host.gate().incoming(h.group("#demo"));
        check.check("P5-1c", "装完就能用", waitFor(() -> h.qq.count("演示 1.0.0") == 1, 5000), "命令回复正常");

        PluginInstaller.Staged nested = h.installer.stageZip(plugin("1.0.0", "", "demo-main/"), "带一层目录的压缩包");
        check.check("P5-1d", "压缩包里套一层目录也能识别", nested.manifest().id().equals("demo"), "找到了内层的 plugin.json");
        h.installer.cancel(nested.token());
    }

    private static void upgrade(PluginTestHost h) throws Exception {
        h.store.kvSet("demo", "keep", "\"旧数据\"");
        PluginInstaller.Staged up = h.installer.stageZip(plugin("1.1.0", "\"qq.reply\",\"network\"", ""), "升级包");
        check.check("P5-4a", "升级时显示已装版本和新增权限",
                up.installedVersion().equals("1.0.0") && !up.downgrade() && up.newPermissions().equals(List.of(Permission.NETWORK)),
                "1.0.0 → 1.1.0，新增权限 " + up.newPermissions());
        h.installer.confirm(up.token(), Set.of(Permission.QQ_REPLY, Permission.NETWORK), true);
        boolean running = waitFor(() -> h.host.view("demo").status().equals("running")
                && h.host.view("demo").manifest().version().equals("1.1.0"), 30_000);
        check.check("P5-4b", "升级后保留设置和 kv 数据", running && "\"旧数据\"".equals(h.store.kvGet("demo", "keep")),
                "版本=" + h.host.view("demo").manifest().version() + "，kv 还在");
        h.host.gate().incoming(h.group("#demo"));
        check.check("P5-4c", "升级后跑的是新版本", waitFor(() -> h.qq.count("演示 1.1.0") == 1, 5000), "回复来自 1.1.0");

        PluginInstaller.Staged down = h.installer.stageZip(plugin("1.0.0", "\"qq.reply\"", ""), "降级包");
        check.check("P5-4d", "版本变小时提示降级", down.downgrade(), "1.1.0 → 1.0.0 标记为降级");
        h.installer.cancel(down.token());
    }

    private static void malicious(PluginTestHost h) {
        check.checkThat("P5-2a", "zip 里有 ../ 路径时拒绝", () -> refused(h, zip(zip -> {
            write(zip, "plugin.json", MANIFEST.formatted("1.0.0", ""));
            write(zip, "../../evil.txt", "坏东西");
        })), "../../evil.txt");
        check.checkThat("P5-2b", "zip 里有绝对路径时拒绝", () -> refused(h, zip(zip -> {
            write(zip, "plugin.json", MANIFEST.formatted("1.0.0", ""));
            write(zip, "C:/Windows/Temp/evil.txt", "坏东西");
        })), "C:/Windows/...");
        check.checkThat("P5-2c", "zip 炸弹（声明体积过大）被拒绝", () -> refused(h, zip(zip -> {
            write(zip, "plugin.json", MANIFEST.formatted("1.0.0", ""));
            write(zip, "bomb.txt", "A".repeat(2_000_000));
            for (int i = 0; i < 60; i++) {
                write(zip, "bomb" + i + ".txt", "A".repeat(2_000_000));
            }
        })), "解压后超过 100 MB");
        check.checkThat("P5-2d", "文件数过多被拒绝", () -> refused(h, zip(zip -> {
            write(zip, "plugin.json", MANIFEST.formatted("1.0.0", ""));
            for (int i = 0; i < 2100; i++) {
                write(zip, "f" + i + ".txt", "x");
            }
        })), "超过 2000 个文件");
        check.checkThat("P5-2e", "不是 zip 的内容被拒绝", () -> refused(h, "这不是压缩包".getBytes(StandardCharsets.UTF_8)), "纯文本");
        check.checkThat("P5-2f", "没有 plugin.json 的压缩包被拒绝", () -> refused(h, zip(zip -> write(zip, "readme.txt", "x"))), "缺少清单");
        Path before = h.root.getParent().resolve("evil.txt");
        check.check("P5-2g", "恶意压缩包没有在目录外留下文件", !Files.exists(before), "临时目录外没有多出文件");
    }

    private static boolean refused(PluginTestHost h, byte[] data) {
        try {
            PluginInstaller.Staged staged = h.installer.stageZip(data, "测试");
            h.installer.cancel(staged.token());
            return false;
        } catch (IllegalArgumentException | IOException e) {
            return true;
        }
    }

    private static void uninstall(PluginTestHost h) {
        h.installer.uninstall("demo", true);
        check.check("P5-5a", "卸载后文件删除、插件消失",
                !Files.exists(h.pluginsDir.resolve("demo")) && h.host.views().stream().noneMatch(v -> v.id().equals("demo")),
                "目录和列表都清干净了");
        check.check("P5-5b", "卸载时选择删数据会清掉 kv 和设置", h.store.kvGet("demo", "keep") == null, "kv 已清空");
        check.check("P5-5c", "卸载后命令不再被吞", !h.host.gate().incoming(h.group("#demo")).drop(), "#demo 交给她");
        boolean refused;
        try {
            h.installer.uninstall("nosuch", false);
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check.check("P5-5d", "卸载不存在的插件报错", refused, "提示没有这个插件");
    }

    private static void git(PluginTestHost h) throws Exception {
        Path work = h.root.resolve("repo");
        Files.createDirectories(work);
        Files.writeString(work.resolve("plugin.json"), MANIFEST.formatted("2.0.0", "\"qq.reply\""));
        Files.writeString(work.resolve("main.py"), MAIN.formatted("2.0.0", "2.0.0"));
        run(work, "git", "init", "-q");
        run(work, "git", "add", ".");
        run(work, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init");
        Path served = h.root.resolve("served");
        Files.createDirectories(served);
        run(served, "git", "clone", "-q", "--bare", work.toString(), "demo.git");
        run(served.resolve("demo.git"), "git", "update-server-info");
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Process http = new ProcessBuilder("python", "-m", "http.server", String.valueOf(port), "--bind", "127.0.0.1")
                .directory(served.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            CheckSupport.sleep(1500);
            gitInstall(h, "http://127.0.0.1:" + port + "/demo.git");
        } finally {
            http.destroyForcibly();
        }
    }

    private static void gitInstall(PluginTestHost h, String url) throws Exception {
        PluginInstaller.Staged staged = h.installer.stageGit(url);
        check.check("P5-3a", "从 Git 仓库安装前先显示清单", staged.manifest().version().equals("2.0.0"), "版本 2.0.0");
        h.installer.confirm(staged.token(), Set.of(Permission.QQ_REPLY), true);
        boolean running = waitFor(() -> h.host.view("demo").status().equals("running"), 30_000);
        check.check("P5-3b", "Git 安装的插件能用", running && !Files.exists(h.pluginsDir.resolve("demo").resolve(".git")),
                "状态=" + h.host.view("demo").status() + "，.git 已剔除");
        boolean refused;
        try {
            h.installer.stageGit(url.replace("demo.git", "nosuch.git"));
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check.check("P5-3c", "地址不对时提示清楚", refused, "克隆失败有提示");
    }

    private static void nodeSdk(PluginTestHost h) throws Exception {
        Files.copy(Path.of("plugins", "sdk", "feiyu_plugin.js").toAbsolutePath(), h.pluginsDir.resolve("sdk").resolve("feiyu_plugin.js"));
        h.install("nodeecho");
        h.host.scan();
        h.host.enable("nodeecho", Set.of(Permission.QQ_REPLY));
        boolean running = waitFor(() -> h.host.view("nodeecho").status().equals("running"), 40_000);
        check.check("P5-7a", "Node SDK 写的插件能启动", running, "状态=" + h.host.view("nodeecho").status()
                + "，错误=" + h.host.view("nodeecho").lastError().replace('\n', ' '));
        int before = h.qq.sent.size();
        boolean handled = h.host.gate().incoming(h.group("#necho 你好 Node")).drop();
        check.check("P5-7b", "Node 插件的命令和别名能用", handled && waitFor(() -> h.qq.count("你好 Node") == 1, 5000)
                && h.host.gate().incoming(h.group("#ne 别名")).drop(), "发出 " + (h.qq.sent.size() - before) + " 条");
        check.check("P5-7c", "Node 插件 handled=false 时交给她", !h.host.gate().incoming(h.group("#npass x")).drop(), "#npass");
        var echo = h.host.extensions().run(new dafeiyu.agent.ToolCall("1", "plugin__nodeecho__echo", "{\"text\":\"工具你好\"}"), "web:test");
        check.check("P5-7d", "Node 插件的工具能调用", !echo.error() && echo.output().contains("工具你好"), echo.output().strip());
        var big = h.host.extensions().run(new dafeiyu.agent.ToolCall("2", "plugin__nodeecho__big", "{}"), "web:test");
        check.check("P5-7e", "Node 插件的超大输出也被截断", big.output().contains("[Output truncated]"), big.output().length() + " 字符");
        check.check("P5-7f", "Node 插件运行时上报的工具也生效",
                h.host.view("nodeecho").tools().size() == 3, h.host.view("nodeecho").tools().stream().map(t -> t.name()).toList().toString());
        check.check("P5-7g", "Node 插件的事件和 kv 正常", waitFor(() -> h.store.kvGet("nodeecho", "last") != null, 5000),
                "last=" + h.store.kvGet("nodeecho", "last"));
    }

    private static void webPage(PluginTestHost h) throws Exception {
        h.install("webpage");
        h.host.scan();
        h.host.enable("webpage", Set.of());
        boolean running = waitFor(() -> h.host.view("webpage").status().equals("running"), 30_000);
        check.check("P5-8a", "带页面的插件能启动，并标记有自定义页面",
                running && Files.isRegularFile(h.pluginsDir.resolve("webpage").resolve("web").resolve("index.html")), "web/index.html 在");
        var reply = h.host.webRequest("webpage", "POST", "/hello", "x=1", "{\"a\":2}");
        check.check("P5-8b", "插件页面的接口请求能转给插件",
                reply.get("status").getAsInt() == 200 && reply.get("body").getAsString().contains("\"path\": \"/hello\"")
                        && reply.get("body").getAsString().contains("x=1"), reply.get("body").getAsString());
        boolean refused;
        try {
            h.host.disable("webpage");
            h.host.webRequest("webpage", "GET", "/hello", "", "");
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check.check("P5-8c", "插件停用后页面接口拒绝请求", refused, "提示插件没在运行");
    }

    private static void run(Path dir, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("命令失败：" + String.join(" ", command));
        }
    }
}
