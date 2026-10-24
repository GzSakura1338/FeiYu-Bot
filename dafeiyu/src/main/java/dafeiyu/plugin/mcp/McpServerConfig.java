package dafeiyu.plugin.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.plugin.store.Secrets;
import dafeiyu.plugin.store.PluginStore;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static dafeiyu.util.Jsons.optString;

public record McpServerConfig(String name, String type, String command, List<String> args, Map<String, Secret> env, String url,
                              Map<String, Secret> headers, boolean enabled, boolean sandbox, boolean network, int timeout,
                              boolean qq, boolean mind) {

    public record Secret(String value, boolean secret) {}

    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,23}");
    public static final int MAX_TIMEOUT = 600;

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!NAME.matcher(name).matches()) {
            problems.add("名字只能用小写字母、数字、- 和 _，1 到 24 个字符");
        }
        if (type.equals("stdio")) {
            if (command.isBlank()) {
                problems.add("stdio 类型要填启动命令");
            }
        } else if (type.equals("http")) {
            try {
                URI uri = URI.create(url);
                if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                    problems.add("网址要以 http:// 或 https:// 开头");
                }
            } catch (IllegalArgumentException e) {
                problems.add("网址格式不对");
            }
        } else {
            problems.add("类型只能是 stdio 或 http");
        }
        if (timeout < 1 || timeout > MAX_TIMEOUT) {
            problems.add("超时要在 1 到 " + MAX_TIMEOUT + " 秒之间");
        }
        return problems;
    }

    public JsonObject toStored() {
        JsonObject o = base();
        o.add("env", secrets(env, true));
        o.add("headers", secrets(headers, true));
        return o;
    }

    public JsonObject toPublic() {
        JsonObject o = base();
        o.add("env", secrets(env, false));
        o.add("headers", secrets(headers, false));
        return o;
    }

    private JsonObject base() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("type", type);
        o.addProperty("command", command);
        JsonArray a = new JsonArray();
        args.forEach(a::add);
        o.add("args", a);
        o.addProperty("url", url);
        o.addProperty("enabled", enabled);
        o.addProperty("sandbox", sandbox);
        o.addProperty("network", network);
        o.addProperty("timeout", timeout);
        o.addProperty("qq", qq);
        o.addProperty("mind", mind);
        return o;
    }

    private static JsonArray secrets(Map<String, Secret> map, boolean stored) {
        JsonArray out = new JsonArray();
        map.forEach((k, s) -> {
            JsonObject e = new JsonObject();
            e.addProperty("key", k);
            e.addProperty("secret", s.secret());
            if (!s.secret()) {
                e.addProperty("value", s.value());
            } else if (stored) {
                e.addProperty("value", s.value().isEmpty() ? "" : Secrets.encrypt(s.value()));
            } else {
                e.addProperty("value", s.value().isEmpty() ? "" : PluginStore.KEEP_SECRET);
            }
            out.add(e);
        });
        return out;
    }

    public static McpServerConfig fromStored(JsonObject o) {
        return parse(o, null, true);
    }

    public static McpServerConfig fromRequest(JsonObject o, McpServerConfig old) {
        return parse(o, old, false);
    }

    private static McpServerConfig parse(JsonObject o, McpServerConfig old, boolean stored) {
        List<String> args = new ArrayList<>();
        if (o.has("args") && o.get("args").isJsonArray()) {
            o.getAsJsonArray("args").forEach(e -> args.add(e.getAsString()));
        }
        return new McpServerConfig(optString(o, "name").strip(), optString(o, "type").isEmpty() ? "stdio" : optString(o, "type"),
                optString(o, "command").strip(), List.copyOf(args), entries(o.get("env"), old == null ? Map.of() : old.env(), stored),
                optString(o, "url").strip(), entries(o.get("headers"), old == null ? Map.of() : old.headers(), stored), bool(o, "enabled", true),
                bool(o, "sandbox", true), bool(o, "network", false), o.has("timeout") ? o.get("timeout").getAsInt() : 60, bool(o, "qq", false),
                bool(o, "mind", false));
    }

    private static Map<String, Secret> entries(JsonElement raw, Map<String, Secret> old, boolean stored) {
        Map<String, Secret> out = new LinkedHashMap<>();
        if (raw == null || !raw.isJsonArray()) {
            return out;
        }
        for (JsonElement e : raw.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject item = e.getAsJsonObject();
            String key = optString(item, "key").strip();
            if (key.isEmpty()) {
                continue;
            }
            boolean secret = bool(item, "secret", false);
            String value = optString(item, "value");
            if (stored && secret && Secrets.sealed(value)) {
                try {
                    value = Secrets.decrypt(value);
                } catch (IllegalStateException ex) {
                    value = "";
                }
            } else if (!stored && secret && value.equals(PluginStore.KEEP_SECRET)) {
                Secret before = old.get(key);
                value = before == null ? "" : before.value();
            }
            out.put(key, new Secret(value, secret));
        }
        return out;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
    }
}
