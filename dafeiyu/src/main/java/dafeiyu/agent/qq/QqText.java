package dafeiyu.agent.qq;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public final class QqText {

    private static final int BUBBLE_MAX = 30;
    private static final int LONG_MESSAGE = 600;
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?~～…])");
    private static final Pattern CLAUSE_END = Pattern.compile("(?<=[，,；;])");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+");
    private static final Pattern LIST_LINE = Pattern.compile("^\\s*(\\d+[.、)]|[-*•·]|[一二三四五六七八九十]+、)\\s*");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern STRAY_TAIL = Pattern.compile("(^|[^\\p{IsHan}\\p{L}\\p{N}）)])#\\d{4}(?!\\d)");
    private static final Pattern HTML_BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern HTML_TAG = Pattern.compile(
            "(?i)</?(kbd|b|i|u|s|em|strong|code|pre|sup|sub|span|small|mark|del|ins|p|div|font|center|h[1-6]|ul|ol|li|a)(\\s[^<>]*)?/?>");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\[\\]]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("(?<!`)`([^`\\n]+)`(?!`)");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");
    private static final Pattern STAGE_DIRECTION = Pattern.compile("\\s*[（(][\\p{IsHan}、，…·\\s]{1,12}[）)]");
    private static final Pattern STICKER_TAG = Pattern.compile("[<＜]\\s*表情包\\s*[:：#]?\\s*#?(\\d+)\\s*/?\\s*[>＞]");
    private static final Pattern HAN_TAG = Pattern.compile("[<＜]\\s*/?\\s*\\p{IsHan}[^<>＜＞\\n]{0,20}[>＞]");

    private QqText() {}

    public static String format(String text) {
        String clean = clean(text);
        if (clean.contains("```")) {
            return clean;
        }
        return EXTRA_BLANK_LINES.matcher(clean).replaceAll("\n\n");
    }

    public static List<String> bubbles(String text, int maxPieces) {
        if (maxPieces <= 1 || text.contains("```") || text.length() > LONG_MESSAGE) {
            return List.of(text);
        }
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!out.isEmpty() && LIST_LINE.matcher(line).lookingAt()) {
                out.set(out.size() - 1, out.getLast() + "\n" + line);
            } else if (line.length() > BUBBLE_MAX && !line.contains("http")) {
                out.addAll(sentences(line));
            } else {
                out.add(line);
            }
        }
        while (out.size() > maxPieces) {
            String last = out.removeLast();
            out.set(out.size() - 1, out.getLast() + " " + last);
        }
        out.replaceAll(QqText::casualEnd);
        return out.isEmpty() ? List.of(text) : out;
    }

    private static List<String> sentences(String line) {
        List<String> parts = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(line)) {
            parts.addAll(sentence.length() > BUBBLE_MAX ? List.of(CLAUSE_END.split(sentence)) : List.of(sentence));
        }
        List<String> out = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (String part : parts) {
            if (!chunk.isEmpty() && chunk.length() + part.length() > BUBBLE_MAX) {
                out.add(chunk.toString().strip());
                chunk.setLength(0);
            }
            chunk.append(part);
        }
        if (!chunk.isEmpty()) {
            out.add(chunk.toString().strip());
        }
        return out;
    }

    private static String casualEnd(String line) {
        String trimmed = line.endsWith("，") || line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
        return trimmed.endsWith("。") && !trimmed.endsWith("。。") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    static long bubblePause(String text, int intervalMs) {
        return intervalMs + ThreadLocalRandom.current().nextLong(intervalMs / 2 + 1) + Math.min(1_500, 30L * text.length());
    }

    static long typingPause(String text, long thinkingMs) {
        long typing = 600 + 90L * Math.min(text.length(), 50) + ThreadLocalRandom.current().nextLong(800);
        return Math.max(0, Math.min(5_000, typing) - thinkingMs);
    }

    static String clean(String text) {
        StringBuilder out = new StringBuilder();
        boolean fence = false;
        for (String line : text.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                fence = !fence;
            } else if (!fence) {
                line = HEADING.matcher(line).replaceFirst("").replace("**", "").replace("__", "");
                line = HTML_TAG.matcher(HTML_BREAK.matcher(line).replaceAll(" ")).replaceAll("");
                line = INLINE_CODE.matcher(MD_LINK.matcher(line).replaceAll("$1 $2")).replaceAll("$1");
                line = HAN_TAG.matcher(STICKER_TAG.matcher(line).replaceAll("[表情包:$1]")).replaceAll("");
                line = STAGE_DIRECTION.matcher(line).replaceAll("");
                line = SPACES.matcher(STRAY_TAIL.matcher(line).replaceAll("$1")).replaceAll(" ").stripTrailing();
            }
            out.append(out.isEmpty() ? "" : "\n").append(line);
        }
        return out.toString().strip();
    }
}
