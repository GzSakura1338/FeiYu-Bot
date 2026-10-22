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

    @Override
    public String description() {
        return """
                Runs a command with Windows cmd.exe inside a locked-down sandbox (a Windows AppContainer) and returns its output.

                The sandbox:
                - can read and write only the working directory; everything else on the computer is invisible or read-only system files
                - has no network access at all (no internet, no localhost), so package installs and downloads will fail
                - runs below normal priority with at most 50% CPU, 1 GB of memory and 64 processes; the whole process tree is killed on timeout
                - has python and node available when they are installed; PowerShell is available as powershell

                Usage notes:
                - Use it to run scripts you wrote, compute things, convert or check files. Prefer the file tools (read, write, edit, glob, grep) for reading and editing files.
                - Commands run in the working directory unless workdir is given. Use relative paths.
                - Output over 200 KB per stream is truncated. Long-running servers or interactive programs will just hit the timeout; do not start them.
                - Chain dependent commands with &&.""";
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
        prepare(ws.root());
        if (!SLOTS.tryAcquire()) {
            throw new ToolFailure("Two sandboxed commands are already running; wait for them to finish");
        }
        try {
            Map<String, String> env = AppContainerSandbox.baseEnvironment(ws.root(), interpreters());
            String windows = env.get("SystemRoot");
            String line = "\"" + windows + "\\System32\\cmd.exe\" /d /s /c \"chcp 65001>nul & " + command + "\"";
            AppContainerSandbox.Result result = AppContainerSandbox.run(line, dir, env, seconds * 1000L);
            return format(result, seconds);
        } finally {
            SLOTS.release();
        }
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
