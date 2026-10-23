package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WebFetchTool implements Tool {

    private record Page(String url, String title, String text, List<HtmlText.Link> links, List<HtmlText.Img> images, String note, long at) {}

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int PAGE_CHARS = 8_000;
    private static final int FIND_CHARS = 6_000;
    private static final int MAX_LINKS = 30;
    private static final int MAX_IMAGES = 8;
    private static final int THIN_PAGE = 200;
    private static final int EXA_CHARS = 40_000;
    private static final long CACHE_MS = 15 * 60_000;
    private static final int CACHE_SIZE = 24;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Set<Integer> BLOCKED = Set.of(401, 403, 412, 418, 429, 451, 503, 999);
    private static final Pattern META_CHARSET = Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)");
    private static final Pattern KEYWORDS = Pattern.compile("[\\s,，、;；|]+");
    private static final Map<String, Page> CACHE = new LinkedHashMap<>(16, 0.75f, true);

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "url": {"type": "string", "description": "要打开的网址，http:// 或 https:// 开头"},
              "find": {"type": "string", "description": "只想找某件事时写关键词（多个用空格分开），只返回提到它们的段落"},
              "offset": {"type": "number", "description": "长网页接着往下读时，填上次结果里说的位置"},
              "format": {"type": "string", "enum": ["markdown", "text", "html"], "description": "默认 markdown；要看网页源码才用 html"}
            }, "required": ["url"]}""");

    @Override
    public String name() {
        return "webfetch";
    }

    @Override
    public String description() {
        return """
                打开一个公开网页，读里面的内容（只能访问公网，本机和局域网地址会被拒绝）。返回标题、正文，第一次打开时还会列出页面上的链接和图片。
                - 长网页一次只给一段（约 8000 字），结果里会写还剩多少、用 offset 填几接着读。
                - 只想知道某件事，就用 find 写关键词，只返回相关段落，又快又省事；不用整页从头读。
                - 想顺着点下去，就把链接列表里的网址再交给 webfetch（正文里的 [3] 对应链接 3）；想看页面上的图，用 view_image 填图片网址。
                - 同一个网页 15 分钟内再读不用重新下载。
                - 知乎、百度百科、贴吧、B站这类网站常常不让程序读，打不开就换个来源，或者用搜索结果的摘要。""";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public String run(JsonObject args, ToolContext context) throws ToolFailure {
        String url = Args.string(args, "url").strip();
        String format = Args.optString(args, "format");
        format = format == null || format.isBlank() ? "markdown" : format.strip().toLowerCase(Locale.ROOT);
        if (!format.equals("markdown") && !format.equals("text") && !format.equals("html")) {
            throw new ToolFailure("format 只能是 markdown、text 或 html");
        }
        Integer offset = Args.optInt(args, "offset");
        String find = Args.optString(args, "find");
        Page page = page(url, format);
        if (find != null && !find.isBlank()) {
            return found(page, find.strip());
        }
        return slice(page, offset == null ? 0 : Math.max(0, offset));
    }

    private static Page page(String url, String format) throws ToolFailure {
        String key = format + " " + url;
        long now = System.currentTimeMillis();
        synchronized (CACHE) {
            Page cached = CACHE.get(key);
            if (cached != null && now - cached.at() < CACHE_MS) {
                return cached;
            }
        }
        Page page = fetch(url, format);
        synchronized (CACHE) {
            CACHE.put(key, page);
            while (CACHE.size() > CACHE_SIZE) {
                CACHE.remove(CACHE.keySet().iterator().next());
            }
        }
        return page;
    }

    private static Page fetch(String url, String format) throws ToolFailure {
        PublicWeb.Response response;
        try {
            response = PublicWeb.get(url, accept(format), MAX_BYTES, TIMEOUT);
        } catch (ToolFailure e) {
            if (e.getMessage().startsWith("不能访问") || e.getMessage().startsWith("网址")) {
                throw e;
            }
            return viaExa(url, e.getMessage());
        }
        int status = response.status();
        if (status >= 400) {
            if (BLOCKED.contains(status)) {
                return viaExa(response.uri().toString(), "网站拒绝了程序访问（HTTP " + status + "）");
            }
            throw new ToolFailure("打不开：HTTP " + status + (status == 404 ? "，这个网页不存在" : ""));
        }
        String type = response.type();
        String where = response.uri().toString();
        if (type.startsWith("image/")) {
            return new Page(where, "", "这个网址是一张图片（" + type + "），想看就用 view_image 填这个网址。", List.of(), List.of(), "", System.currentTimeMillis());
        }
        if (type.startsWith("audio/") || type.startsWith("video/") || type.contains("octet-stream") || type.contains("zip") || type.contains("pdf")) {
            return new Page(where, "", "这个网址是文件（" + type + "，" + response.body().length / 1024 + " KB），不是网页，读不出文字。",
                    List.of(), List.of(), "", System.currentTimeMillis());
        }
        String raw = new String(response.body(), charset(type, response.body()));
        boolean html = type.contains("html") || type.isEmpty() && raw.stripLeading().startsWith("<");
        if (!html || format.equals("html")) {
            return new Page(where, "", raw.strip(), List.of(), List.of(), "", System.currentTimeMillis());
        }
        HtmlText.Page parsed = format.equals("text") ? new HtmlText.Page("", HtmlText.text(raw), List.of(), List.of())
                : HtmlText.page(raw, response.uri(), MAX_LINKS, MAX_IMAGES);
        Page page = new Page(where, parsed.title(), parsed.text(), parsed.links(), parsed.images(), "", System.currentTimeMillis());
        if (parsed.text().length() < THIN_PAGE) {
            try {
                Page rendered = viaExa(where, "");
                if (rendered.text().length() > parsed.text().length() * 2) {
                    return new Page(where, parsed.title().isEmpty() ? rendered.title() : parsed.title(), rendered.text(), parsed.links(),
                            parsed.images(), "这个网页要靠浏览器脚本才显示内容，下面的正文是另外抓取到的。", rendered.at());
                }
            } catch (ToolFailure ignored) {
            }
        }
        return page;
    }

    private static Page viaExa(String url, String why) throws ToolFailure {
        try {
            String text = WebSearch.exaFetch(url, EXA_CHARS);
            String title = "";
            Matcher m = Pattern.compile("(?m)^Title:\\s*(.+)$").matcher(text);
            if (m.find()) {
                title = m.group(1).strip();
            }
            text = text.replaceAll("(?m)^(Title|URL|Published|Author|Published Date):.*$\\n?", "").strip();
            return new Page(url, title, text, List.of(), List.of(), why.isEmpty() ? "" : why + "，下面是另外抓取到的正文。", System.currentTimeMillis());
        } catch (IOException e) {
            throw new ToolFailure((why.isEmpty() ? "打不开这个网页" : why) + "，另外抓取也失败了。换个来源，或者用搜索结果的摘要。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("请求被取消了");
        }
    }

    private static String slice(Page page, int offset) {
        String text = page.text();
        int total = text.length();
        StringBuilder out = head(page);
        if (total == 0) {
            return out.append("这个网页里没有能读的文字。").toString();
        }
        if (offset >= total) {
            return out.append("已经读到头了（全文 ").append(total).append(" 字）。").toString();
        }
        int end = Math.min(total, offset + PAGE_CHARS);
        if (end < total) {
            int cut = text.lastIndexOf('\n', end);
            end = cut > offset + PAGE_CHARS / 2 ? cut : end;
        }
        if (offset > 0 || end < total) {
            out.append("（全文 ").append(total).append(" 字，这里是第 ").append(offset).append("–").append(end).append(" 字")
                    .append(end < total ? "；后面还有，用 offset=" + end + " 接着读，或者用 find 找关键词" : "，读完了").append("）\n\n");
        }
        out.append(text, offset, end);
        if (offset == 0) {
            appendLinks(out, page);
        }
        return out.toString().strip();
    }

    private static String found(Page page, String find) {
        List<String> keys = Arrays.stream(KEYWORDS.split(find.toLowerCase(Locale.ROOT))).filter(k -> !k.isBlank()).distinct().toList();
        String[] paragraphs = page.text().split("\n{2,}");
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < paragraphs.length; i++) {
            String lower = paragraphs[i].toLowerCase(Locale.ROOT);
            int score = (int) keys.stream().filter(lower::contains).count();
            if (score > 0) {
                scored.add(new int[] {i, score});
            }
        }
        StringBuilder out = head(page);
        if (scored.isEmpty()) {
            String start = page.text().length() > 600 ? page.text().substring(0, 600) + "…" : page.text();
            return out.append("这个网页里没找到「").append(find).append("」。网页开头是：\n\n").append(start).toString().strip();
        }
        scored.sort((a, b) -> b[1] != a[1] ? Integer.compare(b[1], a[1]) : Integer.compare(a[0], b[0]));
        List<Integer> chosen = new ArrayList<>();
        int used = 0;
        for (int[] s : scored) {
            int length = Math.min(paragraphs[s[0]].length(), 1500);
            if (used + length > FIND_CHARS && !chosen.isEmpty()) {
                break;
            }
            chosen.add(s[0]);
            used += length;
        }
        chosen.sort(Integer::compare);
        out.append("（在全文 ").append(page.text().length()).append(" 字里找「").append(find).append("」，有 ").append(scored.size())
                .append(" 段提到，按原文顺序列出").append(chosen.size() < scored.size() ? "最相关的 " + chosen.size() + " 段" : "").append("）\n\n");
        int previous = -2;
        for (int index : chosen) {
            if (previous >= 0 && index != previous + 1) {
                out.append("……\n\n");
            }
            String p = paragraphs[index];
            out.append(p.length() > 1500 ? p.substring(0, 1500) + "…" : p).append("\n\n");
            previous = index;
        }
        return out.toString().strip();
    }

    private static StringBuilder head(Page page) {
        StringBuilder out = new StringBuilder();
        if (!page.title().isBlank()) {
            out.append("# ").append(page.title()).append('\n');
        }
        out.append("网址：").append(page.url()).append("\n");
        if (!page.note().isBlank()) {
            out.append("（").append(page.note()).append("）\n");
        }
        return out.append('\n');
    }

    private static void appendLinks(StringBuilder out, Page page) {
        if (!page.links().isEmpty()) {
            out.append("\n\n链接（想点开就把网址交给 webfetch）：");
            for (int i = 0; i < page.links().size(); i++) {
                HtmlText.Link link = page.links().get(i);
                out.append("\n[").append(i + 1).append("] ").append(link.text()).append(" — ").append(link.url());
            }
        }
        if (!page.images().isEmpty()) {
            out.append("\n\n页面上的图片（想看就用 view_image 填网址）：");
            for (HtmlText.Img img : page.images()) {
                out.append("\n- ").append(img.alt().isBlank() ? "（无说明）" : img.alt()).append(" — ").append(img.url());
            }
        }
    }

    private static Charset charset(String type, byte[] body) {
        String name = null;
        int at = type.indexOf("charset=");
        if (at >= 0) {
            name = type.substring(at + 8).replaceAll("[\"';].*$", "").strip();
        } else {
            Matcher m = META_CHARSET.matcher(new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1));
            if (m.find()) {
                name = m.group(1);
            }
        }
        try {
            return name == null || name.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String accept(String format) {
        return format.equals("html") ? "text/html;q=1.0, application/xhtml+xml;q=0.9, */*;q=0.1"
                : "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, text/markdown;q=0.8, */*;q=0.1";
    }
}
