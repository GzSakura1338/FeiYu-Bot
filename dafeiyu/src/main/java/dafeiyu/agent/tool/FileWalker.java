package dafeiyu.agent.tool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.regex.Pattern;

final class FileWalker {

    static final int DEFAULT_LIMIT = 100;
    static final long TIMEOUT_MS = 30_000;

    @FunctionalInterface
    interface Visitor {
        boolean visit(Path file) throws ToolFailure;
    }

    private FileWalker() {}

    static void walk(Workspace ws, Path start, boolean hidden, Visitor visitor) throws ToolFailure {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        boolean[] timedOut = {false};
        ToolFailure[] failure = {null};
        try {
            Files.walkFileTree(start, EnumSet.noneOf(java.nio.file.FileVisitOption.class), 64, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (System.currentTimeMillis() > deadline) {
                        timedOut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (dir.equals(start)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString();
                    if (name.equals(".git") || (!hidden && name.startsWith(".")) || !inside(ws, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (System.currentTimeMillis() > deadline) {
                        timedOut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!hidden && file.getFileName().toString().startsWith(".")) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!attrs.isRegularFile() && !(attrs.isSymbolicLink() && Files.isRegularFile(file) && inside(ws, file))) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        return visitor.visit(file) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                    } catch (ToolFailure e) {
                        failure[0] = e;
                        return FileVisitResult.TERMINATE;
                    }
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolFailure("Unable to search " + ws.relative(start) + ": " + e.getMessage());
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        if (timedOut[0]) {
            throw new ToolFailure("Search timed out after " + TIMEOUT_MS / 1000 + " seconds. Consider using a more specific path or pattern.");
        }
    }

    static String relative(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }

    static Pattern glob(String pattern) {
        String glob = pattern.replace('\\', '/');
        boolean anchored = glob.contains("/");
        if (glob.startsWith("/")) {
            glob = glob.substring(1);
        }
        StringBuilder regex = new StringBuilder(anchored ? "^" : "^(?:.*/)?");
        boolean inClass = false;
        int braces = 0;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                    regex.append(']');
                } else if ("\\[&~^".indexOf(c) >= 0) {
                    regex.append('\\').append(c);
                } else {
                    regex.append(c);
                }
                continue;
            }
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        boolean slashAfter = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                        regex.append(slashAfter ? "(?:.*/)?" : ".*");
                        i += slashAfter ? 2 : 1;
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                case '[' -> {
                    inClass = true;
                    regex.append('[');
                    if (i + 1 < glob.length() && (glob.charAt(i + 1) == '!' || glob.charAt(i + 1) == '^')) {
                        regex.append('^');
                        i++;
                    }
                }
                case '{' -> {
                    braces++;
                    regex.append("(?:");
                }
                case '}' -> {
                    if (braces > 0) {
                        braces--;
                        regex.append(')');
                    } else {
                        regex.append("\\}");
                    }
                }
                case ',' -> regex.append(braces > 0 ? "|" : ",");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (inClass || braces > 0) {
            return Pattern.compile("^" + Pattern.quote(glob) + "$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        return Pattern.compile(regex.append('$').toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static boolean inside(Workspace ws, Path path) {
        try {
            return ws.contains(path.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }
}
