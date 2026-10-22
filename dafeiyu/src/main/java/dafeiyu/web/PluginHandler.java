package dafeiyu.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.db.DatabaseException;
import dafeiyu.log.LogBuffer;
import dafeiyu.plugin.PluginHost;
import dafeiyu.plugin.PluginView;
import dafeiyu.plugin.install.PluginInstaller;
import dafeiyu.plugin.manifest.Permission;
import dafeiyu.plugin.manifest.PluginManifest;
import dafeiyu.plugin.store.PluginStore;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dafeiyu.util.Jsons.optString;

final class PluginHandler {

    private static final String PREFIX = "/api/plugins";
    private static final Map<String, String> STATUS = Map.of("invalid", "清单有问题", "incompatible", "版本不兼容", "stopped", "已停用",
            "needs-approval", "等待同意权限", "starting", "启动中", "running", "运行中", "restarting", "崩溃了，等待重启", "crashed", "已自动停用");

    private final PluginHost host;
    private final PluginInstaller installer;
    private final QqChatBridge bridge;
    private final LogBuffer log;

    PluginHandler(PluginHost host, PluginInstaller installer, QqChatBridge bridge, LogBuffer log) {
        this.host = host;
        this.installer = installer;
        this.bridge = bridge;
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        String[] parts = rest.isEmpty() || rest.equals("/") ? new String[0] : rest.substring(1).split("/");
        try {
            if (rest.equals("/install/upload") && method.equals("POST")) {
                if (Requests.isUpload(ex)) {
                    upload(ex);
                } else {
                    HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/zip"));
                }
                return;
            }
            if (!method.equals("GET") && !Requests.isJson(ex)) {
                HttpResponses.json(ex, 415, error("请求必须带 Content-Type: application/json"));
                return;
            }
            if (parts.length == 0 && method.equals("GET")) {
                HttpResponses.json(ex, overview().toString());
            } else if (rest.equals("/notifications") && method.equals("GET")) {
                JsonArray list = new JsonArray();
                host.notificationsAfter(Requests.intParam(ex, "after", 0)).forEach(list::add);
                HttpResponses.json(ex, list.toString());
            } else if (rest.equals("/config") && method.equals("PUT")) {
                JsonObject body = body(ex);
                if (body.has("prefix")) {
                    host.store().setPrefix(optString(body, "prefix"));
                }
                if (body.has("devMode")) {
                    host.store().setDevMode(body.get("devMode").getAsBoolean());
                }
                log.info("plugins: settings changed (prefix " + host.store().prefix() + ", dev mode " + host.store().devMode() + ")");
                HttpResponses.json(ex, overview().toString());
            } else if (rest.equals("/refresh") && method.equals("POST")) {
                host.scan();
                HttpResponses.json(ex, overview().toString());
            } else if (parts.length >= 2 && parts[0].equals("install")) {
                install(ex, method, parts);
            } else if (parts.length == 1 && method.equals("GET")) {
                HttpResponses.json(ex, detail(host.view(parts[0])).toString());
            } else if (parts.length == 1 && method.equals("DELETE")) {
                installer.uninstall(parts[0], "true".equals(query(ex, "data")));
                HttpResponses.json(ex, overview().toString());
            } else if (parts.length == 2 && parts[1].equals("icon") && method.equals("GET")) {
                icon(ex, host.view(parts[0]));
            } else if (parts.length == 2 && parts[1].equals("logs") && method.equals("GET")) {
                JsonArray list = new JsonArray();
                for (PluginStore.LogEntry e : host.store().logs(parts[0], Requests.intParam(ex, "after", 0), Math.min(500, Requests.intParam(ex, "limit", 200)))) {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", e.id());
                    item.addProperty("at", e.at());
                    item.addProperty("level", e.level());
                    item.addProperty("text", e.text());
                    list.add(item);
                }
                HttpResponses.json(ex, list.toString());
            } else if (parts.length == 4 && parts[1].equals("schedules") && parts[3].equals("run") && method.equals("POST")) {
                host.fireSchedule(parts[0], parts[2]);
                HttpResponses.json(ex, detail(host.view(parts[0])).toString());
            } else if (parts.length == 2 && method.equals("POST")) {
                action(ex, parts[0], parts[1]);
            } else if (parts.length == 2 && parts[1].equals("settings") && method.equals("PUT")) {
                PluginView view = host.view(parts[0]);
                if (view.manifest() == null) {
                    throw new IllegalArgumentException("插件清单有问题");
                }
                JsonObject values = body(ex).has("values") ? body(ex).getAsJsonObject("values") : new JsonObject();
                List<String> problems = host.store().saveValues(view.id(), view.manifest().settings(), values);
                if (!problems.isEmpty()) {
                    throw new IllegalArgumentException(String.join("；", problems));
                }
                host.settingsChanged(view.id());
                HttpResponses.json(ex, detail(host.view(view.id())).toString());
            } else if (parts.length == 2 && parts[1].equals("options") && method.equals("PUT")) {
                options(ex, parts[0]);
            } else {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            }
        } catch (PluginHost.NeedsApproval e) {
            JsonObject body = new JsonObject();
            body.addProperty("error", e.getMessage());
            body.add("missing", permissions(e.missing(), Set.of(), Set.of(), Set.of()));
            HttpResponses.json(ex, 409, body.toString());
        } catch (IllegalArgumentException | IllegalStateException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        } catch (DatabaseException e) {
            log.error(e.getMessage());
            HttpResponses.json(ex, 500, error(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HttpResponses.json(ex, 500, error("interrupted"));
        }
    }

    private void action(HttpExchange ex, String id, String action) throws IOException {
        switch (action) {
            case "enable" -> {
                JsonObject body = body(ex);
                host.enable(id, approvals(body));
            }
            case "disable" -> host.disable(id);
            case "reload" -> host.reload(id);
            default -> throw new IllegalArgumentException("没有这个操作：" + action);
        }
        HttpResponses.json(ex, detail(host.view(id)).toString());
    }

    private void options(HttpExchange ex, String id) throws IOException {
        PluginView view = host.view(id);
        JsonObject body = body(ex);
        PluginStore store = host.store();
        boolean restart = false;
        if (body.has("groups")) {
            List<String> groups = body.getAsJsonArray("groups").asList().stream().map(JsonElement::getAsString).map(String::strip)
                    .filter(s -> !s.isEmpty()).distinct().toList();
            store.setGroups(id, groups);
        }
        if (body.has("qqTools")) {
            store.setQqTools(id, body.get("qqTools").getAsBoolean());
            host.refreshCatalog();
        }
        if (body.has("sandbox")) {
            boolean on = body.get("sandbox").getAsBoolean();
            if (!on && !(body.has("confirm") && body.get("confirm").getAsBoolean())) {
                throw new IllegalArgumentException("关掉沙盒需要二次确认");
            }
            if (on != store.sandbox(id)) {
                store.setSandbox(id, on);
                restart = true;
                log.warn("plugins: sandbox for " + id + " turned " + (on ? "ON" : "OFF"));
            }
        }
        if (restart && view.enabled()) {
            host.reload(id);
        }
        HttpResponses.json(ex, detail(host.view(id)).toString());
    }

    private void install(HttpExchange ex, String method, String[] parts) throws IOException, InterruptedException {
        if (parts.length == 2 && parts[1].equals("url") && method.equals("POST")) {
            HttpResponses.json(ex, staged(installer.stageUrl(optString(body(ex), "url"))).toString());
        } else if (parts.length == 2 && parts[1].equals("git") && method.equals("POST")) {
            HttpResponses.json(ex, staged(installer.stageGit(optString(body(ex), "url"))).toString());
        } else if (parts.length == 3 && parts[2].equals("confirm") && method.equals("POST")) {
            JsonObject body = body(ex);
            String id = installer.confirm(parts[1], approvals(body), body.has("enable") && body.get("enable").getAsBoolean());
            HttpResponses.json(ex, detail(host.view(id)).toString());
        } else if (parts.length == 2 && method.equals("DELETE")) {
            installer.cancel(parts[1]);
            HttpResponses.json(ex, "{}");
        } else {
            HttpResponses.json(ex, 404, error("没有这个接口"));
        }
    }

    private void upload(HttpExchange ex) throws IOException {
        byte[] data;
        try (InputStream in = ex.getRequestBody()) {
            data = in.readNBytes((int) PluginInstaller.MAX_DOWNLOAD + 1);
        }
        String name = query(ex, "name");
        HttpResponses.json(ex, staged(installer.stageZip(data, name.isEmpty() ? "上传的压缩包" : name)).toString());
    }

    private static Set<Permission> approvals(JsonObject body) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        if (body.has("approve") && body.get("approve").isJsonArray()) {
            body.getAsJsonArray("approve").forEach(e -> Permission.of(e.getAsString()).ifPresent(out::add));
        }
        return out;
    }

    private JsonObject overview() {
        JsonObject o = new JsonObject();
        o.addProperty("hostVersion", PluginHost.VERSION);
        o.addProperty("prefix", host.store().prefix());
        o.addProperty("devMode", host.store().devMode());
        o.addProperty("pluginsDir", host.pluginsDir().toString());
        JsonArray list = new JsonArray();
        host.views().forEach(v -> list.add(summary(v)));
        o.add("plugins", list);
        JsonArray groups = new JsonArray();
        bridge.groupNames().forEach((id, name) -> {
            JsonObject g = new JsonObject();
            g.addProperty("groupId", id);
            g.addProperty("name", name);
            groups.add(g);
        });
        o.add("groups", groups);
        return o;
    }

    private JsonObject summary(PluginView v) {
        JsonObject o = new JsonObject();
        PluginManifest m = v.manifest();
        o.addProperty("id", v.id());
        o.addProperty("name", v.name());
        o.addProperty("version", m == null ? "" : m.version());
        o.addProperty("author", m == null ? "" : m.author());
        o.addProperty("description", m == null ? "" : m.description());
        o.addProperty("origin", v.origin());
        o.addProperty("status", v.status());
        o.addProperty("statusLabel", STATUS.getOrDefault(v.status(), v.status()));
        o.addProperty("enabled", v.enabled());
        o.addProperty("lastError", v.lastError());
        o.addProperty("hasIcon", Files.isRegularFile(v.folder().resolve("icon.png")));
        o.add("problems", strings(v.problems()));
        o.add("warnings", strings(v.warnings()));
        o.addProperty("commands", m == null ? 0 : m.commands().size());
        o.addProperty("tools", v.tools().size());
        o.addProperty("sandbox", host.store().sandbox(v.id()));
        return o;
    }

    private JsonObject detail(PluginView v) {
        JsonObject o = summary(v);
        PluginManifest m = v.manifest();
        PluginStore store = host.store();
        o.addProperty("folder", v.folder().toString());
        o.addProperty("startupMillis", v.startupMillis());
        o.addProperty("pid", v.pid());
        o.addProperty("runningSince", v.runningSince());
        o.addProperty("qqTools", store.qqTools(v.id()));
        o.addProperty("kvBytes", store.kvBytes(v.id()));
        o.add("groups", strings(store.groups(v.id())));
        o.addProperty("readme", readme(v.folder()));
        o.addProperty("hasWeb", Files.isRegularFile(v.folder().resolve("web").resolve("index.html")));
        if (m == null) {
            return o;
        }
        o.add("permissions", permissions(List.copyOf(m.permissions()), v.approved(), v.granted(), m.permissions()));
        o.add("settingsSchema", m.settings());
        o.add("settings", store.publicValues(v.id(), m.settings()));
        JsonArray commands = new JsonArray();
        for (PluginManifest.Command c : m.commands()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", c.name());
            item.add("aliases", strings(c.aliases()));
            item.addProperty("description", c.description());
            item.add("scope", strings(List.copyOf(c.scope())));
            commands.add(item);
        }
        o.add("commandList", commands);
        JsonArray tools = new JsonArray();
        for (PluginManifest.Tool t : v.tools()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", t.name());
            item.addProperty("fullName", "plugin__" + v.id() + "__" + t.name());
            item.addProperty("description", t.description());
            item.addProperty("mind", t.mind());
            item.addProperty("timeout", t.timeout());
            tools.add(item);
        }
        o.add("toolList", tools);
        o.add("events", strings(List.copyOf(m.events())));
        o.addProperty("intercept", m.intercept());
        o.addProperty("prompt", m.prompt());
        o.addProperty("filter", m.filter());
        o.addProperty("priority", m.priority());
        JsonArray schedules = new JsonArray();
        for (PluginManifest.Schedule s : m.schedules()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", s.id());
            item.addProperty("cron", s.cron());
            item.addProperty("description", s.description());
            LocalDateTime next = v.nextFire().get(s.id());
            item.addProperty("next", next == null ? "" : next.toString().replace('T', ' '));
            schedules.add(item);
        }
        o.add("schedules", schedules);
        return o;
    }

    private static JsonArray permissions(List<Permission> list, Set<Permission> approved, Set<Permission> granted, Set<Permission> declared) {
        JsonArray out = new JsonArray();
        for (Permission p : list) {
            JsonObject item = new JsonObject();
            item.addProperty("key", p.key());
            item.addProperty("label", p.label());
            item.addProperty("description", p.description());
            item.addProperty("approved", approved.contains(p));
            item.addProperty("granted", granted.contains(p));
            out.add(item);
        }
        return out;
    }

    private JsonObject staged(PluginInstaller.Staged s) {
        PluginManifest m = s.manifest();
        JsonObject o = new JsonObject();
        o.addProperty("token", s.token());
        o.addProperty("id", m.id());
        o.addProperty("name", m.name());
        o.addProperty("version", m.version());
        o.addProperty("author", m.author());
        o.addProperty("description", m.description());
        o.addProperty("source", s.source());
        o.addProperty("installedVersion", s.installedVersion());
        o.addProperty("downgrade", s.downgrade());
        JsonArray perms = permissions(List.copyOf(m.permissions()), Set.of(), Set.of(), m.permissions());
        for (JsonElement e : perms) {
            String key = e.getAsJsonObject().get("key").getAsString();
            e.getAsJsonObject().addProperty("new", s.newPermissions().stream().anyMatch(p -> p.key().equals(key)));
        }
        o.add("permissions", perms);
        o.addProperty("commands", m.commands().size());
        o.addProperty("tools", m.tools().size());
        o.add("events", strings(List.copyOf(m.events())));
        o.addProperty("schedules", m.schedules().size());
        return o;
    }

    private static void icon(HttpExchange ex, PluginView v) throws IOException {
        Path file = v.folder().resolve("icon.png");
        if (!Files.isRegularFile(file) || Files.size(file) > 1024 * 1024) {
            HttpResponses.notFound(ex);
            return;
        }
        HttpResponses.send(ex, 200, "image/png", Files.readAllBytes(file));
    }

    private static String readme(Path folder) {
        Path file = folder.resolve("README.md");
        try {
            String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            return text.length() > 20_000 ? text.substring(0, 20_000) + "\n…" : text;
        } catch (IOException e) {
            return "";
        }
    }

    private static JsonArray strings(List<String> list) {
        JsonArray out = new JsonArray();
        list.forEach(out::add);
        return out;
    }

    private static JsonObject body(HttpExchange ex) throws IOException {
        Object cached = ex.getAttribute("json-body");
        if (cached instanceof JsonObject o) {
            return o;
        }
        String text;
        try (InputStream in = ex.getRequestBody()) {
            text = new String(in.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
        }
        JsonObject o;
        try {
            JsonElement e = text.isBlank() ? new JsonObject() : JsonParser.parseString(text);
            o = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("请求不是合法的 JSON");
        }
        ex.setAttribute("json-body", o);
        return o;
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) {
            for (String pair : q.split("&")) {
                if (pair.startsWith(key + "=")) {
                    return URLDecoder.decode(pair.substring(key.length() + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    private static String error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body.toString();
    }
}
