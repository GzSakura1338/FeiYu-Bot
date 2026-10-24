package dafeiyu.sandbox;

import dafeiyu.agent.tool.ToolFailure;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class Bubblewrap extends UnixSandbox {

    static final List<String> SYSTEM = List.of("/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32", "/etc", "/opt",
            "/nix/store", "/run/current-system");
    static final String RESOLVER = "169.254.1.1";
    static final List<String> PASTA = List.of("--config-net", "--quiet", "--no-map-gw", "-t", "none", "-u", "none", "-T", "none", "-U", "none",
            "--dns-forward", RESOLVER);

    private static final String INSTALL = "这台 Linux 没装 bubblewrap，命令沙盒用不了。装一下：sudo apt install bubblewrap"
            + "（Fedora：sudo dnf install bubblewrap；Arch：sudo pacman -S bubblewrap），装好重启 FeiYu Bot";
    private static final String NO_PASTA = "Linux 上沙盒联网要用 passt 里的 pasta 给沙盒单独一张网卡，这样沙盒才连不到本机的控制台和其他服务。"
            + "这台电脑没装：sudo apt install passt（Fedora：sudo dnf install passt；Arch：sudo pacman -S passt），装好重启 FeiYu Bot";

    private volatile boolean probed;
    private volatile String missing;
    private volatile String binary;
    private volatile boolean netProbed;
    private volatile String netMissing;
    private volatile String pasta;
    private volatile Path resolvFile;

    @Override
    public String kind() {
        return "bubblewrap";
    }

    @Override
    public String label() {
        return "bubblewrap 命名空间沙盒";
    }

    @Override
    public String missing() {
        if (!probed) {
            probe();
        }
        return missing;
    }

    @Override
    public String networkMissing() {
        if (missing() != null) {
            return missing;
        }
        if (!netProbed) {
            probeNetwork();
        }
        return netMissing;
    }

    private synchronized void probe() {
        if (probed) {
            return;
        }
        binary = locate("bwrap");
        if (binary == null) {
            missing = INSTALL;
        } else {
            String failed = check(List.of(binary, "--die-with-parent", "--unshare-all", "--ro-bind", "/", "/", "--dev", "/dev",
                    "--proc", "/proc", "true"));
            missing = failed == null ? null : "bubblewrap 启动不了（多半是系统不允许普通用户创建命名空间）：" + failed
                    + "。Ubuntu 24.04 起可以执行 sudo sysctl kernel.apparmor_restrict_unprivileged_userns=0，"
                    + "老版本 Debian 执行 sudo sysctl kernel.unprivileged_userns_clone=1";
        }
        probed = true;
    }

    private synchronized void probeNetwork() {
        if (netProbed) {
            return;
        }
        pasta = locate("pasta");
        if (pasta == null) {
            netMissing = NO_PASTA;
        } else {
            List<String> command = new ArrayList<>(List.of(pasta));
            command.addAll(PASTA);
            command.addAll(List.of("--", "true"));
            String failed = check(command);
            if (failed == null) {
                try {
                    Path file = Files.createTempFile("feiyu-resolv", ".conf");
                    Files.writeString(file, "nameserver " + RESOLVER + "\n", StandardCharsets.UTF_8);
                    file.toFile().deleteOnExit();
                    resolvFile = file;
                } catch (IOException e) {
                    failed = "写不了临时的 DNS 设置：" + e.getMessage();
                }
            }
            netMissing = failed == null ? null : "pasta 启动不了，沙盒联网用不了：" + failed;
        }
        netProbed = true;
    }

    private static String check(List<String> command) {
        try {
            Result r = Processes.run(command, Path.of("/"), System.getenv(), 15_000);
            return r.exitCode() == 0 ? null : r.stderr().isBlank() ? "退出码 " + r.exitCode() : r.stderr().strip();
        } catch (ToolFailure e) {
            return e.getMessage();
        }
    }

    private static String locate(String name) {
        List<String> dirs = new ArrayList<>(List.of(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)));
        dirs.addAll(List.of("/usr/bin", "/usr/local/bin", "/bin", "/usr/sbin", "/sbin"));
        for (String dir : dirs) {
            if (dir.isBlank()) {
                continue;
            }
            Path p = Path.of(dir, name);
            if (Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return null;
    }

    @Override
    protected List<String> wrap(Spec spec, List<String> argv) throws ToolFailure {
        Path resolv = null;
        if (spec.network()) {
            String why = networkMissing();
            if (why != null) {
                throw new ToolFailure(why);
            }
            try {
                resolv = Path.of("/etc/resolv.conf").toRealPath();
            } catch (IOException ignored) {
            }
        }
        return arguments(spec, argv, binary, pasta, Files::exists, resolv, resolvFile);
    }

    static List<String> arguments(Spec spec, List<String> argv, String bwrap, String pasta, Predicate<Path> exists, Path resolv,
                                  Path resolvFile) {
        List<String> args = new ArrayList<>();
        if (spec.network()) {
            args.add(pasta);
            args.addAll(PASTA);
            args.add("--");
        }
        args.addAll(List.of(bwrap, "--die-with-parent", "--new-session", "--unshare-all"));
        if (spec.network()) {
            args.add("--share-net");
        }
        for (String dir : SYSTEM) {
            if (exists.test(Path.of(dir))) {
                args.addAll(List.of("--ro-bind", dir, dir));
            }
        }
        args.addAll(List.of("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp"));
        if (spec.network() && resolv != null && resolvFile != null) {
            if (!system(resolv) && resolv.getParent() != null) {
                args.addAll(List.of("--dir", unix(resolv.getParent())));
            }
            args.addAll(List.of("--ro-bind", unix(resolvFile), unix(resolv)));
        }
        Set<Path> readable = new LinkedHashSet<>(spec.readable());
        readable.addAll(spec.shared());
        for (Path p : readable) {
            if (!system(p) && exists.test(p) && !spec.writable().contains(p)) {
                args.addAll(List.of("--ro-bind", unix(p), unix(p)));
            }
        }
        for (Path p : spec.writable()) {
            args.addAll(List.of("--bind", unix(p), unix(p)));
        }
        args.addAll(List.of("--chdir", unix(spec.cwd()), "--"));
        args.addAll(argv);
        return args;
    }

    private static String unix(Path p) {
        return File.separatorChar == '\\' ? p.toString().replace('\\', '/') : p.toString();
    }

    private static boolean system(Path p) {
        String text = unix(p);
        return SYSTEM.stream().anyMatch(s -> text.equals(s) || text.startsWith(s + "/"));
    }
}
