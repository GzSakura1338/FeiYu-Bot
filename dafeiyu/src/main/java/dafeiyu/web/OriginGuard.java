package dafeiyu.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 控制台的统一守门人。
 *
 * <p>这个控制台没有任何登录态，安全性原本完全靠"只绑 127.0.0.1"。但只绑回环挡不住
 * <b>DNS rebinding</b>：攻击者把自己控制的域名 A 记录 rebind 到 127.0.0.1，浏览器仍认为
 * 是同源，于是能读配置、以机器人的身份发言、安装插件（插件是真子进程 = 任意代码执行）。
 *
 * <p>挡法就是校验 <b>Host 头</b>：rebinding 时浏览器的 Host 是攻击者的域名，不在白名单里，
 * 直接 403。再补一层 Origin 校验，把跨站表单与跨站脚本也拦掉（正常直连 curl 不带 Origin，
 * 不受影响）。
 *
 * <p>白名单 = 回环地址（SSH 隧道与本机浏览器都走这个）+ 真的绑定的那个地址（用户明确要
 * 对外监听时才用得上）。绑 0.0.0.0 时不会把 0.0.0.0 加进去，因为浏览器不会发这个 Host。
 */
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
