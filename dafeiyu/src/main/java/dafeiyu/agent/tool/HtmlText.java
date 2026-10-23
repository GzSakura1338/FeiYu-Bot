package dafeiyu.agent.tool;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HtmlText {

    record Link(String text, String url) {}

    record Img(String alt, String url) {}

    record Page(String title, String text, List<Link> links, List<Img> images) {}

    private static final Pattern REGION = Pattern.compile("(?is)<(article|main)\\b[^>]*>(.*?)</\\1\\s*>");
    private static final Pattern IMG_TAG = Pattern.compile("(?is)<img\\b[^>]*>");
    private static final Pattern ATTR = Pattern.compile("(?is)\\b([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern NOT_CONTENT = Pattern.compile("(?i)logo|icon|avatar|sprite|blank|placeholder|loading|spacer|pixel|1x1|emoji|badge");
    private static final Pattern LIST = Pattern.compile("(?is)<(ul|ol)\\b[^>]*>((?:(?!<(?:ul|ol)\\b).)*?)</\\1\\s*>");
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\b[^>]*>(.*?)</a\\s*>");
    private static final Pattern LIST_ITEM = Pattern.compile("(?i)<li\\b");
    private static final int MIN_REGION_TEXT = 400;
    private static final int MAX_PRUNE_HTML = 3_000_000;

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

    static Page page(String html, URI base, int maxLinks, int maxImages) {
        String title = title(html);
        String scope = withoutMenus(DROP.matcher(region(html)).replaceAll(" "));
        List<Img> images = images(scope, base, maxImages);
        Map<String, Integer> numbers = new HashMap<>();
        List<Link> links = new ArrayList<>();
        String text = DROP.matcher(scope).replaceAll(" ");
        text = PRE.matcher(text).replaceAll(m -> Matcher.quoteReplacement("\n```\n" + TAG.matcher(m.group(1)).replaceAll("") + "\n```\n"));
        text = HEADING.matcher(text).replaceAll(m -> Matcher.quoteReplacement("\n\n" + "#".repeat(Integer.parseInt(m.group(1))) + " "
                + oneLine(m.group(2)) + "\n\n"));
        text = LINK.matcher(text).replaceAll(m -> {
            String label = oneLine(m.group(3));
            String href = resolve(base, decode(m.group(2)));
            if (label.isEmpty() || href == null) {
                return Matcher.quoteReplacement(label);
            }
            Integer n = numbers.get(href);
            if (n == null && links.size() < maxLinks) {
                n = links.size() + 1;
                numbers.put(href, n);
                links.add(new Link(label.length() > 60 ? label.substring(0, 60) + "…" : label, href));
            }
            return Matcher.quoteReplacement(n == null ? label : label + "[" + n + "]");
        });
        text = IMAGE.matcher(text).replaceAll(m -> m.group(2).isBlank() ? "" : Matcher.quoteReplacement("[图：" + oneLine(m.group(2)) + "]"));
        text = CODE.matcher(text).replaceAll(m -> Matcher.quoteReplacement("`" + m.group(1) + "`"));
        text = BOLD.matcher(text).replaceAll(m -> Matcher.quoteReplacement("**" + m.group(2) + "**"));
        text = ITEM.matcher(text).replaceAll("\n- ");
        text = BREAK.matcher(text).replaceAll("\n");
        text = CELL.matcher(text).replaceAll(" | ");
        text = BLOCK.matcher(text).replaceAll("\n\n");
        text = tidy(decode(TAG.matcher(text).replaceAll("")));
        return new Page(title, text, List.copyOf(links), images);
    }

    private static String region(String html) {
        String best = null;
        int bestLength = MIN_REGION_TEXT;
        Matcher m = REGION.matcher(html);
        while (m.find()) {
            int length = TAG.matcher(DROP.matcher(m.group(2)).replaceAll(" ")).replaceAll("").replaceAll("\\s+", "").length();
            if (length >= bestLength) {
                best = m.group(2);
                bestLength = length;
            }
        }
        return best == null ? html : best;
    }

    private static String withoutMenus(String html) {
        if (html.length() > MAX_PRUNE_HTML) {
            return html;
        }
        String current = html;
        for (int pass = 0; pass < 4; pass++) {
            Matcher m = LIST.matcher(current);
            StringBuilder out = new StringBuilder(current.length());
            boolean changed = false;
            while (m.find()) {
                boolean menu = menu(m.group(2));
                changed |= menu;
                m.appendReplacement(out, menu ? " " : Matcher.quoteReplacement(m.group()));
            }
            m.appendTail(out);
            current = out.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }

    private static boolean menu(String list) {
        int items = 0;
        Matcher item = LIST_ITEM.matcher(list);
        while (item.find()) {
            items++;
        }
        int text = visible(list);
        if (items < 3 || text == 0) {
            return false;
        }
        int linked = 0;
        Matcher a = ANCHOR.matcher(list);
        while (a.find()) {
            linked += visible(a.group(1));
        }
        return linked >= text * 0.6 && text / items < 40;
    }

    private static int visible(String html) {
        return TAG.matcher(html).replaceAll("").replaceAll("\\s+", "").length();
    }

    private static List<Img> images(String html, URI base, int max) {
        List<Img> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher tag = IMG_TAG.matcher(DROP.matcher(html).replaceAll(" "));
        while (tag.find() && out.size() < max) {
            Map<String, String> attrs = new HashMap<>();
            Matcher a = ATTR.matcher(tag.group());
            while (a.find()) {
                String value = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4);
                attrs.putIfAbsent(a.group(1).toLowerCase(Locale.ROOT), decode(value));
            }
            String src = attrs.getOrDefault("data-src", attrs.getOrDefault("data-original", attrs.getOrDefault("src", "")));
            String url = resolve(base, src);
            if (url == null || url.toLowerCase(Locale.ROOT).contains(".svg") || NOT_CONTENT.matcher(url).find() || small(attrs) || !seen.add(url)) {
                continue;
            }
            out.add(new Img(oneLine(attrs.getOrDefault("alt", "")), url));
        }
        return List.copyOf(out);
    }

    private static boolean small(Map<String, String> attrs) {
        for (String key : List.of("width", "height")) {
            String value = attrs.getOrDefault(key, "").replaceAll("[^0-9]", "");
            if (!value.isEmpty() && value.length() < 4 && Integer.parseInt(value) < 60) {
                return true;
            }
        }
        return false;
    }

    private static String resolve(URI base, String href) {
        String h = href == null ? "" : href.strip();
        String lower = h.toLowerCase(Locale.ROOT);
        if (h.isEmpty() || h.startsWith("#") || lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("tel:")
                || lower.startsWith("data:")) {
            return null;
        }
        try {
            URI uri = base.resolve(h.replace(" ", "%20").replace("|", "%7C"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals("http") || scheme.equals("https") ? uri.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
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
