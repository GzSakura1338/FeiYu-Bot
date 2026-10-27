package dafeiyu.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class OriginGuard implements HttpHandler {

    private final HttpHandler inner;
    private final Set<String> hosts;

    OriginGuard(HttpHandler inner, String boundHost, int boundPort) {
        this.inner = inner;
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add("127.0.0.1:" + boundPort);
        allowed.add("localhost:" + boundPort);
        allowed.add("[::1]:" + boundPort);
        allowed.add("127.0.0.1");
        allowed.add("localhost");
        allowed.add("[::1]");
        if (boundHost != null && !boundHost.isBlank() && !isWildcard(boundHost) && !isLoopback(boundHost)) {
            String bracketed = boundHost.contains(":") ? "[" + boundHost + "]" : boundHost;
            allowed.add(bracketed + ":" + boundPort);
            allowed.add(bracketed);
        }
        this.hosts = Set.copyOf(allowed);
    }

    private static boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1") || host.equals("[::1]");
    }

    private static boolean isWildcard(String host) {
        return host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]") || host.equals("*");
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank() || !hosts.contains(host.strip().toLowerCase(Locale.ROOT))) {
            deny(ex, "控制台只接受本机地址访问（Host: " + host + "）。"
                    + "要从别处访问请用 SSH 隧道：ssh -L " + 8090 + ":127.0.0.1:8090 服务器");
            return;
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank() && !originAllowed(origin)) {
            deny(ex, "拒绝跨站请求（Origin: " + origin + "）。这个接口只接受控制台自己的页面调用");
            return;
        }
        inner.handle(ex);
    }

    private boolean originAllowed(String origin) {
        String lower = origin.strip().toLowerCase(Locale.ROOT);
        for (String h : hosts) {
            if (lower.equals("http://" + h) || lower.equals("https://" + h)) {
                return true;
            }
        }
        return false;
    }

    private void deny(HttpExchange ex, String why) throws IOException {
        byte[] body = why.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(403, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
