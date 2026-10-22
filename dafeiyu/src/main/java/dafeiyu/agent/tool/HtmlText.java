package dafeiyu.agent.tool;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HtmlText {

    private static final Pattern DROP = Pattern.compile(
            "(?is)<(script|style|noscript|svg|template|iframe|canvas|head|nav|footer|form)\\b[^>]*>.*?</\\1\\s*>|<!--.*?-->|<!doctype[^>]*>");
    private static final Pattern HEADING = Pattern.compile("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>");
    private static final Pattern LINK = Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a\\s*>");
    private static final Pattern IMAGE = Pattern.compile("(?is)<img\\b[^>]*?alt\\s*=\\s*([\"'])(.*?)\\1[^>]*>");
    private static final Pattern PRE = Pattern.compile("(?is)<pre\\b[^>]*>(.*?)</pre\\s*>");
    private static final Pattern CODE = Pattern.compile("(?is)<code\\b[^>]*>(.*?)</code\\s*>");
    private static final Pattern BOLD = Pattern.compile("(?is)<(strong|b)\\b[^>]*>(.*?)</\\1\\s*>");
    private static final Pattern ITEM = Pattern.compile("(?is)<li\\b[^>]*>");
    private static final Pattern BREAK = Pattern.compile("(?is)<br\\s*/?>");
    private static final Pattern BLOCK = Pattern.compile("(?is)</?(p|div|section|article|header|main|aside|ul|ol|table|tr|blockquote|h[1-6]|dl|dt|dd|figure|figcaption|hr)\\b[^>]*>");
    private static final Pattern CELL = Pattern.compile("(?is)</t[dh]\\s*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");
    private static final Pattern TITLE = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title\\s*>");
    private static final Map<String, String> ENTITIES = Map.ofEntries(Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("ensp", " "), Map.entry("emsp", " "), Map.entry("thinsp", " "), Map.entry("mdash", "—"), Map.entry("ndash", "–"),
            Map.entry("hellip", "…"), Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("middot", "·"), Map.entry("laquo", "«"),
            Map.entry("raquo", "»"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"));

    private HtmlText() {}

    static String markdown(String html) {
        String title = title(html);
        String text = DROP.matcher(html).replaceAll(" ");
        text = PRE.matcher(text).replaceAll(m -> Matcher.quoteReplacement("\n```\n" + TAG.matcher(m.group(1)).replaceAll("") + "\n```\n"));
        text = HEADING.matcher(text).replaceAll(m -> Matcher.quoteReplacement("\n\n" + "#".repeat(Integer.parseInt(m.group(1))) + " "
                + oneLine(m.group(2)) + "\n\n"));
        text = LINK.matcher(text).replaceAll(m -> {
            String label = oneLine(m.group(3));
            String href = m.group(2).strip();
            if (label.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")) {
                return Matcher.quoteReplacement(label);
            }
            return Matcher.quoteReplacement("[" + label + "](" + href + ")");
        });
        text = IMAGE.matcher(text).replaceAll(m -> m.group(2).isBlank() ? "" : Matcher.quoteReplacement("![" + m.group(2).strip() + "]"));
        text = CODE.matcher(text).replaceAll(m -> Matcher.quoteReplacement("`" + m.group(1) + "`"));
        text = BOLD.matcher(text).replaceAll(m -> Matcher.quoteReplacement("**" + m.group(2) + "**"));
        text = ITEM.matcher(text).replaceAll("\n- ");
        text = BREAK.matcher(text).replaceAll("\n");
        text = CELL.matcher(text).replaceAll(" | ");
        text = BLOCK.matcher(text).replaceAll("\n\n");
        text = decode(TAG.matcher(text).replaceAll(""));
        text = tidy(text);
        return title.isEmpty() || text.startsWith("# ") ? text : "# " + title + "\n\n" + text;
    }

    static String text(String html) {
        String text = DROP.matcher(html).replaceAll(" ");
        text = BREAK.matcher(text).replaceAll("\n");
        text = ITEM.matcher(text).replaceAll("\n- ");
        text = BLOCK.matcher(text).replaceAll("\n\n");
        return tidy(decode(TAG.matcher(text).replaceAll("")));
    }

    private static String title(String html) {
        Matcher m = TITLE.matcher(html);
        return m.find() ? oneLine(m.group(1)) : "";
    }

    private static String oneLine(String fragment) {
        return decode(TAG.matcher(fragment).replaceAll("")).replaceAll("\\s+", " ").strip();
    }

    private static String tidy(String text) {
        StringBuilder out = new StringBuilder();
        boolean code = false;
        int blank = 0;
        for (String line : text.split("\n", -1)) {
            if (line.strip().equals("```")) {
                code = !code;
            }
            String clean = code ? line.stripTrailing() : line.replaceAll("[ \\t\\u00A0]+", " ").strip();
            if (clean.isEmpty() && !code) {
                blank++;
                continue;
            }
            if (!out.isEmpty()) {
                out.append(blank > 0 ? "\n\n" : "\n");
            }
            blank = 0;
            out.append(clean);
        }
        return out.toString().strip();
    }

    static String decode(String text) {
        return ENTITY.matcher(text).replaceAll(m -> {
            String name = m.group(1);
            try {
                if (name.startsWith("#x")) {
                    return Matcher.quoteReplacement(Character.toString(Integer.parseInt(name.substring(2), 16)));
                }
                if (name.startsWith("#")) {
                    return Matcher.quoteReplacement(Character.toString(Integer.parseInt(name.substring(1))));
                }
            } catch (IllegalArgumentException e) {
                return Matcher.quoteReplacement(m.group());
            }
            return Matcher.quoteReplacement(ENTITIES.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), m.group()));
        });
    }
}
