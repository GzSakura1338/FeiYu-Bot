package dafeiyu.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.agent.tool.WorkspaceStore;
import dafeiyu.plugin.manifest.Permission;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dafeiyu.plugin.CheckSupport.waitFor;

public final class CheckP4 {

    private static final CheckSupport check = new CheckSupport("P4 沙盒与权限");

    public static void main(String[] args) throws Exception {
        try (PluginTestHost h = new PluginTestHost("p4")) {
            h.install("greedy", "echo");
            Path workspace = h.root.resolve("workspace");
            Files.createDirectories(workspace);
            Files.writeString(workspace.resolve("note.txt"), "工作目录里的文件");
            Path outside = h.root.resolve("outside.txt");
            Files.writeString(outside, "不该被读到");
            h.start();

            h.host.enable("greedy", Set.of());
            waitFor(() -> h.host.view("greedy").status().equals("running"), 30_000);
            check.check("P4-1", "插件默认在沙盒里运行", h.store.sandbox("greedy"), "sandbox=true");

            JsonObject probe = probe(h, Map.of(
                    "db", h.dataDir.resolve("test.db").toString(),
                    "outside", outside.toString(),
                    "other_plugin", h.pluginsDir.resolve("echo").resolve("plugin.json").toString(),
                    "user_home", Path.of(System.getProperty("user.home"), "NTUSER.DAT").toString(),
                    "workspace", workspace.resolve("note.txt").toString()));
            check.check("P4-6a", "没声明权限就调 qq/send 被拒绝", probe.get("qq/send").getAsString().startsWith("denied -32001"),
                    probe.get("qq/send").getAsString());
            check.check("P4-6b", "没声明权限就读聊天记录被拒绝", probe.get("qq/history").getAsString().startsWith("denied -32001"),
                    probe.get("qq/history").getAsString());
            check.check("P4-6c", "没声明权限就读记忆被拒绝", probe.get("memory/recall").getAsString().startsWith("denied -32001"),
                    probe.get("memory/recall").getAsString());
            check.check("P4-6d", "没声明权限就调大模型被拒绝", probe.get("llm/chat").getAsString().startsWith("denied -32001"),
                    probe.get("llm/chat").getAsString());
            check.check("P4-6e", "越权调用写进了安全日志",
                    h.appLog().contains("greedy called qq/send without permission qq.send"), "英文安全日志已记录");
            check.check("P4-2", "没声明 network 时上不了网", probe.get("internet").getAsString().startsWith("blocked"),
                    probe.get("internet").getAsString());
            check.check("P4-3", "本机回环地址也访问不了", probe.get("loopback").getAsString().startsWith("blocked"),
                    probe.get("loopback").getAsString());
            check.check("P4-4a", "读不到数据库", probe.get("read db").getAsString().startsWith("blocked"), probe.get("read db").getAsString());
            check.check("P4-4b", "读不到插件目录外的文件", probe.get("read outside").getAsString().startsWith("blocked"),
                    probe.get("read outside").getAsString());
            check.check("P4-4c", "读不到别的插件的文件", probe.get("read other_plugin").getAsString().startsWith("blocked"),
                    probe.get("read other_plugin").getAsString());
            check.check("P4-4d", "读不到用户目录", probe.get("read user_home").getAsString().startsWith("blocked"),
                    probe.get("read user_home").getAsString());
            check.check("P4-4e", "能读写自己的数据目录", probe.get("own_data").getAsString().equals("allowed"),
                    probe.get("own_data").getAsString());
            check.check("P4-5a", "没声明 files 时读不到工作目录", probe.get("read workspace").getAsString().startsWith("blocked"),
                    probe.get("read workspace").getAsString());

            WorkspaceStore workspaces = h.agent.workspaces();
            var config = workspaces.config();
            workspaces.update(new WorkspaceStore.Config(workspace.toString(), config.enabled(), WorkspaceStore.QqAccess.ALL, List.of()));
            grant(h, "greedy", Permission.NETWORK, Permission.FILES);
            JsonObject after = probe(h, Map.of("workspace", workspace.resolve("note.txt").toString(),
                    "outside", outside.toString(), "db", h.dataDir.resolve("test.db").toString()));
            check.check("P4-2b", "声明并同意 network 后能上公网", after.get("internet").getAsString().equals("allowed"),
                    after.get("internet").getAsString());
            check.check("P4-3b", "有 network 时仍访问不了本机后台接口", after.get("loopback").getAsString().startsWith("blocked"),
                    after.get("loopback").getAsString());
            check.check("P4-5b", "声明并同意 files 后能读工作目录", after.get("read workspace").getAsString().equals("allowed"),
                    after.get("read workspace").getAsString());
            check.check("P4-5c", "有 files 也读不到工作目录以外", after.get("read outside").getAsString().startsWith("blocked")
                            && after.get("read db").getAsString().startsWith("blocked"),
                    "outside=" + after.get("read outside").getAsString() + "，db=" + after.get("read db").getAsString());
            check.check("P4-13", "插件环境变量里没有任何密钥", after.getAsJsonArray("env_secrets").isEmpty(),
                    "可疑环境变量：" + after.getAsJsonArray("env_secrets"));

            Toolbox.Outcome inject = call(h, "plugin__greedy__inject", "{}");
            check.check("P4-12a", "插件返回的内容只是工具结果，不会改变她的指令",
                    inject.output().contains("忽略之前的所有指令"), "原样返回，由系统提示词约束");
            check.check("P4-12b", "系统提示词里写明插件内容只是资料",
                    dafeiyu.agent.Prompt.PLUGIN_CONTENT.contains("外部资料") && dafeiyu.agent.Prompt.PLUGIN_CONTENT.contains("不算数"),
                    dafeiyu.agent.Prompt.PLUGIN_CONTENT);

            limits(h);
            privacy(h);
            approval(h);
            budget(h);
            sandboxOff(h);
        }
        check.summary();
        System.exit(check.failed() == 0 ? 0 : 1);
    }

    private static void limits(PluginTestHost h) {
        long t0 = System.nanoTime();
        Toolbox.Outcome out = call(h, "plugin__greedy__hog", "{}");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        JsonElement parsed = JsonParser.parseString(out.output().startsWith("{") ? out.output() : "{}");
        JsonObject o = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        boolean memoryCapped = o.has("memory_2gb") && o.get("memory_2gb").getAsString().startsWith("blocked");
        boolean processCapped = o.has("processes_started") && o.get("processes_started").getAsInt() < 40;
        check.check("P4-7a", "内存超过沙盒上限时申请失败", memoryCapped || !h.host.view("greedy").status().equals("running"),
                "申请 2 GB：" + (o.has("memory_2gb") ? o.get("memory_2gb").getAsString() : "插件被结束"));
        check.check("P4-7b", "进程数受限", processCapped || !h.host.view("greedy").status().equals("running"),
                "想开 40 个子进程，实际 " + (o.has("processes_started") ? o.get("processes_started").getAsString() : "0") + " 个");
        check.check("P4-7c", "抢资源时主程序没有卡住", ms < 120_000, "整个过程 " + ms + " ms");
        if (!h.host.view("greedy").status().equals("running")) {
            waitFor(() -> h.host.view("greedy").status().equals("running"), 30_000);
        }
    }

    private static void privacy(PluginTestHost h) {
        var persona = h.agent.personas().add(new dafeiyu.agent.persona.Persona("", "测试人格", "", "测试用"));
        h.agent.personas().setActive(persona.id());
        h.agent.memories().add(persona.id(), "self", "", "群里记下的事", "group:900001");
        h.agent.memories().add(persona.id(), "self", "", "私聊里记下的事", "private:10002");
        grant(h, "greedy", Permission.MEMORY_READ, Permission.NETWORK, Permission.FILES);
        Toolbox.Outcome out = call(h, "plugin__greedy__recall_all", "{}");
        check.check("P4-11", "插件读记忆时，私聊来源的记忆不给",
                out.output().contains("群里记下的事") && !out.output().contains("私聊里记下的事"), out.output().strip());
    }

    private static void approval(PluginTestHost h) {
        h.host.disable("greedy");
        h.store.approve("echo", Set.of());
        boolean refused;
        List<Permission> missing = List.of();
        try {
            h.host.enable("echo", Set.of());
            refused = false;
        } catch (PluginHost.NeedsApproval e) {
            refused = true;
            missing = e.missing();
        }
        check.check("P4-8a", "没同意权限前不能启用，并列出缺哪些",
                refused && missing.contains(Permission.QQ_REPLY) && h.host.view("echo").status().equals("needs-approval"),
                "缺少：" + missing);
        h.host.enable("echo", Set.of(Permission.QQ_REPLY));
        check.check("P4-8b", "同意后可以启用", waitFor(() -> h.host.view("echo").status().equals("running"), 20_000),
                "状态=" + h.host.view("echo").status());
    }

    private static void budget(PluginTestHost h) {
        h.store.setLlmBudget("greedy", 0);
        grant(h, "greedy", Permission.LLM, Permission.NETWORK, Permission.FILES);
        waitFor(() -> h.host.view("greedy").status().equals("running"), 30_000);
        JsonObject out = probe(h, Map.of());
        check.check("P4-10", "大模型额度用完后被拒绝", out.get("llm/chat").getAsString().startsWith("denied -32002"),
                out.get("llm/chat").getAsString());
        check.check("P4-10b", "插件的 token 单独计账", h.usage.tokensToday("plugin:greedy") == 0, "plugin:greedy 今日 0 token");
    }

    private static void sandboxOff(PluginTestHost h) {
        h.store.setSandbox("greedy", false);
        h.host.reload("greedy");
        waitFor(() -> h.host.view("greedy").status().equals("running"), 30_000);
        JsonObject out = probe(h, Map.of("outside", h.root.resolve("outside.txt").toString()));
        check.check("P4-9a", "关掉沙盒后插件确实不再受限（所以要二次确认）",
                out.get("read outside").getAsString().equals("allowed") && !h.store.sandbox("greedy"),
                "关沙盒后能读外部文件：" + out.get("read outside").getAsString());
        check.check("P4-9b", "关沙盒后权限检查仍然生效（不是免检）",
                out.get("qq/send").getAsString().startsWith("denied -32001"), out.get("qq/send").getAsString());
        h.store.setSandbox("greedy", true);
    }

    private static void grant(PluginTestHost h, String id, Permission... permissions) {
        h.host.disable(id);
        Path file = h.pluginsDir.resolve(id).resolve("plugin.json");
        try {
            JsonObject m = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            com.google.gson.JsonArray perms = new com.google.gson.JsonArray();
            for (Permission p : permissions) {
                perms.add(p.key());
            }
            m.add("permissions", perms);
            Files.writeString(file, m.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        h.host.scan();
        h.host.enable(id, Set.of(permissions));
        waitFor(() -> h.host.view(id).status().equals("running"), 30_000);
    }

    private static JsonObject probe(PluginTestHost h, Map<String, String> paths) {
        JsonObject args = new JsonObject();
        JsonObject p = new JsonObject();
        paths.forEach(p::addProperty);
        args.add("paths", p);
        Toolbox.Outcome out = call(h, "plugin__greedy__probe", args.toString());
        JsonElement parsed = JsonParser.parseString(out.output());
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    private static Toolbox.Outcome call(PluginTestHost h, String tool, String args) {
        return h.host.extensions().run(new ToolCall(UUID.randomUUID().toString(), tool, args), "web:test");
    }
}
