package dafeiyu.agent.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Patch {

    sealed interface Hunk permits Add, Delete, Update {
        String path();
    }

    record Add(String path, String contents) implements Hunk {}

    record Delete(String path) implements Hunk {}

    record Update(String path, String movePath, List<Chunk> chunks) implements Hunk {}

    static final class Chunk {
        final List<String> oldLines = new ArrayList<>();
        final List<String> newLines = new ArrayList<>();
        final String changeContext;
        boolean endOfFile;

        Chunk(String changeContext) {
            this.changeContext = changeContext;
        }

        boolean empty() {
            return oldLines.isEmpty() && newLines.isEmpty();
        }
    }

    private static final String BEGIN = "*** Begin Patch";
    private static final String END = "*** End Patch";
    private static final String ADD = "*** Add File: ";
    private static final String DELETE = "*** Delete File: ";
    private static final String UPDATE = "*** Update File: ";
    private static final String MOVE = "*** Move to: ";
    private static final String EOF = "*** End of File";
    private static final Pattern HEREDOC = Pattern.compile("^(?:cat\\s+)?<<(['\"]?)(\\w+)\\1\\s*\\n([\\s\\S]*?)\\n\\2\\s*$");

    private Patch() {}

    static List<Hunk> parse(String patchText) throws ToolFailure {
        String text = patchText.strip();
        Matcher heredoc = HEREDOC.matcher(text);
        if (heredoc.matches()) {
            text = heredoc.group(3);
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        if (lines.isEmpty() || !lines.getFirst().strip().equals(BEGIN)) {
            throw new ToolFailure("The first line of the patch must be '" + BEGIN + "'");
        }
        int end = lines.getLast().strip().equals(END) ? lines.size() - 1 : -1;
        if (end <= 0) {
            throw new ToolFailure("The last line of the patch must be '" + END + "'");
        }
        List<Hunk> hunks = new ArrayList<>();
        int index = 1;
        while (index < end) {
            String header = lines.get(index).strip();
            if (header.startsWith(ADD)) {
                String path = header.substring(ADD.length()).strip();
                List<String> content = new ArrayList<>();
                index++;
                while (index < end && !boundary(lines.get(index).strip())) {
                    String line = lines.get(index);
                    if (!line.startsWith("+")) {
                        throw invalid(index, "Invalid Add File line for '" + path + "': expected a line starting with '+', got '" + line.strip() + "'");
                    }
                    content.add(line.substring(1));
                    index++;
                }
                hunks.add(new Add(path, String.join("\n", content)));
            } else if (header.startsWith(DELETE)) {
                String path = header.substring(DELETE.length()).strip();
                if (index + 1 < end && !boundary(lines.get(index + 1).strip())) {
                    throw invalid(index + 1, "Unexpected line after Delete File '" + path + "': '" + lines.get(index + 1).strip()
                            + "'. Delete hunks do not contain body lines");
                }
                hunks.add(new Delete(path));
                index++;
            } else if (header.startsWith(UPDATE)) {
                String path = header.substring(UPDATE.length()).strip();
                int next = index + 1;
                while (next < end && lines.get(next).stripTrailing().equals(EOF)) {
                    next++;
                }
                String movePath = null;
                String move = next < end ? lines.get(next).stripTrailing() : "";
                if (move.startsWith(MOVE.strip())) {
                    movePath = move.length() > MOVE.length() ? move.substring(MOVE.length()).strip() : "";
                    if (movePath.isEmpty()) {
                        throw invalid(next, "Move destination for '" + path + "' must not be empty");
                    }
                    next++;
                }
                List<Chunk> chunks = new ArrayList<>();
                index = parseUpdate(lines, next, end, path, index, chunks);
                hunks.add(new Update(path, movePath, chunks));
            } else {
                throw invalid(index, "'" + header + "' is not a valid hunk header. Valid hunk headers: '*** Add File: {path}', "
                        + "'*** Delete File: {path}', '*** Update File: {path}'");
            }
        }
        if (hunks.isEmpty()) {
            throw new ToolFailure("patch rejected: empty patch");
        }
        return hunks;
    }

    private static int parseUpdate(List<String> lines, int start, int end, String path, int header, List<Chunk> chunks) throws ToolFailure {
        int index = start;
        boolean afterEof = false;
        while (index < end) {
            String line = lines.get(index);
            String trimmed = line.stripTrailing();
            if (afterEof) {
                if (trimmed.isEmpty()) {
                    index++;
                    continue;
                }
                if (trimmed.equals("@@") || trimmed.startsWith("@@ ")) {
                    afterEof = false;
                } else if (boundary(trimmed)) {
                    break;
                } else {
                    throw invalid(index, "Expected update hunk to start with a @@ context marker, got: '" + line + "'");
                }
            }
            if (trimmed.equals(EOF)) {
                Chunk chunk = chunks.isEmpty() ? null : chunks.getLast();
                if (chunk != null && chunk.empty()) {
                    throw invalid(index, "Update hunk does not contain any lines");
                }
                if (chunk != null) {
                    chunk.endOfFile = true;
                    afterEof = true;
                }
                index++;
                continue;
            }
            if (boundary(trimmed)) {
                break;
            }
            if (trimmed.equals("@@") || trimmed.startsWith("@@ ")) {
                if (!chunks.isEmpty() && chunks.getLast().empty()) {
                    throw invalid(index, "Unexpected line found in update hunk: '" + line
                            + "'. Every line should start with ' ' (context line), '+' (added line), or '-' (removed line)");
                }
                chunks.add(new Chunk(trimmed.equals("@@") ? null : trimmed.substring(3)));
                index++;
                continue;
            }
            if (chunks.isEmpty()) {
                chunks.add(new Chunk(null));
            }
            Chunk chunk = chunks.getLast();
            if (line.isEmpty()) {
                chunk.oldLines.add("");
                chunk.newLines.add("");
            } else if (line.startsWith(" ")) {
                chunk.oldLines.add(line.substring(1));
                chunk.newLines.add(line.substring(1));
            } else if (line.startsWith("-")) {
                chunk.oldLines.add(line.substring(1));
            } else if (line.startsWith("+")) {
                chunk.newLines.add(line.substring(1));
            } else {
                throw invalid(index, chunk.empty()
                        ? "Unexpected line found in update hunk: '" + line + "'. Every line should start with ' ' (context line), '+' (added line), or '-' (removed line)"
                        : "Expected update hunk to start with a @@ context marker, got: '" + line + "'");
            }
            index++;
        }
        if (chunks.isEmpty()) {
            throw invalid(header, "Update file hunk for path '" + path + "' is empty");
        }
        if (chunks.getLast().empty()) {
            throw invalid(index, "Update hunk does not contain any lines");
        }
        return index;
    }

    static String derive(String path, List<Chunk> chunks, String original) throws ToolFailure {
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(original.split("\r?\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        List<int[]> spans = new ArrayList<>();
        List<List<String>> inserts = new ArrayList<>();
        int lineIndex = 0;
        for (Chunk chunk : chunks) {
            if (chunk.changeContext != null) {
                int context = seek(lines, List.of(chunk.changeContext), lineIndex, false);
                if (context < 0) {
                    throw new ToolFailure("patch verification failed: Failed to find context '" + chunk.changeContext + "' in " + path);
                }
                lineIndex = context + 1;
            }
            if (chunk.oldLines.isEmpty()) {
                spans.add(new int[] {lines.size(), 0, inserts.size()});
                inserts.add(chunk.newLines);
                continue;
            }
            List<String> oldLines = chunk.oldLines;
            List<String> newLines = chunk.newLines;
            int found = seek(lines, oldLines, lineIndex, chunk.endOfFile);
            if (found < 0 && oldLines.getLast().isEmpty()) {
                oldLines = oldLines.subList(0, oldLines.size() - 1);
                if (!newLines.isEmpty() && newLines.getLast().isEmpty()) {
                    newLines = newLines.subList(0, newLines.size() - 1);
                }
                found = seek(lines, oldLines, lineIndex, chunk.endOfFile);
            }
            if (found < 0) {
                throw new ToolFailure("patch verification failed: Failed to find expected lines in " + path + ":\n" + String.join("\n", chunk.oldLines));
            }
            spans.add(new int[] {found, oldLines.size(), inserts.size()});
            inserts.add(newLines);
            lineIndex = found + oldLines.size();
        }
        spans.sort(Comparator.comparingInt((int[] s) -> s[0]).reversed());
        for (int[] span : spans) {
            List<String> target = lines.subList(span[0], span[0] + span[1]);
            target.clear();
            target.addAll(inserts.get(span[2]));
        }
        return String.join(newline, lines) + newline;
    }

    private static int seek(List<String> lines, List<String> pattern, int start, boolean eof) {
        if (pattern.isEmpty()) {
            return -1;
        }
        List<BiPredicate<String, String>> compares = List.of(String::equals,
                (a, b) -> a.stripTrailing().equals(b.stripTrailing()),
                (a, b) -> a.strip().equals(b.strip()),
                (a, b) -> normalize(a.strip()).equals(normalize(b.strip())));
        if (eof) {
            int offset = lines.size() - pattern.size();
            if (offset < start) {
                return -1;
            }
            for (BiPredicate<String, String> compare : compares) {
                if (matches(lines, pattern, offset, compare)) {
                    return offset;
                }
            }
            return -1;
        }
        for (BiPredicate<String, String> compare : compares) {
            for (int offset = start; offset <= lines.size() - pattern.size(); offset++) {
                if (matches(lines, pattern, offset, compare)) {
                    return offset;
                }
            }
        }
        return -1;
    }

    private static boolean matches(List<String> lines, List<String> pattern, int offset, BiPredicate<String, String> compare) {
        for (int i = 0; i < pattern.size(); i++) {
            if (!compare.test(lines.get(offset + i), pattern.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String value) {
        return value.replaceAll("[‘’‚‛]", "'")
                .replaceAll("[“”„‟]", "\"")
                .replaceAll("[‐‑‒–—―−]", "-")
                .replaceAll("[\\u00A0\\u2002-\\u200A\\u202F\\u205F\\u3000]", " ");
    }

    private static boolean boundary(String line) {
        return line.equals(END) || line.startsWith(ADD) || line.startsWith(DELETE) || line.startsWith(UPDATE);
    }

    private static ToolFailure invalid(int index, String reason) {
        return new ToolFailure("patch verification failed: Invalid hunk at line " + (index + 1) + ": " + reason);
    }
}
