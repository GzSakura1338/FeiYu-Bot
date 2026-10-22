package dafeiyu.config;

import java.net.InetSocketAddress;

public record WsSettings(String host, int port, String path, String token) {

    public WsSettings {
        host = host == null ? "" : host.trim();
        path = path == null ? "" : path.trim();
        token = token == null ? "" : token.trim();
    }

    public String validate() {
        if (host.isEmpty()) return "监听地址不能为空";
        if (!host.matches("[A-Za-z0-9.\\-:\\[\\]]+")) return "监听地址格式不对";
        if (port < 1 || port > 65535) return "端口必须在 1～65535 之间";
        if (!path.startsWith("/")) return "路径必须以 / 开头";
        if (path.contains(" ") || path.contains("?") || path.contains("#")) return "路径不能包含空格、? 或 #";
        if (token.contains(" ")) return "token 不能包含空格";
        return null;
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(host, port);
    }

    public String url() {
        return "ws://" + host + ":" + port + path;
    }
}
