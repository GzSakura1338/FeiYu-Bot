package dafeiyu.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.Extensions;
import dafeiyu.agent.qq.MessageGate;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Workspace;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.plugin.api.HostApi;
import dafeiyu.plugin.api.QqPort;
import dafeiyu.plugin.manifest.Permission;
import dafeiyu.plugin.manifest.PluginManifest;
import dafeiyu.plugin.mcp.McpManager;
import dafeiyu.plugin.rpc.RpcChannel;
import dafeiyu.plugin.store.PluginStore;
import dafeiyu.sandbox.Platform;
import dafeiyu.sandbox.Sandbox;
import dafeiyu.sandbox.Sandboxes;
import dafeiyu.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class PluginHost implements HostApi.Services {

    public static final String VERSION = "0.2.0";
    static final int LOG_LINES_PER_SECOND = 50;
    static final long INIT_TIMEOUT_MS = 15_000;
    static final int CRASH_LIMIT = 5;
    static final long CRASH_WINDOW_MS = 10 * 60_000;
    static final long MAX_BACKOFF_MS = 5 * 60_000;
    static final long STABLE_MS = 60_000;
    private static final Set<String> UNWATCHED = Set.of("__pycache__", ".git", "node_modules", ".venv");

    public static final class NeedsApproval extends RuntimeException {
        private final List<Permission> missing;

        NeedsApproval(List<Permission> missing) {
            super("需要先同意这些权限：" + String.join("、", missing.stream().map(Permission::label).toList()));
            this.missing = missing;
        }

        public List<Permission> missing() {
            return missing;
        }
    }

    private final ChatAgent agent;
    private final QqPort qq;
    private final LogBuffer log;
    private final PluginStore store;
    private final Path pluginsDir;
    private final Path dataDir;
    private final Path sdkDir;
    private final List<Path> extraDirs;
    private final Map<String, PluginSlot> slots = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "plugin-host");
        t.setDaemon(true);
        return t;
    });
    private final HostApi api;
    private final PluginDispatcher dispatcher;
    private final PluginScheduler scheduler;
    private final List<JsonObject> notifications = new ArrayList<>();
    private volatile McpManager mcp;
    private volatile boolean connected;
    private volatile boolean shuttingDown;
    private String python;
    private String node;
    private long notificationIds;

    public PluginHost(ChatAgent agent, QqPort qq, MessageArchive archive, PluginStore store, Path pluginsDir,
                      Path dataDir, List<Path> extraDirs, LogBuffer log) {
        this.agent = agent;
        this.qq = qq;
        this.store = store;
        this.log = log;
        this.pluginsDir = pluginsDir.toAbsolutePath().normalize();
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.sdkDir = this.pluginsDir.resolve("sdk");
        this.extraDirs = extraDirs.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        this.api = new HostApi(this, agent, qq, archive, store);
        this.dispatcher = new PluginDispatcher(this, agent, store);
        this.scheduler = new PluginScheduler(this);
    }

    public MessageGate gate() {
        return dispatcher;
    }

    public Extensions extensions() {
        return dispatcher;
    }

    public void setMcp(McpManager mcp) {
        this.mcp = mcp;
    }

    public McpManager mcp() {
        return mcp;
    }

    public PluginStore store() {
        return store;
    }

    public Path pluginsDir() {
        return pluginsDir;
    }

    public Path dataDir() {
        return dataDir;
    }

    public List<String> toolNames() {
        return dispatcher.toolNames();
    }

    public void start() {
        try {
            Files.createDirectories(pluginsDir);
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("plugins: cannot create plugin folders: " + e.getMessage());
        }
        scan();
        warmUp();
        for (PluginSlot slot : sorted()) {
            if (store.enabled().contains(slot.id)) {
                synchronized (this) {
                    launch(slot);
                }
            }
        }
        timers.scheduleAtFixedRate(this::tick, 5, 5, TimeUnit.SECONDS);
        timers.scheduleAtFixedRate(this::flushSuppressed, 1, 1, TimeUnit.SECONDS);
        log.info("plugins: host " + VERSION + " ready, " + slots.size() + " plugin(s) found in " + pluginsDir);
    }

    public void shutdown() {
        shuttingDown = true;
        timers.shutdownNow();
        List<CompletableFuture<Void>> stops = new ArrayList<>();
        for (PluginSlot slot : slots.values()) {
            PluginProcess p = slot.process;
            if (p != null) {
                stops.add(CompletableFuture.runAsync(p::stop));
            }
        }
        try {
            CompletableFuture.allOf(stops.toArray(CompletableFuture[]::new)).get(4, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        for (PluginSlot slot : slots.values()) {
            PluginProcess p = slot.process;
            if (p != null) {
                p.kill();
            }
        }
        McpManager m = mcp;
        if (m != null) {
            m.shutdown();
        }
    }

    public synchronized void scan() {
        Map<String, PluginSlot> found = new LinkedHashMap<>();
        for (Path folder : candidates()) {
            String id = folder.getFileName().toString();
            String origin = folder.getParent().equals(pluginsDir) ? "installed"
                    : folder.getParent().equals(pluginsDir.resolve("examples")) ? "example" : "dev";
            if (found.containsKey(id)) {
                PluginSlot dup = new PluginSlot(id + "@" + origin + found.size(), folder, origin);
                dup.status = PluginSlot.INVALID;
                dup.problems = List.of("id「" + id + "」和 " + found.get(id).folder + " 里的插件重复了，这个没有加载");
                found.put(dup.id, dup);
                log.warn("plugins: duplicate plugin id " + id + " in " + folder);
                continue;
            }
            PluginSlot existing = slots.get(id);
            PluginSlot slot = existing != null && existing.folder.equals(folder) ? existing : new PluginSlot(id, folder, origin);
            found.put(id, slot);
            if (slot.process == null) {
                loadManifest(slot);
            }
        }
        for (PluginSlot old : slots.values()) {
            if (found.get(old.id) != old) {
                cancelRestart(old);
                stopProcess(old);
            }
        }
        slots.clear();
        slots.putAll(found);
        dispatcher.rebuild();
    }

    private List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        List<Path> roots = new ArrayList<>(List.of(pluginsDir, pluginsDir.resolve("examples")));
        roots.addAll(extraDirs);
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> list = Files.list(root)) {
                list.filter(Files::isDirectory).filter(p -> Files.isRegularFile(p.resolve(PluginManifest.FILE))).sorted().forEach(out::add);
            } catch (IOException e) {
                log.warn("plugins: cannot list " + root + ": " + e.getMessage());
            }
        }
        return out;
    }

    private void loadManifest(PluginSlot slot) {
        try {
            PluginManifest m = PluginManifest.read(slot.folder);
            List<String> problems = new ArrayList<>();
            for (PluginManifest.Tool t : m.tools()) {
                if (PluginDispatcher.toolName(slot.id, t.name()).length() > 64) {
                    problems.add("工具「" + t.name() + "」加上插件 id 后名字超过 64 个字符");
                }
            }
            if (!problems.isEmpty()) {
                throw new PluginManifest.Invalid(problems);
            }
            slot.manifest = m;
            slot.tools = m.tools();
            slot.problems = List.of();
            if (!m.hostSatisfied(VERSION)) {
                slot.status = PluginSlot.INCOMPATIBLE;
                slot.problems = List.of("需要主程序版本 " + m.host() + "，现在是 " + VERSION);
            } else if (!PluginSlot.CRASHED.equals(slot.status)) {
                slot.status = PluginSlot.STOPPED;
            }
        } catch (PluginManifest.Invalid e) {
            slot.manifest = null;
            slot.status = PluginSlot.INVALID;
            slot.problems = e.problems();
        }
        slot.folderStamp = stamp(slot.folder);
    }

    List<PluginSlot> sorted() {
        return slots.values().stream().sorted(Comparator.comparing(s -> s.id)).toList();
    }

    PluginSlot slot(String id) {
        return slots.get(id);
    }

    PluginSlot require(String id) {
        PluginSlot slot = slots.get(id);
        if (slot == null) {
            throw new IllegalArgumentException("没有这个插件：" + id);
        }
        return slot;
    }

    public synchronized void enable(String id, Set<Permission> approve) {
        PluginSlot slot = require(id);
        PluginManifest m = slot.manifest;
        if (m == null) {
            throw new IllegalArgumentException("插件清单有问题，不能启用：" + String.join("；", slot.problems));
        }
        if (PluginSlot.INCOMPATIBLE.equals(slot.status)) {
            throw new IllegalArgumentException(String.join("；", slot.problems));
        }
        Set<Permission> approved = EnumSet.noneOf(Permission.class);
        approved.addAll(store.approved(id));
        if (approve != null) {
            approve.stream().filter(m.permissions()::contains).forEach(approved::add);
        }
        store.approve(id, approved);
        List<Permission> missing = m.permissions().stream().filter(p -> !approved.contains(p)).sorted().toList();
        if (!missing.isEmpty()) {
            slot.status = PluginSlot.NEEDS_APPROVAL;
            throw new NeedsApproval(missing);
        }
        store.setEnabled(id, true);
        slot.crashes.clear();
        slot.backoff = 0;
        slot.lastError = "";
        launch(slot);
        log.info("plugins: " + id + " enabled");
    }

    public synchronized void disable(String id) {
        PluginSlot slot = require(id);
        store.setEnabled(id, false);
        cancelRestart(slot);
        stopProcess(slot);
        if (slot.manifest != null && !PluginSlot.INCOMPATIBLE.equals(slot.status)) {
            slot.status = PluginSlot.STOPPED;
        }
        dispatcher.rebuild();
        log.info("plugins: " + id + " disabled");
    }

    public synchronized void reload(String id) {
        PluginSlot slot = require(id);
        cancelRestart(slot);
        stopProcess(slot);
        loadManifest(slot);
        dispatcher.rebuild();
        if (store.enabled().contains(id) && slot.manifest != null) {
            launch(slot);
        }
        log.info("plugins: " + id + " reloaded");
    }

    public synchronized void forgetSlot(String id) {
        PluginSlot slot = slots.remove(id);
        if (slot != null) {
            cancelRestart(slot);
            stopProcess(slot);
        }
        dispatcher.rebuild();
    }

    private void launch(PluginSlot slot) {
        PluginManifest m = slot.manifest;
        if (m == null || shuttingDown || (slot.process != null && slot.process.alive())) {
            return;
        }
        if (!m.hostSatisfied(VERSION)) {
            slot.status = PluginSlot.INCOMPATIBLE;
            return;
        }
        if (!store.approved(slot.id).containsAll(m.permissions())) {
            slot.status = PluginSlot.NEEDS_APPROVAL;
            return;
        }
        slot.status = PluginSlot.STARTING;
        slot.resume();
        long began = System.nanoTime();
        PluginProcess process;
        try {
            process = PluginProcess.start(slot.id, launchSpec(slot, m), (method, params) -> api.handle(caller(slot), method, params),
                    (level, text) -> pluginLog(slot, level, text), p -> exited(slot, p));
        } catch (ToolFailure | RuntimeException e) {
            slot.lastError = "启动失败：" + e.getMessage();
            pluginLog(slot, "error", slot.lastError);
            log.warn("plugins: " + slot.id + " failed to start: " + e.getMessage());
            crashed(slot, null);
            return;
        }
        slot.process = process;
        slot.folderStamp = stamp(slot.folder);
        process.call("initialize", initParams(slot, m), INIT_TIMEOUT_MS).whenComplete((result, error) -> {
            if (slot.process != process) {
                return;
            }
            if (error != null) {
                String tail = slot.recentStderr();
                slot.lastError = "初始化失败：" + describe(error) + (tail.isBlank() ? "" : "\n" + tail);
                pluginLog(slot, "error", "初始化失败：" + describe(error));
                synchronized (PluginHost.this) {
                    crashed(slot, process);
                }
                return;
            }
            slot.tools = result != null && result.isJsonObject() && result.getAsJsonObject().has("tools")
                    ? mergeTools(slot, m, result.getAsJsonObject().get("tools")) : m.tools();
            slot.startupMillis = (System.nanoTime() - began) / 1_000_000;
            slot.runningSince = System.currentTimeMillis();
            slot.status = PluginSlot.RUNNING;
            scheduler.arm(slot);
            dispatcher.rebuild();
            boolean sandbox = store.sandbox(slot.id);
            log.info("plugins: " + slot.id + " started (pid " + process.pid() + ", " + slot.startupMillis + " ms, "
                    + (sandbox ? "sandboxed" : "NOT sandboxed") + ")");
            pluginLog(slot, "info", "已启动，用时 " + slot.startupMillis + " 毫秒" + (sandbox ? "，在沙盒里" : "，没开沙盒"));
        });
    }

    private JsonObject initParams(PluginSlot slot, PluginManifest m) {
        JsonObject params = new JsonObject();
        params.addProperty("hostVersion", VERSION);
        params.addProperty("pluginId", slot.id);
        params.add("settings", store.values(slot.id, m.settings()));
        params.addProperty("dataDir", dataDir.resolve(slot.id).toString());
        params.addProperty("prefix", store.prefix());
        JsonArray perms = new JsonArray();
        granted(slot).forEach(p -> perms.add(p.key()));
        params.add("permissions", perms);
        params.addProperty("sandbox", store.sandbox(slot.id));
        return params;
    }

    private HostApi.Caller caller(PluginSlot slot) {
        return new HostApi.Caller(slot.id, slot.name(), granted(slot));
    }

    Set<Permission> granted(PluginSlot slot) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        PluginManifest m = slot.manifest;
        if (m != null) {
            store.approved(slot.id).stream().filter(m.permissions()::contains).forEach(out::add);
        }
        return out;
    }

    private List<PluginManifest.Tool> mergeTools(PluginSlot slot, PluginManifest m, JsonElement reported) {
        List<String> problems = new ArrayList<>();
        List<JsonObject> list = new ArrayList<>();
        if (reported.isJsonArray()) {
            reported.getAsJsonArray().forEach(e -> {
                if (e.isJsonObject()) {
                    list.add(e.getAsJsonObject());
                }
            });
        }
        Map<String, PluginManifest.Tool> merged = new LinkedHashMap<>();
        m.tools().forEach(t -> merged.put(t.name(), t));
        for (JsonObject raw : list) {
            if (merged.size() >= PluginManifest.MAX_TOOLS && !merged.containsKey(Jsons.optString(raw, "name"))) {
                problems.add("工具太多了（上报了 " + list.size() + " 个），只保留前 " + PluginManifest.MAX_TOOLS + " 个");
                break;
            }
            List<String> own = new ArrayList<>();
            List<PluginManifest.Tool> parsed = PluginManifest.tools(List.of(raw), m.toolTimeout(), own);
            if (!own.isEmpty() || parsed.isEmpty()) {
                problems.add("工具「" + Jsons.optString(raw, "name") + "」没有加载：" + String.join("；", own));
                continue;
            }
            PluginManifest.Tool t = parsed.getFirst();
            if (PluginDispatcher.toolName(slot.id, t.name()).length() > 64) {
                problems.add("工具「" + t.name() + "」名字太长，没有加载");
                continue;
            }
            merged.put(t.name(), t);
        }
        slot.warnings.removeIf(w -> w.startsWith("工具"));
        for (String p : problems) {
            slot.warnings.add(p);
            pluginLog(slot, "warn", p);
        }
        return merged.values().stream().limit(PluginManifest.MAX_TOOLS).toList();
    }

    private Sandbox.Spec launchSpec(PluginSlot slot, PluginManifest m) throws ToolFailure {
        boolean sandbox = store.sandbox(slot.id);
        Sandbox box = Sandboxes.current();
        if (sandbox && box.missing() != null) {
            throw new ToolFailure(box.missing() + "。也可以在插件详情里关掉这个插件的沙盒再启动");
        }
        Set<Permission> perms = granted(slot);
        Path data = dataDir.resolve(slot.id);
        try {
            Files.createDirectories(data);
        } catch (IOException e) {
            throw new ToolFailure("cannot create " + data + ": " + e.getMessage());
        }
        List<String> command = new ArrayList<>();
        List<Path> interpreterDirs = new ArrayList<>();
        List<Path> shared = new ArrayList<>();
        switch (m.command()) {
            case "python" -> {
                String exe = interpreter(true);
                if (exe == null) {
                    throw new ToolFailure("这台电脑上找不到 Python");
                }
                command.addAll(List.of(exe, "-u"));
                interpreterDirs.add(Path.of(exe).getParent());
                shared.addAll(Sandboxes.runtimeRoots(Path.of(exe)));
            }
            case "node" -> {
                String exe = interpreter(false);
                if (exe == null) {
                    throw new ToolFailure("这台电脑上找不到 Node.js");
                }
                command.addAll(List.of(exe, "--preserve-symlinks", "--preserve-symlinks-main"));
                interpreterDirs.add(Path.of(exe).getParent());
                shared.addAll(Sandboxes.runtimeRoots(Path.of(exe)));
                ensurePackageJson(slot.folder, slot.id, slot);
                ensurePackageJson(sdkDir, "feiyu-plugin-sdk", null);
            }
            default -> {
                Path exe = slot.folder.resolve(m.command()).normalize();
                if (!exe.startsWith(slot.folder) || !Files.isRegularFile(exe)) {
                    throw new ToolFailure("run.command 只能是 python、node 或插件文件夹里的程序：" + m.command());
                }
                command.add(exe.toString());
            }
        }
        command.addAll(m.args());
        Map<String, String> env = box.environment(data, interpreterDirs);
        m.env().forEach((k, v) -> {
            if (!env.containsKey(k) && !k.toUpperCase().startsWith("FEIYU_")) {
                env.put(k, v);
            }
        });
        env.put("PYTHONPATH", sdkDir.toString());
        env.put("NODE_PATH", sdkDir.toString());
        env.put("FEIYU_PLUGIN_ID", slot.id);
        env.put("FEIYU_DATA_DIR", data.toString());
        env.put("FEIYU_HOST_VERSION", VERSION);
        env.put("no_proxy", "*");
        env.put("NO_PROXY", "*");
        Workspace ws = perms.contains(Permission.FILES) ? agent.workspaces().workspace() : null;
        if (ws != null) {
            env.put("FEIYU_WORKSPACE", ws.root().toString());
        }
        if (Files.isDirectory(sdkDir)) {
            shared.add(sdkDir);
        }
        boolean network = !sandbox || perms.contains(Permission.NETWORK);
        if (sandbox && network && box.networkMissing() != null) {
            pluginLog(slot, "warn", "这次断网启动：" + box.networkMissing());
            network = false;
        }
        return Sandbox.Spec.exec(command, slot.folder, env)
                .isolated(sandbox, "dafeiyu.plugin." + slot.id, network)
                .reading(List.of(slot.folder), shared)
                .writing(ws == null ? List.of(data) : List.of(data, ws.root()))
                .limits(sandbox ? 512L << 20 : 1024L << 20, sandbox ? 50 : 80, sandbox ? 16 : 32);
    }

    private void ensurePackageJson(Path folder, String name, PluginSlot slot) {
        Path file = folder.resolve("package.json");
        if (!Files.isDirectory(folder) || Files.exists(file)) {
            return;
        }
        try {
            Files.writeString(file, "{\n  \"name\": \"" + name + "\",\n  \"private\": true,\n  \"type\": \"commonjs\"\n}\n");
            if (slot != null) {
                pluginLog(slot, "info", "补了一个 package.json：Node 需要它来确定模块类型");
            }
        } catch (IOException e) {
            log.warn("plugins: cannot write package.json in " + folder + ": " + e.getMessage());
        }
    }

    private void warmUp() {
        Sandboxes.ledger(new Sandboxes.Ledger() {
            @Override
            public boolean granted(String key) {
                return store.granted(key);
            }

            @Override
            public void mark(String key) {
                store.markGranted(key);
            }
        });
        Thread.ofVirtual().name("plugin-warmup").start(() -> {
            try {
                List<Path> shared = new ArrayList<>();
                for (boolean py : new boolean[] {true, false}) {
                    String exe = interpreter(py);
                    if (exe != null) {
                        shared.addAll(Sandboxes.runtimeRoots(Path.of(exe)));
                    }
                }
                if (Files.isDirectory(sdkDir)) {
                    shared.add(sdkDir);
                }
                Sandboxes.current().warm(shared);
            } catch (RuntimeException e) {
                log.warn("plugins: preparing the sandbox failed: " + e.getMessage());
            }
        });
    }

    private synchronized String interpreter(boolean pythonWanted) {
        if (pythonWanted) {
            for (String name : Platform.windows() ? List.of("python") : List.of("python3", "python")) {
                if (python == null) {
                    python = probe(name, "-c", "import sys;print(sys.executable)");
                }
            }
            return python;
        }
        if (node == null) {
            node = probe("node", "-e", "console.log(process.execPath)");
        }
        return node;
    }

    private static String probe(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 && !out.isEmpty() && !out.contains("\n") && Files.isRegularFile(Path.of(out)) ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void exited(PluginSlot slot, PluginProcess process) {
        if (process.stopping() || shuttingDown) {
            return;
        }
        synchronized (this) {
            if (slot.process != process) {
                return;
            }
            String tail = slot.recentStderr();
            int code = process.exitCode();
            slot.lastError = "进程意外退出（退出码 " + code + "）" + (tail.isBlank() ? "" : "\n" + tail);
            pluginLog(slot, "error", "进程意外退出（退出码 " + code + "）");
            log.warn("plugins: " + slot.id + " exited unexpectedly (code " + code + ")");
            crashed(slot, process);
        }
    }

    private void crashed(PluginSlot slot, PluginProcess process) {
        if (process != null) {
            if (slot.process != process) {
                return;
            }
            process.kill();
        }
        slot.process = null;
        dispatcher.forget(slot.id);
        dispatcher.rebuild();
        long now = System.currentTimeMillis();
        if (slot.runningSince > 0 && now - slot.runningSince >= STABLE_MS) {
            slot.backoff = 0;
        }
        slot.runningSince = 0;
        slot.crashes.addLast(now);
        while (!slot.crashes.isEmpty() && now - slot.crashes.peekFirst() > CRASH_WINDOW_MS) {
            slot.crashes.removeFirst();
        }
        if (slot.crashes.size() >= CRASH_LIMIT) {
            slot.status = PluginSlot.CRASHED;
            String tail = slot.recentStderr();
            slot.lastError = "10 分钟内崩溃了 " + slot.crashes.size() + " 次，已自动停用。" + (tail.isBlank() ? "" : "最后的错误输出：\n" + tail);
            store.setEnabled(slot.id, false);
            pluginLog(slot, "error", "10 分钟内崩溃 " + CRASH_LIMIT + " 次，已自动停用");
            log.warn("plugins: " + slot.id + " crashed " + CRASH_LIMIT + " times in 10 minutes and was disabled");
            return;
        }
        if (!store.enabled().contains(slot.id)) {
            slot.status = PluginSlot.STOPPED;
            return;
        }
        long delay = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(20, slot.backoff));
        slot.backoff++;
        slot.status = PluginSlot.RESTARTING;
        pluginLog(slot, "warn", "将在 " + delay / 1000 + " 秒后自动重启");
        cancelRestart(slot);
        slot.restart = timers.schedule(() -> {
            synchronized (PluginHost.this) {
                slot.restart = null;
                if (store.enabled().contains(slot.id) && PluginSlot.RESTARTING.equals(slot.status)) {
                    launch(slot);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelRestart(PluginSlot slot) {
        if (slot.restart != null) {
            slot.restart.cancel(false);
            slot.restart = null;
        }
    }

    private void stopProcess(PluginSlot slot) {
        PluginProcess p = slot.process;
        slot.process = null;
        if (p != null) {
            p.stop();
        }
        dispatcher.forget(slot.id);
        slot.nextFire.clear();
    }

    void pluginLog(PluginSlot slot, String level, String text) {
        if (level.equals("stderr")) {
            slot.stderr(text);
        }
        if (!slot.admitLog(System.currentTimeMillis())) {
            return;
        }
        try {
            store.log(slot.id, level, text);
        } catch (RuntimeException ignored) {
        }
    }

    private void flushSuppressed() {
        for (PluginSlot slot : slots.values()) {
            int n = slot.takeSuppressed();
            if (n > 0) {
                try {
                    store.log(slot.id, "warn", "日志太多，这一秒省略了 " + n + " 行");
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void tick() {
        try {
            boolean now = qq.connected();
            if (now != connected) {
                connected = now;
                dispatcher.broadcast(now ? "bot.connected" : "bot.disconnected", new JsonObject());
            }
            scheduler.fire(LocalDateTime.now());
            dispatcher.expire();
            if (store.devMode()) {
                for (PluginSlot slot : sorted()) {
                    if (store.enabled().contains(slot.id) && slot.manifest != null) {
                        long stamp = stamp(slot.folder);
                        if (stamp != slot.folderStamp) {
                            slot.folderStamp = stamp;
                            pluginLog(slot, "info", "开发模式：文件有改动，自动重新加载");
                            reload(slot.id);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("plugins: tick failed: " + e);
        }
    }

    private static long stamp(Path folder) {
        long[] sum = {0};
        try {
            Files.walkFileTree(folder, Set.of(), 4, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return UNWATCHED.contains(String.valueOf(dir.getFileName())) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        sum[0] += attrs.lastModifiedTime().toMillis() * 31 + attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return 0;
        }
        return sum[0];
    }

    public void onNapcatEvent(JsonObject event) {
        dispatcher.notice(event);
    }

    public void onConnected() {
        connected = true;
        dispatcher.broadcast("bot.connected", new JsonObject());
    }

    public void settingsChanged(String id) {
        PluginSlot slot = require(id);
        PluginManifest m = slot.manifest;
        if (m != null && slot.running()) {
            JsonObject params = new JsonObject();
            params.add("settings", store.values(id, m.settings()));
            slot.process.notify("settings/changed", params);
        }
        dispatcher.rebuild();
    }

    public void refreshCatalog() {
        dispatcher.rebuild();
    }

    @Override
    public HostApi.ReplyTarget useReply(String pluginId, String ctx) {
        return dispatcher.useReply(pluginId, ctx);
    }

    @Override
    public void log(String pluginId, String level, String text) {
        PluginSlot slot = slots.get(pluginId);
        if (slot != null) {
            pluginLog(slot, level, text);
        }
    }

    @Override
    public synchronized void notifyWeb(String pluginId, String text) {
        PluginSlot slot = slots.get(pluginId);
        JsonObject n = new JsonObject();
        n.addProperty("id", ++notificationIds);
        n.addProperty("plugin", pluginId);
        n.addProperty("name", slot == null ? pluginId : slot.name());
        n.addProperty("text", text);
        n.addProperty("at", System.currentTimeMillis());
        notifications.add(n);
        while (notifications.size() > 50) {
            notifications.removeFirst();
        }
        log(pluginId, "notify", text);
    }

    public synchronized List<JsonObject> notificationsAfter(long id) {
        return notifications.stream().filter(n -> n.get("id").getAsLong() > id).toList();
    }

    @Override
    public void denied(String pluginId, String method, Permission missing) {
        String perm = missing == null ? "allowed groups" : missing.key();
        log.warn("plugins: " + pluginId + " called " + method + " without permission " + perm);
        log(pluginId, "warn", "越权调用 " + method + "（缺少 " + (missing == null ? "群权限" : "权限 " + missing.key()) + "），已拒绝");
    }

    @Override
    public void toolsChanged(String pluginId) {
        PluginSlot slot = slots.get(pluginId);
        if (slot == null || !slot.running()) {
            return;
        }
        slot.process.call("tools/list", new JsonObject(), 10_000).whenComplete((result, error) -> {
            if (error == null && result != null && slot.manifest != null) {
                JsonElement tools = result.isJsonObject() && result.getAsJsonObject().has("tools") ? result.getAsJsonObject().get("tools") : result;
                slot.tools = mergeTools(slot, slot.manifest, tools);
                dispatcher.rebuild();
                pluginLog(slot, "info", "工具列表更新了，现在有 " + slot.tools.size() + " 个");
            }
        });
    }

    LogBuffer appLog() {
        return log;
    }

    static String describe(Throwable e) {
        Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        if (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof RpcChannel.RpcError r) {
            return r.getMessage() + " (" + r.code() + ")";
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    public JsonObject webRequest(String id, String method, String path, String query, String body) {
        PluginSlot slot = require(id);
        if (!slot.running()) {
            throw new IllegalArgumentException("插件没在运行");
        }
        JsonObject params = new JsonObject();
        params.addProperty("method", method);
        params.addProperty("path", path);
        params.addProperty("query", query == null ? "" : query);
        params.addProperty("body", body);
        try {
            JsonElement r = slot.process.call("web/request", params, 15_000).get(15_500, TimeUnit.MILLISECONDS);
            return r != null && r.isJsonObject() ? r.getAsJsonObject() : new JsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (Exception e) {
            throw new IllegalStateException(describe(e));
        }
    }

    public void fireSchedule(String pluginId, String scheduleId) {
        scheduler.fireNow(require(pluginId), scheduleId);
    }

    public LocalDateTime nextFire(String pluginId, String scheduleId) {
        PluginSlot slot = slots.get(pluginId);
        return slot == null ? null : slot.nextFire.get(scheduleId);
    }

    public List<PluginView> views() {
        return sorted().stream().map(this::view).toList();
    }

    public PluginView view(String id) {
        return view(require(id));
    }

    private PluginView view(PluginSlot slot) {
        PluginProcess p = slot.process;
        return new PluginView(slot.id, slot.folder, slot.origin, slot.manifest, slot.problems, List.copyOf(slot.warnings), slot.status,
                slot.lastError, store.enabled().contains(slot.id), slot.tools, slot.startupMillis, p == null ? -1 : p.pid(),
                slot.runningSince, Map.copyOf(slot.nextFire), granted(slot), store.approved(slot.id));
    }
}
