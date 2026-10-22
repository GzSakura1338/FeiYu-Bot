package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EditTool implements Tool {

    private static final String CRLF = "\r\n";
    private static final Pattern NEWLINE = Pattern.compile("\r?\n");

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "path": {"type": "string", "description": "File to edit"},
              "oldString": {"type": "string", "description": "Exact text to find and replace"},
              "newString": {"type": "string", "description": "Text to replace oldString with (must differ from oldString)"},
              "replaceAll": {"type": "boolean", "description": "Whether to replace every occurrence of oldString. When false, oldString must match exactly once. Defaults to false."}
            }, "required": ["path", "oldString", "newString"]}""");

    record Match(int start, int end) {}

    private record View(List<Match> matches, Function<Match, String> replace) {}

    private record Line(int start, int end, String text, int contentEnd, boolean newline) {}

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Edit the contents of a file by finding and replacing exact text. When editing text from Read output, preserve the exact "
                + "indentation (tabs or spaces) and omit the line-number prefix, such as `1: `. Never include the prefix in oldString or newString. "
                + "Write line breaks as \\n; the file's existing CRLF or LF line endings are matched and preserved. To change line endings "
                + "themselves, make oldString and newString differ only in \\r; that edit applies to the exact bytes. If there is no exact match, "
                + "the edit retries while ignoring typographic quote, dash, and space variants, then trailing whitespace on each line. The edit "
                + "fails if oldString is not found. By default, oldString must identify a UNIQUE location. Multiple matches FAIL unless replaceAll "
                + "is true. Add more surrounding context to disambiguate, or set replaceAll to true to replace every occurrence. Use replaceAll "
                + "when the change should apply to every occurrence, such as renaming a variable.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public String run(JsonObject args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        String input = Args.string(args, "path");
        String oldString = Args.string(args, "oldString");
        String newString = Args.string(args, "newString");
        boolean replaceAll = Args.optBool(args, "replaceAll", false);
        if (oldString.equals(newString)) {
            throw new ToolFailure("No changes to apply: oldString and newString are identical.");
        }
        if (oldString.isEmpty()) {
            throw new ToolFailure("oldString must not be empty. Use write to create or overwrite a file.");
        }
        Path target = ws.existingFile(input);
        String display = ws.relative(target);
        ReentrantLock lock = Workspace.lock(target);
        lock.lock();
        try {
            byte[] bytes = TextFile.readBytes(target, display);
            if (TextFile.isBinary(bytes)) {
                throw new ToolFailure("Cannot edit binary file: " + display);
            }
            TextFile.Decoded original = TextFile.decode(bytes);
            String source = original.text();
            boolean endingsOnly = oldString.replace("\r", "").equals(newString.replace("\r", ""));
            View view = endingsOnly
                    ? new View(findOccurrences(source, oldString), m -> newString)
                    : matchView(source, oldString, newString);
            int replacements = view.matches().size();
            if (replacements == 0) {
                throw new ToolFailure("Could not find oldString in " + input + ". It must match exactly, including whitespace and indentation.");
            }
            if (replacements > 1 && !replaceAll) {
                throw new ToolFailure("Found " + replacements + " matches for oldString, but expected exactly one. Add more surrounding "
                        + "context to make oldString unique, or set replaceAll to true to replace every occurrence.");
            }
            List<Match> applied = replaceAll ? view.matches() : view.matches().subList(0, 1);
            StringBuilder replaced = new StringBuilder(source);
            for (int i = applied.size() - 1; i >= 0; i--) {
                Match m = applied.get(i);
                replaced.replace(m.start(), m.end(), view.replace().apply(m));
            }
            String result = replaced.toString();
            if (result.equals(source)) {
                throw new ToolFailure(endingsOnly
                        ? "No changes to apply: " + input + " already has those line endings."
                        : "No changes to apply: the edit leaves " + input + " unchanged. Line endings follow the file; to change them, "
                          + "write \\r explicitly in oldString or newString.");
            }
            boolean bom = original.bom() || result.startsWith(TextFile.BOM);
            ws.write(target, TextFile.encode(TextFile.stripBom(result), original.charset(), bom));
            return "Edited " + display + " (" + replacements + " replacement" + (replacements == 1 ? "" : "s") + ")";
        } finally {
            lock.unlock();
        }
    }

    static String normalizeForMatch(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(switch (c) {
                case '‘', '’', '‚', '‛' -> '\'';
                case '“', '”', '„', '‟' -> '"';
                case '‐', '‑', '‒', '–', '—', '―', '−' -> '-';
                case ' ', ' ', ' ', '　' -> ' ';
                default -> c >= ' ' && c <= ' ' ? ' ' : c;
            });
        }
        return out.toString();
    }

    static List<Match> findOccurrences(String content, String search) {
        List<Match> result = new ArrayList<>();
        int offset = 0;
        while ((offset = content.indexOf(search, offset)) != -1) {
            result.add(new Match(offset, offset + search.length()));
            offset += search.length();
        }
        return result;
    }

    static List<Match> findLineOccurrences(String content, String search) {
        boolean trailingNewline = search.endsWith("\n");
        List<String> expected = new ArrayList<>(List.of(search.split("\n", -1)));
        if (trailingNewline) {
            expected.removeLast();
        }
        List<Line> lines = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int nl = content.indexOf('\n', start);
            boolean newline = nl >= 0;
            int end = newline ? nl + 1 : content.length();
            String text = content.substring(start, newline ? nl : end);
            lines.add(new Line(start, end, text, start + text.length() - (text.endsWith("\r") ? 1 : 0), newline));
            start = end;
        }
        List<Match> result = new ArrayList<>();
        for (int i = 0; i + expected.size() <= lines.size(); i++) {
            boolean same = true;
            for (int k = 0; k < expected.size() && same; k++) {
                same = lineKey(lines.get(i + k).text()).equals(lineKey(expected.get(k)));
            }
            if (!same) {
                continue;
            }
            Line last = lines.get(i + expected.size() - 1);
            if (trailingNewline && !last.newline()) {
                continue;
            }
            Match candidate = new Match(lines.get(i).start(), trailingNewline ? last.end() : last.contentEnd());
            if (result.stream().noneMatch(m -> m.end() > candidate.start() && m.start() < candidate.end())) {
                result.add(candidate);
            }
        }
        return result;
    }

    static String lineEnding(String text, String fallback) {
        int crlf = count(text, CRLF);
        int lf = count(text, "\n") - crlf;
        if (crlf == lf) {
            return fallback;
        }
        return crlf > lf ? CRLF : "\n";
    }

    static String withRegionEndings(String region, String replacement, String fallback) {
        String[] before = NEWLINE.split(region, -1);
        List<String> endings = new ArrayList<>();
        Matcher m = NEWLINE.matcher(region);
        while (m.find()) {
            endings.add(m.group());
        }
        String[] after = replacement.split("\n", -1);
        String[] beforeKeys = new String[before.length];
        for (int i = 0; i < before.length; i++) {
            beforeKeys[i] = lineKey(before[i]);
        }
        String[] afterKeys = new String[after.length];
        for (int i = 0; i < after.length; i++) {
            afterKeys[i] = lineKey(after[i]);
        }
        Integer[] source = alignLines(beforeKeys, afterKeys);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < after.length; i++) {
            out.append(after[i]);
            if (i < after.length - 1) {
                Integer s = source[i];
                out.append(s != null && s < endings.size() ? endings.get(s) : fallback);
            }
        }
        return out.toString();
    }

    static Integer[] alignLines(String[] before, String[] after) {
        int width = after.length + 1;
        int[] table = new int[(before.length + 1) * width];
        for (int i = before.length - 1; i >= 0; i--) {
            for (int j = after.length - 1; j >= 0; j--) {
                table[i * width + j] = before[i].equals(after[j])
                        ? table[(i + 1) * width + j + 1] + 1
                        : Math.max(table[(i + 1) * width + j], table[i * width + j + 1]);
            }
        }
        Integer[] result = new Integer[after.length];
        List<Integer> gapBefore = new ArrayList<>();
        List<Integer> gapAfter = new ArrayList<>();
        Runnable flush = () -> {
            for (int k = 0; k < gapAfter.size(); k++) {
                result[gapAfter.get(k)] = k < gapBefore.size() ? gapBefore.get(k) : null;
            }
            gapBefore.clear();
            gapAfter.clear();
        };
        int i = 0;
        int j = 0;
        while (i < before.length && j < after.length) {
            if (before[i].equals(after[j])) {
                flush.run();
                result[j++] = i++;
                continue;
            }
            if (table[(i + 1) * width + j] >= table[i * width + j + 1]) {
                gapBefore.add(i++);
            } else {
                gapAfter.add(j++);
            }
        }
        while (i < before.length) {
            gapBefore.add(i++);
        }
        while (j < after.length) {
            gapAfter.add(j++);
        }
        flush.run();
        return result;
    }

    private static View matchView(String source, String oldString, String newString) {
        String view = source.replace(CRLF, "\n");
        List<Integer> removed = new ArrayList<>();
        int index = 0;
        int at = source.indexOf(CRLF);
        while (at >= 0) {
            removed.add(at - index++);
            at = source.indexOf(CRLF, at + 2);
        }
        String fileEnding = lineEnding(source, "\n");
        String search = oldString.replace(CRLF, "\n");
        String replacement = newString.replace(CRLF, "\n");
        List<Match> found = findOccurrences(view, search);
        if (found.isEmpty()) {
            found = findOccurrences(normalizeForMatch(view), normalizeForMatch(search));
        }
        if (found.isEmpty()) {
            found = findLineOccurrences(view, search);
        }
        List<Match> matches = found.stream()
                .map(m -> new Match(m.start() + countBefore(removed, m.start()), m.end() + countBefore(removed, m.end())))
                .toList();
        return new View(matches, m -> {
            String region = source.substring(m.start(), m.end());
            return withRegionEndings(region, replacement, lineEnding(region, fileEnding));
        });
    }

    private static int countBefore(List<Integer> positions, int offset) {
        int low = 0;
        int high = positions.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (positions.get(middle) < offset) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static String lineKey(String line) {
        return normalizeForMatch(line).stripTrailing();
    }

    private static int count(String text, String token) {
        int count = 0;
        int at = text.indexOf(token);
        while (at >= 0) {
            count++;
            at = text.indexOf(token, at + token.length());
        }
        return count;
    }
}
