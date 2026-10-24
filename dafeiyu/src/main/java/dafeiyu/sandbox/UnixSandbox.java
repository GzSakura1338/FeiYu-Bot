package dafeiyu.sandbox;

import dafeiyu.agent.tool.ToolFailure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

abstract class UnixSandbox implements Sandbox {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:=@%+,-]+");
    private static final Path NICE = Path.of("/usr/bin/nice");

    @Override
    public String shellName() {
        return Files.isExecutable(Path.of("/bin/bash")) ? "/bin/bash" : "/bin/sh";
    }

    @Override
    public boolean reachesLan() {
        return true;
    }

    protected List<String> systemPath() {
        return List.of("/usr/local/bin", "/usr/bin", "/bin", "/usr/local/sbin", "/usr/sbin", "/sbin");
    }

    protected String locale() {
        return "C.UTF-8";
    }

    protected abstract List<String> wrap(Spec spec, List<String> argv) throws ToolFailure;

    @Override
    public Map<String, String> environment(Path root, List<Path> extraPath) throws ToolFailure {
        Path sandbox = root.resolve(".sandbox");
        Path home = sandbox.resolve("home");
        Path temp = sandbox.resolve("tmp");
        try {
            Files.createDirectories(home);
            Files.createDirectories(temp);
        } catch (IOException e) {
            throw new ToolFailure("Unable to prepare the sandbox folders: " + e.getMessage());
        }
        List<String> path = new ArrayList<>();
        extraPath.forEach(p -> path.add(p.toString()));
        systemPath().stream().filter(p -> !path.contains(p)).forEach(path::add);
        String user = System.getProperty("user.name", "sandbox");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", String.join(":", path));
        env.put("HOME", home.toString());
        env.put("TMPDIR", temp.toString());
        env.put("TMP", temp.toString());
        env.put("TEMP", temp.toString());
        env.put("USER", user);
        env.put("LOGNAME", user);
        env.put("SHELL", shellName());
        env.put("LANG", locale());
        env.put("TERM", "dumb");
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("PYTHONNOUSERSITE", "1");
        env.put("SANDBOX", "1");
        return env;
    }

    @Override
    public String quote(List<String> args) {
        return String.join(" ", args.stream().map(UnixSandbox::quote).toList());
    }

    static String quote(String arg) {
        return !arg.isEmpty() && PLAIN.matcher(arg).matches() ? arg : "'" + arg.replace("'", "'\"'\"'") + "'";
    }

    @Override
    public Result run(Spec spec, long timeoutMs) throws ToolFailure {
        return Processes.wait(start(spec), timeoutMs);
    }

    @Override
    public Running spawn(Spec spec) throws ToolFailure {
        return new Processes.Running(start(spec));
    }

    List<String> argv(Spec spec) {
        return spec.shell() == null ? spec.command() : List.of(shellName(), "-c", spec.shell());
    }

    private Process start(Spec spec) throws ToolFailure {
        List<String> argv = argv(spec);
        if (spec.isolated()) {
            String why = missing();
            if (why == null && spec.network()) {
                why = networkMissing();
            }
            if (why != null) {
                throw new ToolFailure(why);
            }
            for (Path p : spec.writable()) {
                try {
                    Files.createDirectories(p);
                } catch (IOException ignored) {
                }
            }
            argv = wrap(spec, argv);
            if (spec.cpuPercent() < 100 && Files.isExecutable(NICE)) {
                List<String> niced = new ArrayList<>(List.of(NICE.toString(), "-n", "10"));
                niced.addAll(argv);
                argv = niced;
            }
        }
        return Processes.start(argv, spec.cwd(), spec.env());
    }

    static final class Plain extends UnixSandbox {

        @Override
        public String kind() {
            return "none";
        }

        @Override
        public String label() {
            return "无（这个系统没有可用的沙盒）";
        }

        @Override
        public String missing() {
            return "这个系统上没有能用的命令沙盒（只支持 Windows 应用容器、Linux bubblewrap 和 macOS sandbox-exec）。"
                    + "要跑命令只能在「工作目录 › 命令行执行」里给主人开「沙盒外执行」";
        }

        @Override
        protected List<String> wrap(Spec spec, List<String> argv) throws ToolFailure {
            throw new ToolFailure(missing());
        }
    }
}
