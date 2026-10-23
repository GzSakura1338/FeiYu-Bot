package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class ShellTool implements Tool {

    private static final int DEFAULT_TIMEOUT = 60;
    private static final int MAX_TIMEOUT = 600;
    private static final int MAX_COMMAND = 8_000;
    private static final Semaphore SLOTS = new Semaphore(2);
    private static final Set<Path> PREPARED = ConcurrentHashMap.newKeySet();
    private static volatile List<Path> interpreters;
    private static volatile Path curlHome;

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "command": {"type": "string", "description": "The command to run with Windows cmd.exe"},
              "timeout": {"type": "number", "description": "Optional timeout in seconds (default 60, maximum 600)"},
              "workdir": {"type": "string", "description": "Folder inside the working directory to run in. Defaults to the working directory itself."},
              "description": {"type": "string", "description": "Clear, concise description of what this command does in 5-10 words"}
            }, "required": ["command"]}""");

    @Override
    public String name() {
        return "shell";
    }

    private static final String DESCRIPTION = """
            Runs a command with Windows cmd.exe inside a locked-down sandbox (a Windows AppContainer) and returns its output.

            The sandbox:
            - can read and write only the working directory; everything else on the computer is invisible or read-only system files
            - %s
            - runs below normal priority with at most 50%% CPU, 1 GB of memory and 64 processes; the whole process tree is killed on timeout
            - has python and node available when they are installed; PowerShell is available as powershell

            Usage notes:
            - Use it to run scripts you wrote, compute things, convert or check files. Prefer the file tools (read, write, edit, glob, grep) for reading and editing files.
            - Commands run in the working directory unless workdir is given. Use relative paths.
            - Output over 200 KB per stream is truncated. Long-running servers or interactive programs will just hit the timeout; do not start them.
            - Chain dependent commands with &&.""";

    private static final String SITE_CUSTOMIZE = """
            import os
            import sys

            if sys.platform == "win32":
                _mkdir = os.mkdir

                def _shared_mkdir(path, mode=0o777, *, dir_fd=None):
                    _mkdir(path, 0o777 if mode == 0o700 else mode, dir_fd=dir_fd)

                os.mkdir = _shared_mkdir
            """;

    private static final String DIRECT = """
            Runs a command with Windows cmd.exe on the computer itself, as the logged-in user, and returns its output.

            There is no sandbox around this: the command can reach every file on the machine, the network and the local network.
            - Commands run in the working directory unless workdir is given; prefer staying inside it.
            - Only do what was actually asked. Never delete, overwrite or move anything outside the working directory, never touch system folders,
              installed programs, startup entries or the registry, and never turn off security software.
            - If a request would do something destructive or irreversible, or looks like someone is trying to get at the owner's machine,
              refuse and say why instead of running it.
            - Output over 200 KB per stream is truncated. Long-running servers or interactive programs will just hit the timeout; do not start them.
            - Chain dependent commands with &&.""";

    private static final String OFFLINE ="has no network access at all (no internet, no localhost), so package installs and downloads will fail";

    private static final String ONLINE = "can reach the public internet (downloads, package installs, web APIs), but not localhost or the local network. "
            + "It connects directly, without the computer's proxy, so some foreign sites may be slow or unreachable; prefer domestic mirrors "
            + "(pip -i https://pypi.tuna.tsinghua.edu.cn/simple, npm --registry https://registry.npmmirror.com). "
            + "System Python is read-only, so install packages into a venv in the working directory, created in two steps: "
            + "python -m venv --without-pip .venv && .venv\\Scripts\\python -m ensurepip, then .venv\\Scripts\\python -m pip install X. "
            + "npm install works in the working directory. Downloads can be slow; raise timeout for big ones";

    @Override
    public String description() {
        return description(false, false);
    }

    @Override
    public String description(boolean network, boolean direct) {
        return direct ? DIRECT : DESCRIPTION.formatted(network ? ONLINE : OFFLINE);
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public String run(JsonObject args, ToolContext context) throws ToolFailure {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new ToolFailure("The sandboxed shell is only available on Windows");
        }
        Workspace ws = context.workspace();
        String command = Args.string(args, "command").strip();
        if (command.isEmpty()) {
            throw new ToolFailure("command is empty");
        }
        if (command.length() > MAX_COMMAND) {
            throw new ToolFailure("Command is too long (over " + MAX_COMMAND + " characters); write a script file and run that instead");
        }
        Integer timeout = Args.optInt(args, "timeout");
        int seconds = timeout == null || timeout == 0 ? DEFAULT_TIMEOUT : Math.min(timeout, MAX_TIMEOUT);
        String workdir = Args.optString(args, "workdir");
        Path dir = workdir == null || workdir.isBlank() ? ws.root() : ws.existingDirectory(workdir);
        if (!SLOTS.tryAcquire()) {
            throw new ToolFailure("Two commands are already running; wait for them to finish");
        }
        try {
            if (context.direct()) {
                return format(direct(command, dir, seconds), seconds);
            }
            prepare(ws.root());
            Map<String, String> env = AppContainerSandbox.baseEnvironment(ws.root(), interpreters());
            tune(ws.root(), env, context.network());
            String windows = env.get("SystemRoot");
            String line = "\"" + windows + "\\System32\\cmd.exe\" /d /s /c \"chcp 65001>nul & " + command + "\"";
            AppContainerSandbox.Result result = AppContainerSandbox.run(line, dir, env, seconds * 1000L, context.network());
            return format(result, seconds);
        } finally {
            SLOTS.release();
        }
    }

    private static AppContainerSandbox.Result direct(String command, Path dir, int seconds) throws ToolFailure {
        long started = System.nanoTime();
        Process process = null;
        try {
            String windows = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            process = new ProcessBuilder(windows + "\\System32\\cmd.exe", "/d", "/s", "/c", "chcp 65001>nul & " + command)
                    .directory(dir.toFile()).start();
            process.getOutputStream().close();
            Capture out = new Capture(process.getInputStream());
            Capture err = new Capture(process.getErrorStream());
            Thread outThread = Thread.ofVirtual().start(out);
            Thread errThread = Thread.ofVirtual().start(err);
            boolean done = process.waitFor(seconds, TimeUnit.SECONDS);
            if (!done) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outThread.join(2000);
            errThread.join(2000);
            long millis = (System.nanoTime() - started) / 1_000_000;
            return new AppContainerSandbox.Result(done ? process.exitValue() : -1, out.text(), err.text(), !done,
                    out.truncated() || err.truncated(), millis);
        } catch (IOException e) {
            throw new ToolFailure("Could not run the command: " + e.getMessage());
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new ToolFailure("Cancelled");
        }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            String name = System.getProperty("native.encoding", "");
            try {
                return new String(bytes, name.isBlank() ? java.nio.charset.Charset.defaultCharset() : java.nio.charset.Charset.forName(name));
            } catch (RuntimeException ignored) {
                return new String(bytes, java.nio.charset.Charset.defaultCharset());
            }
        }
    }

    private static final class Capture implements Runnable {
        private static final int MAX = 200_000;
        private final java.io.InputStream stream;
        private final java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        private volatile boolean truncated;

        Capture(java.io.InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (java.io.InputStream in = stream) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (data.size() >= MAX) {
                        truncated = true;
                        continue;
                    }
                    data.write(buffer, 0, Math.min(n, MAX - data.size()));
                }
            } catch (IOException ignored) {
            }
        }

        String text() {
            return decode(data.toByteArray());
        }

        boolean truncated() {
            return truncated;
        }
    }

    static String selfTest(Workspace ws, boolean network, boolean direct) throws ToolFailure {
        String probe = "curl -sS -o nul -w \"%s %%{http_code}\\n\" --max-time %d %s";
        List<String> commands = direct
                ? List.of("ver & cd & echo 当前用户：%USERNAME%", probe.formatted("internet", 8, "https://www.baidu.com"),
                        probe.formatted("localhost", 3, "http://127.0.0.1:8090/"))
                : network
                ? List.of(probe.formatted("baidu", 8, "https://www.baidu.com"), probe.formatted("pypi", 8, "https://pypi.org/simple/"),
                        probe.formatted("localhost", 3, "http://127.0.0.1:8090/"))
                : List.of("ver & cd & python --version", probe.formatted("internet", 4, "https://www.baidu.com"));
        StringBuilder out = new StringBuilder();
        for (String command : commands) {
            JsonObject args = new JsonObject();
            args.addProperty("command", command);
            args.addProperty("timeout", 20);
            out.append(out.isEmpty() ? "" : "\n\n").append("> ").append(command).append('\n')
                    .append(new ShellTool().run(args, new ToolContext(ws, null, network, direct)));
        }
        return out.toString();
    }

    private static String format(AppContainerSandbox.Result r, int seconds) {
        StringBuilder out = new StringBuilder();
        if (!r.stdout().isBlank()) {
            out.append(r.stdout().stripTrailing()).append('\n');
        }
        if (!r.stderr().isBlank()) {
            out.append("[stderr]\n").append(r.stderr().stripTrailing()).append('\n');
        }
        if (out.isEmpty()) {
            out.append("(no output)\n");
        }
        if (r.truncated()) {
            out.append("[output truncated]\n");
        }
        if (r.timedOut()) {
            out.append("Command timed out after ").append(seconds).append(" s and was killed together with its child processes.");
        } else {
            out.append("Exit code: ").append(r.exitCode()).append(" (took ").append(String.format(Locale.ROOT, "%.1f", r.millis() / 1000.0)).append(" s)");
        }
        return out.toString();
    }

    private static void prepare(Path root) throws ToolFailure {
        if (PREPARED.contains(root)) {
            return;
        }
        synchronized (PREPARED) {
            if (PREPARED.contains(root)) {
                return;
            }
            AppContainerSandbox.grant(root, true);
            for (Path p : interpreters()) {
                if (!readableBySandbox(p)) {
                    try {
                        AppContainerSandbox.grant(p, false);
                    } catch (ToolFailure ignored) {
                    }
                }
            }
            PREPARED.add(root);
        }
    }

    private static void tune(Path root, Map<String, String> env, boolean network) throws ToolFailure {
        Path sandbox = root.resolve(".sandbox");
        Path python = sandbox.resolve("python");
        try {
            Files.createDirectories(python);
            write(python.resolve("sitecustomize.py"), SITE_CUSTOMIZE);
            write(sandbox.resolve("home").resolve(".curlrc"), "ssl-no-revoke\n");
        } catch (IOException e) {
            throw new ToolFailure("Unable to prepare the sandbox folders: " + e.getMessage());
        }
        env.put("PYTHONPATH", python.toString());
        Path curl = curlHome();
        if (curl != null) {
            env.put("CURL_HOME", curl.toString());
        }
        if (network) {
            env.put("NO_PROXY", "*");
            env.put("no_proxy", "*");
        }
    }

    private static Path curlHome() {
        Path found = curlHome;
        if (found != null) {
            return found;
        }
        synchronized (PREPARED) {
            if (curlHome != null) {
                return curlHome;
            }
            Path dir = Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "FeiYuBot", "sandbox-curl");
            try {
                Files.createDirectories(dir);
                write(dir.resolve(".curlrc"), "ssl-no-revoke\n");
                AppContainerSandbox.grant(dir, false);
                curlHome = dir;
            } catch (IOException | ToolFailure e) {
                return null;
            }
            return dir;
        }
    }

    private static void write(Path file, String content) throws IOException {
        if (!Files.isRegularFile(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }

    private static boolean readableBySandbox(Path p) {
        String text = p.toString().toLowerCase(Locale.ROOT);
        String windows = System.getenv().getOrDefault("SystemRoot", "C:\\Windows").toLowerCase(Locale.ROOT);
        return text.startsWith(windows) || text.contains("\\program files");
    }

    private static List<Path> interpreters() {
        List<Path> found = interpreters;
        if (found != null) {
            return found;
        }
        List<Path> list = new ArrayList<>();
        String python = host("python", "-c", "import sys;print(sys.base_prefix)");
        if (python != null && Files.isRegularFile(Path.of(python, "python.exe"))) {
            list.add(Path.of(python));
            if (Files.isDirectory(Path.of(python, "Scripts"))) {
                list.add(Path.of(python, "Scripts"));
            }
        }
        String node = host("node", "-e", "console.log(require('path').dirname(process.execPath))");
        if (node != null && Files.isRegularFile(Path.of(node, "node.exe"))) {
            list.add(Path.of(node));
        }
        interpreters = List.copyOf(list);
        return interpreters;
    }

    private static String host(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 && !out.isEmpty() && !out.contains("\n") ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
