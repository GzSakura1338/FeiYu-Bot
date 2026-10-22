package dafeiyu.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebSearch {

    public record Hit(String title, String url, String snippet) {}

    public record Results(String engine, List<Hit> hits) {}

    public static final int DEFAULT_RESULTS = 8;
    public static final int MAX_RESULTS = 15;
    private static final int SNIPPET_CHARS = 1200;
    private static final Duration TIMEOUT = Duration.ofSeconds(25);
    private static final String EXA = "https://mcp.exa.ai/mcp";
    private static final String BING = "https://cn.bing.com/search?ensearch=0&q=";
    private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");
    private static final Pattern BING_ITEM = Pattern.compile("<li class=\"b_algo\"(.*?)</li>", Pattern.DOTALL);
    private static final Pattern BING_LINK = Pattern.compile("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern BING_TEXT = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern EXA_FIELD = Pattern.compile("(?m)^(Title|URL|Published|Author|Highlights|Text):\\s?(.*)$");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private WebSearch() {}

    public static Results search(String query, int count) throws ToolFailure {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            throw new ToolFailure("query is empty");
        }
        int n = Math.max(1, Math.min(MAX_RESULTS, count));
        boolean chinese = HAN.matcher(q).find();
        List<String> errors = new ArrayList<>();
        for (String engine : chinese ? List.of("bing", "exa") : List.of("exa", "bing")) {
            try {
                List<Hit> hits = engine.equals("bing") ? bing(q, n) : exa(q, n);
                if (!hits.isEmpty()) {
                    return new Results(engine, hits);
                }
                errors.add(engine + ": no results");
            } catch (IOException | RuntimeException e) {
                errors.add(engine + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolFailure("search was interrupted");
            }
        }
        throw new ToolFailure("Search failed (" + String.join("; ", errors) + ")");
    }

    public static String render(String query, Results results) {
        StringBuilder out = new StringBuilder("Search results for \"" + query + "\" (" + results.hits().size() + " results):\n");
        int i = 1;
        for (Hit hit : results.hits()) {
            out.append('\n').append(i++).append(". ").append(hit.title().isBlank() ? hit.url() : hit.title()).append('\n')
                    .append("   ").append(hit.url()).append('\n');
            if (!hit.snippet().isBlank()) {
                out.append("   ").append(hit.snippet().replace("\n", "\n   ")).append('\n');
            }
        }
        return out.toString().strip();
    }

    static List<Hit> bing(String query, int count) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BING + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&count=" + count))
                .timeout(TIMEOUT)
                .header("User-Agent", BROWSER)
                .header("Accept", "text/html")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET().build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return parseBing(response.body(), count);
    }

    static List<Hit> parseBing(String html, int count) {
        List<Hit> hits = new ArrayList<>();
        Matcher item = BING_ITEM.matcher(html);
        while (item.find() && hits.size() < count) {
            Matcher link = BING_LINK.matcher(item.group(1));
            if (!link.find()) {
                continue;
            }
            Matcher text = BING_TEXT.matcher(item.group(1));
            String snippet = text.find() ? plain(text.group(1)) : "";
            hits.add(new Hit(plain(link.group(2)), HtmlText.decode(link.group(1)), snippet.replaceFirst("\\s*阅读更多$", "")));
        }
        return hits;
    }

    static List<Hit> exa(String query, int count) throws IOException, InterruptedException {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("query", query);
        arguments.addProperty("numResults", count);
        arguments.addProperty("type", "auto");
        arguments.addProperty("livecrawl", "fallback");
        arguments.addProperty("contextMaxCharacters", SNIPPET_CHARS * count);
        JsonObject params = new JsonObject();
        params.addProperty("name", "web_search_exa");
        params.add("arguments", arguments);
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", "tools/call");
        body.add("params", params);
        HttpRequest request = HttpRequest.newBuilder(URI.create(EXA))
                .timeout(TIMEOUT)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return parseExa(response.body(), count);
    }

    static List<Hit> parseExa(String raw, int count) {
        StringBuilder text = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            JsonElement json = JsonParser.parseString(line.substring(5).strip());
            JsonObject result = json.isJsonObject() && json.getAsJsonObject().has("result") ? json.getAsJsonObject().getAsJsonObject("result") : null;
            JsonArray content = result != null && result.has("content") ? result.getAsJsonArray("content") : new JsonArray();
            for (JsonElement part : content) {
                JsonObject p = part.getAsJsonObject();
                if (p.has("text")) {
                    text.append(p.get("text").getAsString()).append('\n');
                }
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (String block : text.toString().split("(?m)(?=^Title: )")) {
            if (hits.size() >= count || !block.startsWith("Title: ")) {
                continue;
            }
            String title = "";
            String url = "";
            String published = "";
            StringBuilder body = new StringBuilder();
            boolean inBody = false;
            for (String line : block.split("\n")) {
                Matcher field = EXA_FIELD.matcher(line);
                if (!inBody && field.matches()) {
                    switch (field.group(1)) {
                        case "Title" -> title = field.group(2).strip();
                        case "URL" -> url = field.group(2).strip();
                        case "Published" -> published = field.group(2).strip();
                        default -> {
                            inBody = true;
                            body.append(field.group(2)).append('\n');
                        }
                    }
                } else if (inBody) {
                    body.append(line).append('\n');
                }
            }
            String snippet = body.toString().replaceAll("\n{2,}", "\n").strip();
            snippet = snippet.length() > SNIPPET_CHARS ? snippet.substring(0, SNIPPET_CHARS) + "…" : snippet;
            String date = published.isEmpty() || published.equals("N/A") ? "" : "Published " + published.replaceFirst("T.*", "") + "\n";
            if (!url.isEmpty()) {
                hits.add(new Hit(title, url, date + snippet));
            }
        }
        return hits;
    }

    private static String plain(String html) {
        return HtmlText.decode(html.replaceAll("<[^>]+>", "")).replaceAll("\\s+", " ").strip();
    }
}
