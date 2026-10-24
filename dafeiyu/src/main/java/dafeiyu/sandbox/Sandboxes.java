package dafeiyu.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Sandboxes {

    public interface Ledger {

        boolean granted(String key);

        void mark(String key);
    }

    private static volatile Sandbox current;

    private Sandboxes() {}

    public static Sandbox current() {
        Sandbox known = current;
        if (known != null) {
            return known;
        }
        synchronized (Sandboxes.class) {
            if (current == null) {
                current = switch (Platform.current()) {
                    case WINDOWS -> new WindowsSandbox();
                    case LINUX -> new Bubblewrap();
                    case MAC -> new Seatbelt();
                    case OTHER -> new UnixSandbox.Plain();
                };
            }
            return current;
        }
    }

    public static void ledger(Ledger ledger) {
        if (current() instanceof WindowsSandbox windows) {
            windows.ledger(ledger);
        }
    }

    public static List<Path> runtimeRoots(Path exe) {
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(root(exe.toAbsolutePath().normalize()));
        if (!Platform.windows()) {
            try {
                roots.add(root(exe.toRealPath()));
            } catch (IOException ignored) {
            }
        }
        roots.remove(null);
        return List.copyOf(roots);
    }

    private static Path root(Path exe) {
        Path dir = exe.getParent();
        if (dir == null || Platform.windows()) {
            return dir;
        }
        Path name = dir.getFileName();
        return name != null && name.toString().equals("bin") && dir.getParent() != null ? dir.getParent() : dir;
    }
}
