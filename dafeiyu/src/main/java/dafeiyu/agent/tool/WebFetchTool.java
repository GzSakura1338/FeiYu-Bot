package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WebFetchTool implements Tool {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_OUTPUT = 100_000;
    private static final int DEFAULT_TIMEOUT = 30;
    private static final int MAX_TIMEOUT = 120;
    private static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; dafeiyu/1.0";
    private static final Pattern META_CHARSET = Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "url": {"type": "string", "description": "The HTTP or HTTPS URL to fetch content from"},
              "format": {"type": "string", "enum": ["text", "markdown", "html"], "description": "The format to return the content in. Defaults to markdown."},
              "timeout": {"type": "number", "description": "Optional timeout in seconds (maximum: 120)"}
            }, "required": ["url"]}""");

    @Override
    public String name() {
        return "webfetch";
    }

    @Override
    public String description() {
        return "Fetch content from an HTTP or HTTPS URL and return it as text, markdown, or HTML. Markdown is the default.\n\n"
                + "This tool is read-only. Only public internet addresses can be fetched; local and private network addresses are refused. "
                + "Large pages are truncated.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public String run(JsonObject args, ToolContext context) throws ToolFailure {
        String format = Args.optString(args, "format");
        format = format == null || format.isBlank() ? "markdown" : format.strip().toLowerCase(Locale.ROOT);
        if (!format.equals("markdown") && !format.equals("text") && !format.equals("html")) {
            throw new ToolFailure("format must be text, markdown or html");
        }
        Integer timeout = Args.optInt(args, "timeout");
        int seconds = timeout == null || timeout == 0 ? DEFAULT_TIMEOUT : Math.min(timeout, MAX_TIMEOUT);
        URI uri = parse(Args.string(args, "url").strip());
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkPublic(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(seconds))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", accept(format))
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new ToolFailure("Request failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolFailure("Request was cancelled");
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                close(response);
                if (location == null) {
                    throw new ToolFailure("Redirect without a Location header (HTTP " + status + ")");
                }
                uri = parse(uri.resolve(location).toString());
                continue;
            }
            byte[] body = read(response);
            if (status >= 400) {
                throw new ToolFailure("Request failed with status code: " + status);
            }
            String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/") || type.contains("octet-stream")
                    || type.contains("zip") || type.contains("pdf")) {
                return "Fetched " + uri + " (" + type + ", " + body.length + " bytes). This is binary content and cannot be shown as text.";
            }
            String text = new String(body, charset(type, body));
            boolean html = type.contains("html") || (type.isEmpty() && text.stripLeading().startsWith("<"));
            String output = !html || format.equals("html") ? text : format.equals("markdown") ? HtmlText.markdown(text) : HtmlText.text(text);
            if (output.length() > MAX_OUTPUT) {
                output = output.substring(0, MAX_OUTPUT) + "\n\n[Content truncated]";
            }
            return output.isBlank() ? "Fetched " + uri + " but the page has no readable text." : output;
        }
        throw new ToolFailure("Too many redirects");
    }

    private static URI parse(String raw) throws ToolFailure {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new ToolFailure("Invalid URL: " + raw);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ToolFailure("URL must use http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ToolFailure("URL has no host: " + raw);
        }
        return uri;
    }

    private static void checkPublic(URI uri) throws ToolFailure {
        String host = uri.getHost();
        if (host.equalsIgnoreCase("localhost") || host.toLowerCase(Locale.ROOT).endsWith(".localhost")
                || host.toLowerCase(Locale.ROOT).endsWith(".local") || host.toLowerCase(Locale.ROOT).endsWith(".internal")) {
            throw new ToolFailure("Refused: " + host + " is a local address");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (UnknownHostException e) {
            throw new ToolFailure("Unknown host: " + host);
        }
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(WebFetchTool::internal)) {
            throw new ToolFailure("Refused: " + host + " points to a local or private network address");
        }
    }

    private static boolean internal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) {
                return true;
            }
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    return internal(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
            return false;
        }
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 0 || first >= 224 || (first == 100 && second >= 64 && second < 128) || (first == 192 && second == 0 && (b[2] & 0xFF) == 0);
    }

    private static byte[] read(HttpResponse<InputStream> response) throws ToolFailure {
        try (InputStream in = response.body()) {
            byte[] data = in.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw new ToolFailure("Response too large (exceeds 5 MB limit)");
            }
            return data;
        } catch (IOException e) {
            throw new ToolFailure("Failed to read the response: " + e.getMessage());
        }
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
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
        return switch (format) {
            case "text" -> "text/plain;q=1.0, text/markdown;q=0.9, text/html;q=0.8, */*;q=0.1";
            case "html" -> "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, text/markdown;q=0.7, */*;q=0.1";
            default -> "text/markdown;q=1.0, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1";
        };
    }
}
