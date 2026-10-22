package dafeiyu.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.plugin.mcp.McpManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dafeiyu.plugin.CheckSupport.sleep;
import static dafeiyu.plugin.CheckSupport.waitFor;

public final class CheckP3 {

    private static final CheckSupport check = new CheckSupport("P3 MCP");

    public static void main(String[] args) throws Exception {
        Path server = Path.of("src", "test", "plugins", "mcp-fake", "server.py").toAbsolutePath();
        try (PluginTestHost h = new PluginTestHost("p3")) {
            h.start();
            McpManager mcp = new McpManager(h.store, h.dataDir.resolve("mcp"), h.log, h.host::refreshCatalog);
            h.host.setMcp(mcp);
            mcp.start();
            stdio(h, mcp, server);
            http(h, mcp, server);
            failures(h, mcp, server);
        }
        check.summary();
        System.exit(check.failed() == 0 ? 0 : 1);
    }

    private static JsonObject config(String name, String type) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("type", type);
        o.addProperty("enabled", true);
        o.addProperty("sandbox", true);
        o.addProperty("network", false);
        o.addProperty("timeout", 5);
        o.addProperty("qq", false);
        o.addProperty("mind", false);
        return o;
    }

    private static JsonObject stdioConfig(String name, Path server, String protocol) {
        JsonObject o = config(name, "stdio");
        o.addProperty("timeout", 2);
        o.addProperty("command", "python");
        JsonArray args = new JsonArray();
        args.add("-u");
        args.add(server.toString());
        o.add("args", args);
        JsonArray env = new JsonArray();
        if (protocol != null) {
            JsonObject e = new JsonObject();
            e.addProperty("key", "FAKE_PROTOCOL");
            e.addProperty("value", protocol);
            e.addProperty("secret", false);
            env.add(e);
        }
        o.add("env", env);
        return o;
    }

    private static void stdio(PluginTestHost h, McpManager mcp, Path server) {
        mcp.save(stdioConfig("fake", server, null), null);
        boolean connected = waitFor(() -> "connected".equals(mcp.statuses().get("fake")), 60_000);
        McpManager.ServerView view = mcp.views().getFirst();
        check.check("P3-1a", "stdio 服务能连上并拿到工具", connected && view.tools().size() == 6,
                "状态=" + view.status() + "，服务名=" + view.serverName() + "，工具数=" + view.tools().size() + "（含分页）");
        check.check("P3-1b", "工具名是 mcp__服务__工具",
                view.tools().stream().anyMatch(t -> t.name().equals("mcp__fake__echo")), view.tools().stream().map(McpManager.ToolView::name).toList().toString());
        enable(h, mcp, "fake");
        Toolbox.Outcome echo = call(h, "mcp__fake__echo", "{\"text\":\"你好 MCP\"}");
        check.check("P3-1c", "能调用 stdio 服务的工具", !echo.error() && echo.output().contains("你好 MCP"), echo.output().strip());
        Toolbox.Outcome bad = call(h, "mcp__fake__add", "{\"a\":\"x\",\"b\":1}");
        check.check("P3-1d", "参数不符合 Schema 时先拦下", bad.error() && bad.output().contains("should be number"), bad.output().strip());
        Toolbox.Outcome slow = call(h, "mcp__fake__slow", "{}");
        check.check("P3-7a", "工具超时返回明确错误", slow.error() && slow.output().contains("timed out"), slow.output().strip());
        Toolbox.Outcome fail = call(h, "mcp__fake__fail", "{}");
        check.check("P3-7b", "工具返回 isError 时标成错误", fail.error() && fail.output().contains("something went wrong"), fail.output().strip());
        Toolbox.Outcome image = call(h, "mcp__fake__image", "{}");
        check.check("P3-7c", "图片内容给出文字说明而不是乱码",
                !image.error() && image.output().contains("[image image/png") && image.output().contains("a tiny image"), image.output().strip());
        int before = mcp.views().getFirst().tools().size();
        call(h, "mcp__fake__grow", "{}");
        boolean grew = waitFor(() -> mcp.views().getFirst().tools().size() > before, 15_000);
        check.check("P3-5", "服务通知工具列表变化后列表更新", grew,
                before + " → " + mcp.views().getFirst().tools().size() + " 个工具");
    }

    private static void http(PluginTestHost h, McpManager mcp, Path server) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Process p = new ProcessBuilder("python", "-u", server.toString(), "http", String.valueOf(port))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            sleep(1500);
            JsonObject o = config("fakehttp", "http");
            o.addProperty("url", "http://127.0.0.1:" + port + "/mcp");
            JsonArray headers = new JsonArray();
            JsonObject key = new JsonObject();
            key.addProperty("key", "X-Test-Key");
            key.addProperty("value", "secret-123");
            key.addProperty("secret", true);
            headers.add(key);
            o.add("headers", headers);
            mcp.save(o, null);
            boolean connected = waitFor(() -> "connected".equals(mcp.statuses().get("fakehttp")), 30_000);
            McpManager.ServerView view = mcp.views().stream().filter(v -> v.config().name().equals("fakehttp")).findFirst().orElseThrow();
            check.check("P3-2a", "Streamable HTTP 服务能连上（带自定义请求头）", connected && !view.tools().isEmpty(),
                    "状态=" + view.status() + "，工具数=" + view.tools().size() + "，错误=" + view.error());
            enable(h, mcp, "fakehttp");
            Toolbox.Outcome echo = call(h, "mcp__fakehttp__echo", "{\"text\":\"HTTP 你好\"}");
            check.check("P3-2b", "能调用 HTTP 服务的工具（SSE 响应）", !echo.error() && echo.output().contains("HTTP 你好"), echo.output().strip());
            String stored = h.store.mcpServers();
            check.check("P3-6", "请求头里的密钥加密存储、页面不回显",
                    !stored.contains("secret-123") && stored.contains("dpapi:")
                            && mcp.views().stream().anyMatch(v -> v.config().toPublic().toString().contains("__keep__")),
                    "数据库里没有明文");
            JsonObject edit = view.config().toPublic();
            edit.addProperty("timeout", 9);
            mcp.save(edit, "fakehttp");
            waitFor(() -> "connected".equals(mcp.statuses().get("fakehttp")), 30_000);
            check.check("P3-6b", "编辑时不改密钥也不会丢",
                    waitFor(() -> !call(h, "mcp__fakehttp__echo", "{\"text\":\"还在\"}").error(), 20_000), "改完超时设置后仍能调用");
        } finally {
            p.destroyForcibly();
        }
    }

    private static void failures(PluginTestHost h, McpManager mcp, Path server) throws IOException {
        JsonObject gone = stdioConfig("missing", Path.of("no-such-server.py"), null);
        mcp.save(gone, null);
        boolean failed = waitFor(() -> "error".equals(mcp.statuses().get("missing")), 30_000);
        McpManager.ServerView view = mcp.views().stream().filter(v -> v.config().name().equals("missing")).findFirst().orElseThrow();
        check.check("P3-4a", "启动失败时显示状态和原因，不影响其他服务", failed && !view.error().isBlank()
                        && "connected".equals(mcp.statuses().get("fake")), "错误=" + view.error());
        mcp.delete("missing");

        mcp.save(stdioConfig("oldproto", server, "1999-01-01"), null);
        boolean rejected = waitFor(() -> "error".equals(mcp.statuses().get("oldproto")), 60_000);
        McpManager.ServerView old = mcp.views().stream().filter(v -> v.config().name().equals("oldproto")).findFirst().orElseThrow();
        check.check("P3-4b", "协议版本不兼容时报错并说明", rejected && old.error().contains("协议版本"), "错误=" + old.error());
        mcp.delete("oldproto");

        List<ToolSpec> before = mcp.tools(false, false, Set.copyOf(h.agent.workspaces().config().enabled()));
        mcp.setEnabled("fake", false);
        List<ToolSpec> after = mcp.tools(false, false, Set.copyOf(h.agent.workspaces().config().enabled()));
        check.check("P3-4c", "停用后工具从列表里消失", !before.isEmpty() && after.stream().noneMatch(t -> t.name().startsWith("mcp__fake__")),
                before.size() + " → " + after.size());
        Toolbox.Outcome off = call(h, "mcp__fake__echo", "{\"text\":\"x\"}");
        check.check("P3-4d", "停用后调用返回明确错误", off.error(), off.output().strip());
        mcp.setEnabled("fake", true);
        check.check("P3-4e", "重新启用后恢复", waitFor(() -> "connected".equals(mcp.statuses().get("fake")), 30_000), "重新连上");

        mcp.delete("fake");
        check.check("P3-3", "删除服务后工具消失",
                mcp.tools(false, false, Set.copyOf(h.agent.workspaces().config().enabled())).stream().noneMatch(t -> t.name().startsWith("mcp__fake__")),
                "删除干净");
    }

    private static void enable(PluginTestHost h, McpManager mcp, String server) {
        h.host.refreshCatalog();
        List<String> names = h.host.toolNames().stream().filter(n -> n.startsWith("mcp__" + server + "__")).toList();
        var config = h.agent.workspaces().config();
        List<String> enabled = new java.util.ArrayList<>(config.enabled());
        names.forEach(n -> {
            if (!enabled.contains(n)) {
                enabled.add(n);
            }
        });
        h.agent.workspaces().update(new dafeiyu.agent.tool.WorkspaceStore.Config(config.dir(), enabled, config.qqAccess(), config.qqUsers()));
    }

    private static Toolbox.Outcome call(PluginTestHost h, String tool, String args) {
        return h.host.extensions().run(new ToolCall(UUID.randomUUID().toString(), tool, args), "web:test");
    }
}
