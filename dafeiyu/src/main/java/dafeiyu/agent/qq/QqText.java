package dafeiyu.agent.qq;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public final class QqText {

    private static final int CASUAL_MAX_CHARS = 150;
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+");
    private static final Pattern LIST_LINE = Pattern.compile("(?m)^\\s*(\\d+[.、)]|[-*•·])\\s");
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\n\\s*");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

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
            }
            out.append(out.isEmpty() ? "" : "\n").append(line);
        }
        return out.toString().strip();
    }
}
