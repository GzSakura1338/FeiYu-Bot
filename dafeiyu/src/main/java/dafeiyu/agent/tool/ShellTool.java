package dafeiyu.agent.tool;

import com.google.gson.JsonObject;
import dafeiyu.sandbox.Platform;
import dafeiyu.sandbox.Sandbox;
import dafeiyu.sandbox.Sandboxes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class ShellTool extends TextTool {

    record Runtime(Path bin, Path root) {}

    private static final int DEFAULT_TIMEOUT = 60;
    private static final int MAX_TIMEOUT = 600;
    private static final int MAX_COMMAND = 8_000;
    private static final Semaphore SLOTS = new Semaphore(2);
    private static final Object LOCK = new Object();
    private static volatile List<Runtime> interpreters;
    private static volatile Path curlHome;

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "command": {"type": "string", "description": "The command to run with the system shell (cmd.exe on Windows, bash or sh on Linux and macOS)"},
              "timeout": {"type": "number", "description": "Optional timeout in seconds (default 60, maximum 600)"},
              "workdir": {"type": "string", "description": "Folder inside the working directory to run in. Defaults to the working directory itself."},
              "description": {"type": "string", "description": "Clear, concise description of what this command does in 5-10 words"}
            }, "required": ["command"]}""");

    @Override
    public String name() {
        return "shell";
    }

    private static final String DESCRIPTION = """
            Runs a command with %s inside a locked-down sandbox (%s) and returns its output.

            The sandbox:
            - can read and write only the working directory; everything else on the computer is invisible or read-only system files
            - %s
            - %s
            - has %s available when they are installed%s

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
            Runs a command with %s on the computer itself, as the logged-in user, and returns its output.

            There is no sandbox around this: the command can reach every file on the machine, the network and the local network.
            - Commands run in the working directory unless workdir is given; prefer staying inside it.
            - Only do what was actually asked. Never delete, overwrite or move anything outside the working directory, never touch system folders,
              installed programs, startup entries or system settings, and never turn off security software.
            - If a request would do something destructive or irreversible, or looks like someone is trying to get at the owner's machine,
              refuse and say why instead of running it.
            - Output over 200 KB per stream is truncated. Long-running servers or interactive programs will just hit the timeout; do not start them.
            - Chain dependent commands with &&.""";

    private static final String OFFLINE = "has no network access at all (no internet, no localhost), so package installs and downloads will fail";

    private static final String MIRRORS = "It connects directly, without the computer's proxy, so some foreign sites may be slow or unreachable; prefer domestic mirrors "
            + "(pip -i https://pypi.tuna.tsinghua.edu.cn/simple, npm --registry https://registry.npmmirror.com). ";

    private static final String ONLINE_WINDOWS = "can reach the public internet (downloads, package installs, web APIs), but not localhost or the local network. "
            + MIRRORS
            + "System Python is read-only, so install packages into a venv in the working directory, created in two steps: "
            + "python -m venv --without-pip .venv && .venv\\Scripts\\python -m ensurepip, then .venv\\Scripts\\python -m pip install X. "
            + "npm install works in the working directory. Downloads can be slow; raise timeout for big ones";

    private static final String ONLINE_UNIX = "can reach the public internet (downloads, package installs, web APIs), but not localhost. On this system it can "
            + "still reach devices on the local network; never use it against them. "
            + MIRRORS
            + "System Python is read-only, so install packages into a venv in the working directory: python3 -m venv .venv, "
            + "then .venv/bin/python -m pip install X. npm install works in the working directory. Downloads can be slow; raise timeout for big ones";

    private static final String LIMITS = "runs below normal priority with at most 50% CPU, 1 GB of memory and 64 processes; the whole process tree is killed on timeout";
    private static final String PRIORITY_ONLY = "runs at lower priority; the whole process tree is killed on timeout";

    @Override
    public String description(ToolContext context) {
        Sandbox sandbox = Sandboxes.current();
        if (context.direct()) {
            return DIRECT.formatted(sandbox.shellName());
        }
        boolean windows = Platform.windows();
        String network = !online(sandbox, context) ? OFFLINE : windows ? ONLINE_WINDOWS : ONLINE_UNIX;
        return DESCRIPTION.formatted(sandbox.shellName(), sandbox.label(), network, sandbox.limited() ? LIMITS : PRIORITY_ONLY,
                windows ? "python and node" : "python3 and node", windows ? "; PowerShell is available as powershell" : "");
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    protected String text(Args args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        String command = args.string("command").strip();
        if (command.isEmpty()) {
            throw new ToolFailure("command is empty");
        }
        if (command.length() > MAX_COMMAND) {
            throw new ToolFailure("Command is too long (over " + MAX_COMMAND + " characters); write a script file and run that instead");
        }
        Integer timeout = args.optInt("timeout");
        int seconds = timeout == null || timeout == 0 ? DEFAULT_TIMEOUT : Math.min(timeout, MAX_TIMEOUT);
        String workdir = args.optString("workdir");
        Path dir = workdir == null || workdir.isBlank() ? ws.root() : ws.existingDirectory(workdir);
        Sandbox sandbox = Sandboxes.current();
        if (!context.direct() && sandbox.missing() != null) {
            throw new ToolFailure(sandbox.missing());
        }
        if (!SLOTS.tryAcquire()) {
            throw new ToolFailure("Two commands are already running; wait for them to finish");
        }
        try {
            if (context.direct()) {
                return format(sandbox.run(Sandbox.Spec.shell(command, dir, new HashMap<>(System.getenv())), seconds * 1000L), seconds);
            }
            boolean online = online(sandbox, context);
            List<Runtime> runtimes = interpreters();
            Map<String, String> env = sandbox.environment(ws.root(), runtimes.stream().map(Runtime::bin).distinct().toList());
            tune(ws.root(), env, online);
            List<Path> shared = new ArrayList<>(runtimes.stream().map(Runtime::root).distinct().toList());
            Path curl = Platform.windows() ? curlHome() : null;
            if (curl != null) {
                shared.add(curl);
            }
            Sandbox.Spec spec = Sandbox.Spec.shell(command, dir, env)
                    .isolated(true, "", online)
                    .reading(List.of(), shared)
                    .writing(List.of(ws.root()))
                    .limits(1024L << 20, 50, 64);
            String out = format(sandbox.run(spec, seconds * 1000L), seconds);
            return online || !context.network() ? out : out + "\n[ran without network: " + sandbox.networkMissing() + "]";
        } finally {
            SLOTS.release();
        }
    }

    private static boolean online(Sandbox sandbox, ToolContext context) {
        return context.network() && sandbox.networkMissing() == null;
    }

    static String selfTest(Workspace ws, boolean network, boolean direct) throws ToolFailure {
        boolean windows = Platform.windows();
        String probe = "curl -sS -o " + (windows ? "nul" : "/dev/null") + " -w \"%s %%{http_code}\\n\" --max-time %d %s";
        String about = windows ? "ver & cd" : "uname -sr; pwd";
        List<String> commands = direct
                ? List.of(about + (windows ? " & echo 当前用户：%USERNAME%" : "; echo 当前用户：$(id -un)"),
                        probe.formatted("internet", 8, "https://www.baidu.com"), probe.formatted("localhost", 3, "http://127.0.0.1:8090/"))
                : network
                ? List.of(probe.formatted("baidu", 8, "https://www.baidu.com"), probe.formatted("pypi", 8, "https://pypi.org/simple/"),
                        probe.formatted("localhost", 3, "http://127.0.0.1:8090/"))
                : List.of(about + (windows ? " & python --version" : "; python3 --version"), probe.formatted("internet", 4, "https://www.baidu.com"));
        StringBuilder out = new StringBuilder(direct ? "沙盒外执行（" + Sandboxes.current().shellName() + "）" : "沙盒：" + Sandboxes.current().label());
        for (String command : commands) {
            JsonObject args = new JsonObject();
            args.addProperty("command", command);
            args.addProperty("timeout", 20);
            out.append("\n\n").append("> ").append(command).append('\n')
                    .append(new ShellTool().text(new Args(args), new ToolContext("", "", ws, null, network, direct)));
        }
        return out.toString();
    }

    private static String format(Sandbox.Result r, int seconds) {
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

    private static void tune(Path root, Map<String, String> env, boolean network) throws ToolFailure {
        if (Platform.windows()) {
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
        synchronized (LOCK) {
            if (curlHome != null) {
                return curlHome;
            }
            Path dir = Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "FeiYuBot", "sandbox-curl");
            try {
                Files.createDirectories(dir);
                write(dir.resolve(".curlrc"), "ssl-no-revoke\n");
                curlHome = dir;
            } catch (IOException e) {
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

    static List<Runtime> interpreters() {
        List<Runtime> found = interpreters;
        if (found != null) {
            return found;
        }
        boolean windows = Platform.windows();
        List<Runtime> list = new ArrayList<>();
        for (String python : windows ? List.of("python") : List.of("python3", "python")) {
            String prefix = host(python, "-c", "import sys;print(sys.base_prefix)");
            if (prefix == null) {
                continue;
            }
            Path root = Path.of(prefix);
            if (windows && Files.isRegularFile(root.resolve("python.exe"))) {
                list.add(new Runtime(root, root));
                if (Files.isDirectory(root.resolve("Scripts"))) {
                    list.add(new Runtime(root.resolve("Scripts"), root));
                }
                break;
            }
            if (!windows && Files.isDirectory(root.resolve("bin"))) {
                list.add(new Runtime(root.resolve("bin"), root));
                break;
            }
        }
        String node = host("node", "-e", "console.log(require('path').dirname(process.execPath))");
        if (node != null) {
            Path bin = Path.of(node);
            if (windows && Files.isRegularFile(bin.resolve("node.exe"))) {
                list.add(new Runtime(bin, bin));
            } else if (!windows && Files.isRegularFile(bin.resolve("node"))) {
                Path parent = bin.getParent();
                list.add(new Runtime(bin, bin.getFileName() != null && bin.getFileName().toString().equals("bin") && parent != null ? parent : bin));
            }
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
