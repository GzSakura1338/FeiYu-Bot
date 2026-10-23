package dafeiyu.agent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PublicWeb {

    public record Response(URI uri, int status, String type, byte[] body) {}

    static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final int MAX_REDIRECTS = 5;
    private static final long PROXY_RECHECK_MS = 10 * 60_000;
    private static final Pattern PROXY_SERVER = Pattern.compile("(?:https=)?([\\w.-]+):(\\d{2,5})");
    private static final HttpClient DIRECT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Set<String> PROXIED_HOSTS = ConcurrentHashMap.newKeySet();
    private static final ExecutorService READERS = Executors.newVirtualThreadPerTaskExecutor();
    private static volatile HttpClient proxied;
    private static volatile long proxyCheckedAt;

    private PublicWeb() {}

    public static byte[] download(String url, int maxBytes) throws ToolFailure {
        Response response = get(url, "image/avif,image/webp,image/*,*/*;q=0.8", maxBytes, Duration.ofSeconds(30));
        if (response.status() >= 400) {
            throw new ToolFailure("下载失败：HTTP " + response.status());
        }
        return response.body();
    }

    static Response get(String url, String accept, int maxBytes, Duration timeout) throws ToolFailure {
        URI uri = parse(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkPublic(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", BROWSER)
                    .header("Accept", accept)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = send(request, uri.getHost());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                close(response);
                if (location == null) {
                    throw new ToolFailure("网页要跳转但没给地址（HTTP " + status + "）");
                }
                uri = parse(uri.resolve(location.strip().replace(" ", "%20")).toString());
                continue;
            }
            String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            return new Response(uri, status, type, read(response, maxBytes, timeout));
        }
        throw new ToolFailure("跳转次数太多");
    }

    private static HttpResponse<InputStream> send(HttpRequest request, String host) throws ToolFailure {
        HttpClient viaProxy = proxied();
        boolean proxyFirst = viaProxy != null && PROXIED_HOSTS.contains(host);
        try {
            return (proxyFirst ? viaProxy : DIRECT).send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            if (proxyFirst || viaProxy == null) {
                throw new ToolFailure("连不上 " + host + "：" + reason(e));
            }
            try {
                HttpResponse<InputStream> response = viaProxy.send(request, HttpResponse.BodyHandlers.ofInputStream());
                PROXIED_HOSTS.add(host);
                return response;
            } catch (IOException again) {
                throw new ToolFailure("连不上 " + host + "：" + reason(again));
            } catch (InterruptedException again) {
                Thread.currentThread().interrupt();
                throw new ToolFailure("请求被取消了");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("请求被取消了");
        }
    }

    private static String reason(IOException e) {
        return e instanceof java.net.http.HttpConnectTimeoutException ? "连接超时" : String.valueOf(e.getMessage());
    }

    private static HttpClient proxied() {
        long now = System.currentTimeMillis();
        if (now - proxyCheckedAt < PROXY_RECHECK_MS) {
            return proxied;
        }
        proxyCheckedAt = now;
        InetSocketAddress address = systemProxy();
        proxied = address == null ? null : HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(address))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return proxied;
    }

    private static InetSocketAddress systemProxy() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        try {
            Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
            p.waitFor(5, TimeUnit.SECONDS);
            if (!Pattern.compile("ProxyEnable\\s+REG_DWORD\\s+0x1\\b").matcher(out).find()) {
                return null;
            }
            Matcher server = Pattern.compile("ProxyServer\\s+REG_SZ\\s+(\\S+)").matcher(out);
            Matcher hostPort = server.find() ? PROXY_SERVER.matcher(server.group(1)) : null;
            if (hostPort == null || !hostPort.find()) {
                return null;
            }
            String host = hostPort.group(1);
            if (!host.equals("127.0.0.1") && !host.equalsIgnoreCase("localhost")) {
                return null;
            }
            return InetSocketAddress.createUnresolved(host, Integer.parseInt(hostPort.group(2)));
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static URI parse(String raw) throws ToolFailure {
        URI uri;
        try {
            uri = URI.create(raw.strip().replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            throw new ToolFailure("网址不对：" + raw);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ToolFailure("网址要以 http:// 或 https:// 开头");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ToolFailure("网址里没有域名：" + raw);
        }
        return uri;
    }

    static void checkPublic(URI uri) throws ToolFailure {
        String host = uri.getHost();
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local") || lower.endsWith(".internal")) {
            throw new ToolFailure("不能访问本机地址：" + host);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (UnknownHostException e) {
            throw new ToolFailure("找不到这个网站：" + host);
        }
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(PublicWeb::internal)) {
            throw new ToolFailure("不能访问本机或局域网地址：" + host);
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

    private static byte[] read(HttpResponse<InputStream> response, int maxBytes, Duration timeout) throws ToolFailure {
        InputStream in = response.body();
        Future<byte[]> reading = READERS.submit(() -> in.readNBytes(maxBytes + 1));
        try {
            byte[] data = reading.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (data.length > maxBytes) {
                throw new ToolFailure("内容太大了（超过 " + maxBytes / 1024 / 1024 + " MB）");
            }
            return data;
        } catch (TimeoutException e) {
            throw new ToolFailure("网页太慢了，" + timeout.toSeconds() + " 秒还没传完");
        } catch (ExecutionException e) {
            throw new ToolFailure("读取失败：" + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("请求被取消了");
        } finally {
            reading.cancel(true);
            close(response);
        }
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
        }
    }
}
