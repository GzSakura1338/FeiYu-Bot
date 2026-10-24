package dafeiyu.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

final class Seatbelt extends UnixSandbox {

    static final String EXEC = "/usr/bin/sandbox-exec";

    private static final List<String> SYSTEM_READ = List.of("/usr", "/bin", "/sbin", "/System", "/Library", "/opt/homebrew", "/opt/local",
            "/Applications/Xcode.app", "/private/etc", "/private/var/db/timezone", "/private/var/select", "/dev");
    private static final List<String> BASE_SERVICES = List.of("com.apple.system.opendirectoryd.libinfo",
            "com.apple.system.opendirectoryd.membership", "com.apple.system.logger", "com.apple.system.notification_center", "com.apple.logd",
            "com.apple.PowerManagement.control", "com.apple.bsd.dirhelper", "com.apple.cfprefsd.daemon", "com.apple.cfprefsd.agent");
    private static final List<String> NETWORK_SERVICES = List.of("com.apple.SecurityServer", "com.apple.networkd", "com.apple.ocspd",
            "com.apple.trustd", "com.apple.trustd.agent", "com.apple.SystemConfiguration.DNSConfiguration",
            "com.apple.SystemConfiguration.configd", "com.apple.dnssd.service");

    @Override
    public String kind() {
        return "seatbelt";
    }

    @Override
    public String label() {
        return "macOS sandbox-exec（Seatbelt）";
    }

    @Override
    public String missing() {
        return Files.isExecutable(Path.of(EXEC)) ? null : "这台 Mac 上找不到 " + EXEC + "，命令沙盒用不了";
    }

    @Override
    protected List<String> systemPath() {
        return List.of("/opt/homebrew/bin", "/opt/homebrew/sbin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin");
    }

    @Override
    protected String locale() {
        return "en_US.UTF-8";
    }

    @Override
    protected List<String> wrap(Spec spec, List<String> argv) {
        List<String> args = new ArrayList<>(List.of(EXEC, "-p", profile(spec, Seatbelt::real)));
        args.addAll(argv);
        return args;
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    static String profile(Spec spec, Function<Path, Path> real) {
        StringBuilder sb = new StringBuilder();
        sb.append("(version 1)\n(deny default)\n");
        sb.append("(allow process-exec)\n(allow process-fork)\n(allow signal (target same-sandbox))\n(allow process-info* (target same-sandbox))\n");
        sb.append("(allow sysctl-read)\n(allow user-preference-read)\n(allow ipc-posix-sem)\n(allow ipc-posix-shm)\n(allow file-read-metadata)\n");
        sb.append("(allow mach-lookup").append(names(BASE_SERVICES)).append(")\n");
        sb.append("(allow file-read* (literal \"/\")").append(subpaths(SYSTEM_READ)).append(" (literal \"/private/var/db/xcode_select_link\"))\n");
        sb.append("(allow file-write-data (literal \"/dev/null\") (literal \"/dev/zero\") (literal \"/dev/tty\") (literal \"/dev/dtracehelper\"))\n");
        Set<String> readable = new LinkedHashSet<>();
        for (Path p : spec.readable()) {
            readable.add(real.apply(p).toString());
        }
        for (Path p : spec.shared()) {
            readable.add(real.apply(p).toString());
        }
        if (!readable.isEmpty()) {
            sb.append("(allow file-read*").append(subpaths(List.copyOf(readable))).append(")\n");
        }
        List<String> writable = new ArrayList<>();
        for (Path p : spec.writable()) {
            writable.add(real.apply(p).toString());
        }
        if (!writable.isEmpty()) {
            sb.append("(allow file-read* file-write*").append(subpaths(writable)).append(")\n");
        }
        if (spec.network()) {
            sb.append("(allow network-outbound (require-all (remote ip \"*:*\") (require-not (remote ip \"localhost:*\"))))\n");
            sb.append("(allow network-outbound (remote unix-socket (path-literal \"/private/var/run/mDNSResponder\")))\n");
            sb.append("(allow system-socket)\n(allow mach-lookup").append(names(NETWORK_SERVICES)).append(")\n");
        }
        return sb.toString();
    }

    private static String subpaths(List<String> paths) {
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            sb.append(" (subpath ").append(string(p)).append(')');
        }
        return sb.toString();
    }

    private static String names(List<String> services) {
        StringBuilder sb = new StringBuilder();
        for (String s : services) {
            sb.append(" (global-name ").append(string(s)).append(')');
        }
        return sb.toString();
    }

    static String string(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
