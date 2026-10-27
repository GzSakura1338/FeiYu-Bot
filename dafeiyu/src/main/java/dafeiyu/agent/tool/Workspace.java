package dafeiyu.agent.tool;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Workspace {

    private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³]|conin\\$|conout\\$)(\\..*)?");
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Path root;

    private Workspace(Path root) {
        this.root = root;
    }

    public static Workspace open(String dir, List<Path> protectedDirs) {
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException("工作目录不能为空");
        }
        Path path;
        try {
            path = Path.of(dir.strip());
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("路径格式不对：" + e.getReason());
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("请填写完整路径，例如 D:\\bot-files");
        }
        Path real;
        try {
            real = path.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("目录不存在：" + path);
        }
        if (!Files.isDirectory(real)) {
            throw new IllegalArgumentException("这不是一个目录：" + real);
        }
        if (real.getParent() == null) {
            throw new IllegalArgumentException("不能把整个磁盘根目录当作工作目录");
        }
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (real.equals(home)) {
            throw new IllegalArgumentException("不能把整个用户目录当作工作目录，请新建一个专用目录");
        }
        for (Path own : protectedDirs) {
            if (realOrSelf(own).startsWith(real)) {
                throw new IllegalArgumentException("工作目录不能包含大肥鱼自己的目录（里面有 API Key 和数据库）");
            }
        }
        return new Workspace(real);
    }

    public Path root() {
        return root;
    }

    public String name() {
        return root.getFileName().toString();
    }

    public String relative(Path path) {
        String rel = root.relativize(path).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }

    public boolean contains(Path real) {
        return real.startsWith(root);
    }

    public Path resolve(String input) throws ToolFailure {
        String text = input == null || input.isBlank() ? "." : input.strip();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 0x20) {
                throw new ToolFailure("Invalid path: " + input);
            }
        }
        Path candidate;
        try {
            Path given = Path.of(text);
            if (!given.isAbsolute() && given.getRoot() != null) {
                throw new ToolFailure("Invalid path: " + input + ". Use a path relative to the working directory.");
            }
            candidate = given.isAbsolute() ? given : root.resolve(given);
        } catch (InvalidPathException e) {
            throw new ToolFailure("Invalid path: " + input + " (" + e.getReason() + ")");
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw outside(input);
        }
        for (Path part : root.relativize(normalized)) {
            String name = part.toString();
            if (name.isEmpty()) {
                continue;
            }
            if (RESERVED.matcher(name).matches() || name.endsWith(".") || name.endsWith(" ") || name.contains(":")) {
                throw new ToolFailure("Invalid file name: " + name);
            }
        }
        Path existing = normalized;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null || !existing.startsWith(root)) {
                throw outside(input);
            }
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (NoSuchFileException e) {
            throw new ToolFailure("Broken link: " + relative(existing));
        } catch (IOException e) {
            throw new ToolFailure("Cannot access " + relative(existing) + ": " + e.getMessage());
        }
        if (!contains(real)) {
            throw outside(input);
        }
        return existing.equals(normalized) ? real : real.resolve(existing.relativize(normalized));
    }

    public Path existingFile(String input) throws ToolFailure {
        Path path = resolve(input);
        if (!Files.exists(path)) {
            throw new ToolFailure("File not found: " + input);
        }
        if (Files.isDirectory(path)) {
            throw new ToolFailure("Path is a directory, not a file: " + input);
        }
        if (!Files.isRegularFile(path)) {
            throw new ToolFailure("Not a regular file: " + input);
        }
        return path;
    }

    public Path existingDirectory(String input) throws ToolFailure {
        Path path = resolve(input);
        if (!Files.exists(path)) {
            throw new ToolFailure("Directory not found: " + (input == null || input.isBlank() ? "." : input));
        }
        if (!Files.isDirectory(path)) {
            throw new ToolFailure("Not a directory: " + input);
        }
        return path;
    }

    public void write(Path target, byte[] data) throws ToolFailure {
        if (Files.isDirectory(target)) {
            throw new ToolFailure("Path is a directory, not a file: " + relative(target));
        }
        Path dir = target.getParent();
        try {
            Files.createDirectories(dir);
            Path realDir = dir.toRealPath();
            if (!contains(realDir)) {
                throw outside(relative(target));
            }
            Path tmp = Files.createTempFile(realDir, ".dafeiyu-", ".tmp");
            try {
                Files.write(tmp, data);
                Path dest = realDir.resolve(target.getFileName());
                try {
                    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                makeWorldReadable(dest);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new ToolFailure("Unable to write " + relative(target) + ": " + e.getMessage());
        }
    }

    public void place(Path target, Path source) throws ToolFailure {
        if (Files.isDirectory(target)) {
            throw new ToolFailure("Path is a directory, not a file: " + relative(target));
        }
        Path dir = target.getParent();
        try {
            Files.createDirectories(dir);
            Path realDir = dir.toRealPath();
            if (!contains(realDir)) {
                throw outside(relative(target));
            }
            Path dest = realDir.resolve(target.getFileName());
            try {
                Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            makeWorldReadable(dest);
        } catch (IOException e) {
            throw new ToolFailure("Unable to write " + relative(target) + ": " + e.getMessage());
        }
    }

    public static void makeWorldReadable(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    public record Entry(String name, String path, boolean directory, long size, long modified, int items) {}

    public static final String TRASH = ".trash";

    private static int count(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return (int) Math.min(children.limit(10_000).count(), Integer.MAX_VALUE);
        } catch (IOException e) {
            return 0;
        }
    }

    public List<Entry> entries(Path dir) throws ToolFailure {
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                if (!contains(realOrSelf(child))) {
                    continue;
                }
                BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
                out.add(new Entry(child.getFileName().toString(), relative(child), attrs.isDirectory(),
                        attrs.isDirectory() ? 0 : attrs.size(), attrs.lastModifiedTime().toMillis(), attrs.isDirectory() ? count(child) : 0));
            }
        } catch (IOException e) {
            throw new ToolFailure("Unable to list " + relative(dir) + ": " + e.getMessage());
        }
        out.sort(Comparator.comparing((Entry e) -> !e.directory()).thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    public void move(Path from, Path to, boolean overwrite) throws ToolFailure {
        if (from.equals(root)) {
            throw new ToolFailure("The working directory itself cannot be moved");
        }
        if (to.startsWith(from)) {
            throw new ToolFailure("Cannot move " + relative(from) + " into itself");
        }
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
            throw new ToolFailure("Destination already exists: " + relative(to) + ". Choose another name or set overwrite to true.");
        }
        if (overwrite && Files.isDirectory(to)) {
            throw new ToolFailure("Destination is an existing directory: " + relative(to));
        }
        try {
            Files.createDirectories(to.getParent());
            Path realDir = to.getParent().toRealPath();
            if (!contains(realDir)) {
                throw outside(relative(to));
            }
            Path dest = realDir.resolve(to.getFileName());
            if (overwrite) {
                Files.move(from, dest, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(from, dest);
            }
        } catch (IOException e) {
            throw new ToolFailure("Unable to move " + relative(from) + ": " + e.getMessage());
        }
    }

    public String trash(Path target) throws ToolFailure {
        Path bin = root.resolve(TRASH);
        if (target.equals(root) || target.equals(bin)) {
            throw new ToolFailure("This folder cannot be deleted");
        }
        if (target.startsWith(bin)) {
            throw new ToolFailure(relative(target) + " is already in the trash");
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dest = bin.resolve(stamp + "-" + target.getFileName());
        move(target, dest, false);
        return relative(dest);
    }

    public void purge(Path target) throws ToolFailure {
        Path bin = root.resolve(TRASH);
        if (!target.startsWith(bin) || target.equals(bin)) {
            throw new ToolFailure("Only items in the trash can be deleted for good");
        }
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!contains(realOrSelf(p))) {
                    throw outside(relative(p));
                }
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new ToolFailure("Unable to delete " + relative(target) + ": " + e.getMessage());
        }
    }

    public static ReentrantLock lock(Path path) {
        return LOCKS.computeIfAbsent(path.toString().toLowerCase(Locale.ROOT), k -> new ReentrantLock());
    }

    private static ToolFailure outside(String input) {
        return new ToolFailure("Access denied: " + input + " is outside the working directory. Only files inside the working directory can be used.");
    }

    private static Path realOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
