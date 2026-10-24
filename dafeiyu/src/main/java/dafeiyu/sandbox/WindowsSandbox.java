package dafeiyu.sandbox;

import dafeiyu.agent.tool.ToolFailure;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class WindowsSandbox implements Sandbox {

    private final Set<String> grants = ConcurrentHashMap.newKeySet();
    private volatile Sandboxes.Ledger ledger;

    void ledger(Sandboxes.Ledger ledger) {
        this.ledger = ledger;
    }

    @Override
    public String kind() {
        return "appcontainer";
    }

    @Override
    public String label() {
        return "Windows 应用容器（AppContainer）";
    }

    @Override
    public String missing() {
        return null;
    }

    @Override
    public String shellName() {
        return "cmd.exe";
    }

    @Override
    public boolean limited() {
        return true;
    }

    @Override
    public boolean reachesLan() {
        return false;
    }

    @Override
    public Map<String, String> environment(Path root, List<Path> extraPath) throws ToolFailure {
        return AppContainerSandbox.baseEnvironment(root, extraPath);
    }

    @Override
    public String quote(List<String> args) {
        return AppContainerSandbox.quote(args);
    }

    @Override
    public Result run(Spec spec, long timeoutMs) throws ToolFailure {
        if (!spec.isolated()) {
            return Processes.run(argv(spec), spec.cwd(), spec.env(), timeoutMs);
        }
        grant(spec.isolated(true, "", spec.network()));
        AppContainerSandbox.Result r = AppContainerSandbox.run(commandLine(spec), spec.cwd(), spec.env(), timeoutMs, spec.network());
        return new Result(r.exitCode(), r.stdout(), r.stderr(), r.timedOut(), r.truncated(), r.millis());
    }

    @Override
    public Running spawn(Spec spec) throws ToolFailure {
        grant(spec);
        String profile = spec.profile().isEmpty() ? AppContainerSandbox.CONTAINER : spec.profile();
        return AppContainerSandbox.spawn(new AppContainerSandbox.Spec(commandLine(spec), spec.cwd(), spec.env(),
                spec.isolated() ? profile : null, spec.network(), spec.memoryBytes(), spec.cpuPercent(), spec.maxProcesses()));
    }

    @Override
    public void warm(List<Path> shared) {
        for (Path p : shared) {
            try {
                if (!readableByDefault(p)) {
                    once(AppContainerSandbox.ALL_APP_PACKAGES, p, false, true);
                }
            } catch (ToolFailure ignored) {
            }
        }
    }

    private static List<String> argv(Spec spec) {
        if (spec.shell() == null) {
            return spec.command();
        }
        return List.of(cmd(), "/d", "/s", "/c", "chcp 65001>nul & " + spec.shell());
    }

    private static String commandLine(Spec spec) {
        return spec.shell() == null ? AppContainerSandbox.quote(spec.command())
                : "\"" + cmd() + "\" /d /s /c \"chcp 65001>nul & " + spec.shell() + "\"";
    }

    private static String cmd() {
        return System.getenv().getOrDefault("SystemRoot", "C:\\Windows") + "\\System32\\cmd.exe";
    }

    private void grant(Spec spec) throws ToolFailure {
        if (!spec.isolated()) {
            return;
        }
        String sid = spec.profile().isEmpty() || spec.profile().equals(AppContainerSandbox.CONTAINER)
                ? AppContainerSandbox.sid() : AppContainerSandbox.containerSid(spec.profile());
        for (Path p : spec.writable()) {
            once(sid, p, true, false);
        }
        for (Path p : spec.readable()) {
            AppContainerSandbox.grantTo(p, false, sid);
        }
        List<Path> common = new ArrayList<>(spec.shared());
        common.removeIf(WindowsSandbox::readableByDefault);
        for (Path p : common) {
            once(AppContainerSandbox.ALL_APP_PACKAGES, p, false, true);
        }
    }

    private void once(String sid, Path path, boolean write, boolean remembered) throws ToolFailure {
        String key = sid + "|" + path + "|" + (write ? "M" : "RX");
        if (grants.contains(key)) {
            return;
        }
        Sandboxes.Ledger known = remembered ? ledger : null;
        if (known == null || !known.granted(key)) {
            AppContainerSandbox.grantTo(path, write, sid);
            if (known != null) {
                known.mark(key);
            }
        }
        grants.add(key);
    }

    private static boolean readableByDefault(Path p) {
        String text = p.toString().toLowerCase(Locale.ROOT);
        String windows = System.getenv().getOrDefault("SystemRoot", "C:\\Windows").toLowerCase(Locale.ROOT);
        return text.startsWith(windows) || text.contains("\\program files");
    }
}
