package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class GrepTool extends TextTool {

    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_PREVIEW = 500;

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "pattern": {"type": "string", "description": "Regular expression or literal text to match in file contents."},
              "path": {"type": "string", "description": "File or directory to search. Defaults to the current working directory."},
              "include": {"type": "string", "description": "Glob pattern to filter files (for example, \\"*.js\\" or \\"*.{ts,tsx}\\")"},
              "literal": {"type": "boolean", "description": "Treat `pattern` as exact text instead of a regular expression (default: false)."},
              "caseSensitive": {"type": "boolean", "description": "Use case-sensitive matching (default: true)."},
              "limit": {"type": "integer", "description": "Maximum number of matching lines to return (default: 100)"}
            }, "required": ["pattern"]}""");

    private record Hit(String path, int line, String text) {}

    private static final class Deadline implements CharSequence {

        static final class Expired extends RuntimeException {
            Expired() {
                super(null, null, false, false);
            }
        }

        private final CharSequence text;
        private final long deadline;
        private int reads;

        Deadline(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFFF) == 0 && System.nanoTime() > deadline) {
                throw new Expired();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description(ToolContext context) {
        return "Search file contents using regular expression syntax or literal text matching. Use it to locate specific code, symbols, "
                + "or text patterns, and narrow searches with `path` or `include`. Returns matching file paths, line numbers, and line previews.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    protected String text(Args args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        String text = args.string("pattern");
        if (text.isEmpty()) {
            throw new ToolFailure("Pattern must not be empty");
        }
        int flags = args.bool("caseSensitive", true) ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Pattern pattern;
        try {
            pattern = Pattern.compile(args.bool("literal", false) ? Pattern.quote(text) : text, flags);
        } catch (PatternSyntaxException e) {
            throw new ToolFailure("Invalid regex pattern: " + e.getDescription());
        }
        String include = args.optString("include");
        Pattern filter = include == null || include.isBlank() ? null : FileWalker.glob(include.strip());
        Integer limitArg = args.optInt("limit");
        int limit = limitArg == null || limitArg < 1 ? FileWalker.DEFAULT_LIMIT : limitArg;
        Path target = ws.resolve(args.optString("path"));
        if (!Files.exists(target)) {
            throw new ToolFailure("Search path does not exist: " + args.optString("path"));
        }
        List<Hit> hits = new ArrayList<>();
        long deadline = System.nanoTime() + FileWalker.TIMEOUT_MS * 1_000_000;
        try {
            if (Files.isRegularFile(target)) {
                search(ws, target, pattern, hits, limit, deadline);
            } else {
                FileWalker.walk(ws, target, false, file -> {
                    if (filter == null || filter.matcher(FileWalker.relative(target, file)).matches()) {
                        search(ws, file, pattern, hits, limit, deadline);
                    }
                    return hits.size() <= limit;
                });
            }
        } catch (Deadline.Expired e) {
            throw new ToolFailure("Search timed out after " + FileWalker.TIMEOUT_MS / 1000 + " seconds. Consider using a simpler pattern or a more specific path.");
        }
        boolean truncated = hits.size() > limit;
        List<Hit> shown = hits.subList(0, Math.min(limit, hits.size()));
        List<String> lines = new ArrayList<>();
        lines.add(shown.isEmpty() ? "No matches found" : "Found " + shown.size() + " matches");
        String current = "";
        for (Hit hit : shown) {
            if (!current.equals(hit.path())) {
                if (!current.isEmpty()) {
                    lines.add("");
                }
                current = hit.path();
                lines.add(hit.path() + ":");
            }
            lines.add("  Line " + hit.line() + ": " + hit.text());
        }
        if (truncated) {
            lines.add("");
            lines.add("(Results are truncated: showing first " + shown.size() + " results. Consider using a more specific path or pattern.)");
        }
        return String.join("\n", lines);
    }

    private static void search(Workspace ws, Path file, Pattern pattern, List<Hit> hits, int limit, long deadline) {
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return;
            }
            bytes = Files.readAllBytes(file);
        } catch (java.io.IOException e) {
            return;
        }
        if (TextFile.isBinary(bytes)) {
            return;
        }
        List<String> lines = ReadTool.splitLines(TextFile.decode(bytes).text());
        String display = ws.relative(file);
        for (int i = 0; i < lines.size() && hits.size() <= limit; i++) {
            if (pattern.matcher(new Deadline(lines.get(i), deadline)).find()) {
                String line = lines.get(i).strip();
                hits.add(new Hit(display, i + 1, line.length() > MAX_PREVIEW ? line.substring(0, MAX_PREVIEW) + "…" : line));
            }
        }
    }
}
