package dafeiyu.config;

public record WsProfile(String id, String name, String host, int port, String path, String token) {

    public WsProfile {
        name = name == null ? "" : name.trim();
    }

    public WsSettings settings() {
        return new WsSettings(host, port, path, token);
    }

    public WsProfile withId(String newId) {
        return new WsProfile(newId, name, host, port, path, token);
    }

    public String validate() {
        if (name.isEmpty()) return "名称不能为空";
        if (name.length() > 30) return "名称不能超过 30 个字";
        return settings().validate();
    }
}
