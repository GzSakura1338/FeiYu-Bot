package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ReadTool extends TextTool {

    static final int MAX_LINES = 2_000;
    static final int MAX_BYTES = 50 * 1024;
    static final int MAX_LINE_LENGTH = 2_000;

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "path": {"type": "string", "description": "File or directory to read"},
              "offset": {"type": "integer", "description": "The line or directory entry to start reading from (1-based)"},
              "limit": {"type": "integer", "description": "The maximum number of lines or directory entries to read (defaults to and capped at 2000)"}
            }, "required": ["path"]}""");

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description(ToolContext context) {
        return "Read the contents of a file or directory. Each text line is prefixed by its 1-based line number as <line>: <content>. "
                + "The prefix is for reference and is not part of the file content. Directory entries are returned one per line. "
                + "Use offset and limit to read large files or directories in sections. Prefer one larger read over many small slices, "
                + "and use grep to find specific content in large files.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    protected String text(Args args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        String input = args.string("path");
        Integer offset = args.optInt("offset");
        Integer limit = args.optInt("limit");
        Path path = ws.resolve(input);
        if (!Files.exists(path)) {
            throw missing(ws, input, path);
        }
        if (Files.isDirectory(path)) {
            return list(ws, path, offset, limit);
        }
        if (!Files.isRegularFile(path)) {
            throw new ToolFailure("Not a regular file: " + input);
        }
        String display = ws.relative(path);
        byte[] bytes = TextFile.readBytes(path, display);
        if (TextFile.isBinary(bytes)) {
            throw new ToolFailure("Cannot read binary file: " + display);
        }
        return page(display, TextFile.decode(bytes).text(), bytes.length > MAX_BYTES || offset != null || limit != null, offset, limit);
    }

    static String page(String display, String text, boolean paged, Integer offsetArg, Integer limitArg) throws ToolFailure {
        List<String> lines = splitLines(text);
        int offset = offsetArg == null || offsetArg < 1 ? 1 : offsetArg;
        if (offset > lines.size() && !(lines.isEmpty() && offset == 1)) {
            throw new ToolFailure("Offset " + offset + " is out of range for this file (" + lines.size() + " lines)");
        }
        int limit = paged ? Math.min(limitArg == null || limitArg < 1 ? MAX_LINES : limitArg, MAX_LINES) : Integer.MAX_VALUE;
        List<String> selected = new ArrayList<>();
        int size = 0;
        Integer next = null;
        for (int i = offset - 1; i < lines.size(); i++) {
            if (selected.size() >= limit) {
                next = i + 1;
                break;
            }
            String line = clamp(lines.get(i));
            int lineSize = line.getBytes(StandardCharsets.UTF_8).length + (selected.isEmpty() ? 0 : 1);
            if (paged && size + lineSize > MAX_BYTES) {
                next = i + 1;
                break;
            }
            selected.add(line);
            size += lineSize;
        }
        StringBuilder out = new StringBuilder(selected.isEmpty()
                ? "Read file " + display + ", 0 lines"
                : "Read file " + display + ", lines " + offset + "-" + (offset + selected.size() - 1));
        for (int i = 0; i < selected.size(); i++) {
            out.append('\n').append(offset + i).append(": ").append(selected.get(i));
        }
        if (next != null) {
            out.append("\n[Output truncated. Continue reading with offset: ").append(next).append(']');
        }
        return out.toString();
    }

    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) {
            return lines;
        }
        int start = 0;
        while (start <= text.length()) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) {
                if (start < text.length()) {
                    lines.add(stripCr(text.substring(start)));
                }
                break;
            }
            lines.add(stripCr(text.substring(start, nl)));
            start = nl + 1;
        }
        return lines;
    }

    static String clamp(String line) {
        return line.length() > MAX_LINE_LENGTH
                ? line.substring(0, MAX_LINE_LENGTH) + "... (line truncated to " + MAX_LINE_LENGTH + " chars)"
                : line;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String list(Workspace ws, Path dir, Integer offsetArg, Integer limitArg) throws ToolFailure {
        record Entry(String name, boolean directory) {}
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                boolean directory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(child);
                if (!directory && !Files.isRegularFile(child)) {
                    continue;
                }
                entries.add(new Entry(child.getFileName().toString(), directory));
            }
        } catch (IOException e) {
            throw new ToolFailure("Unable to read directory " + ws.relative(dir) + ": " + e.getMessage());
        }
        entries.sort(Comparator.comparing((Entry e) -> !e.directory()).thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        int offset = offsetArg == null || offsetArg < 1 ? 1 : offsetArg;
        int limit = Math.min(limitArg == null || limitArg < 1 ? MAX_LINES : limitArg, MAX_LINES);
        List<Entry> selected = entries.subList(Math.min(offset - 1, entries.size()), Math.min(offset - 1 + limit, entries.size()));
        String display = ws.relative(dir);
        StringBuilder out = new StringBuilder(selected.isEmpty()
                ? "Read directory " + display + ", 0 entries"
                : "Read directory " + display + ", entries " + offset + "-" + (offset + selected.size() - 1));
        for (Entry e : selected) {
            out.append('\n').append(e.name()).append(e.directory() ? "/" : "");
        }
        if (offset - 1 + selected.size() < entries.size()) {
            out.append("\n[Output truncated. Continue reading with offset: ").append(offset + selected.size()).append(']');
        }
        return out.toString();
    }

    private static ToolFailure missing(Workspace ws, String input, Path path) {
        String base = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        Path parent = path.getParent();
        if (parent != null && Files.isDirectory(parent) && !base.isEmpty()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
                for (Path child : stream) {
                    String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.contains(base) || base.contains(name)) {
                        suggestions.add(ws.relative(child));
                        if (suggestions.size() == 3) {
                            break;
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return new ToolFailure(suggestions.isEmpty()
                ? "File not found: " + input
                : "File not found: " + input + "\n\nDid you mean one of these?\n" + String.join("\n", suggestions));
    }
}
