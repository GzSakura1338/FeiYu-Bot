package dafeiyu.agent.qq;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public final class QqText {

    private static final int CASUAL_MAX_CHARS = 150;
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+");
    private static final Pattern LIST_LINE = Pattern.compile("(?m)^\\s*(\\d+[.、)]|[-*•·])\\s");
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\n\\s*");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern STRAY_TAIL = Pattern.compile("(^|[^\\p{IsHan}\\p{L}\\p{N}）)])#\\d{4}(?!\\d)");
    private static final Pattern HTML_BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern HTML_TAG = Pattern.compile(
            "(?i)</?(kbd|b|i|u|s|em|strong|code|pre|sup|sub|span|small|mark|del|ins|p|div|font|center|h[1-6]|ul|ol|li|a)(\\s[^<>]*)?/?>");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\[\\]]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("(?<!`)`([^`\\n]+)`(?!`)");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");

    private QqText() {}

    public static String format(String text) {
        String clean = clean(text);
        if (clean.contains("```")) {
            return clean;
        }
        if (clean.length() <= CASUAL_MAX_CHARS && !LIST_LINE.matcher(clean).find()) {
            String line = LINE_BREAKS.matcher(clean).replaceAll(" ");
            return line.endsWith("。") && !line.endsWith("。。") ? line.substring(0, line.length() - 1) : line;
        }
        return EXTRA_BLANK_LINES.matcher(clean).replaceAll("\n\n");
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
                line = SPACES.matcher(STRAY_TAIL.matcher(line).replaceAll("$1")).replaceAll(" ").stripTrailing();
            }
            out.append(out.isEmpty() ? "" : "\n").append(line);
        }
        return out.toString().strip();
    }
}
