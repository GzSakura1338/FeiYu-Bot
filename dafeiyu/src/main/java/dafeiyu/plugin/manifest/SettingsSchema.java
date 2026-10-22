package dafeiyu.plugin.manifest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SettingsSchema {

    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "array");

    private SettingsSchema() {}

    public static List<String> check(JsonObject schema) {
        List<String> problems = new ArrayList<>();
        if (schema.has("type") && !"object".equals(text(schema.get("type")))) {
            problems.add("settings 的 type 只能是 object");
        }
        for (Map.Entry<String, JsonObject> e : properties(schema).entrySet()) {
            String key = e.getKey();
            JsonObject p = e.getValue();
            String type = p.has("type") ? text(p.get("type")) : "string";
            if (!TYPES.contains(type)) {
                problems.add("设置项「" + key + "」的类型「" + type + "」不支持（只支持 string、number、integer、boolean、array）");
            }
            if (bool(p, "secret") && !type.equals("string")) {
                problems.add("设置项「" + key + "」只有字符串才能标 secret");
            }
            if (p.has("enum") && !p.get("enum").isJsonArray()) {
                problems.add("设置项「" + key + "」的 enum 必须是数组");
            }
            if (p.has("pattern")) {
                try {
                    Pattern.compile(text(p.get("pattern")));
                } catch (PatternSyntaxException ex) {
                    problems.add("设置项「" + key + "」的 pattern 不是合法的正则");
                }
            }
        }
        return problems;
    }

    public static Map<String, JsonObject> properties(JsonObject schema) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        if (schema.has("properties") && schema.get("properties").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : schema.getAsJsonObject("properties").entrySet()) {
                if (e.getValue().isJsonObject()) {
                    out.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
        }
        return out;
    }

    public static Set<String> secrets(JsonObject schema) {
        Set<String> out = new LinkedHashSet<>();
        properties(schema).forEach((k, p) -> {
            if (bool(p, "secret")) {
                out.add(k);
            }
        });
        return out;
    }

    public static JsonObject withDefaults(JsonObject schema, JsonObject values) {
        JsonObject out = values == null ? new JsonObject() : values.deepCopy();
        properties(schema).forEach((k, p) -> {
            if (!out.has(k) && p.has("default")) {
                out.add(k, p.get("default").deepCopy());
            }
        });
        return out;
    }

    public static List<String> validate(JsonObject schema, JsonObject values) {
        List<String> problems = new ArrayList<>();
        Map<String, JsonObject> props = properties(schema);
        for (String key : values.keySet()) {
            if (!props.containsKey(key)) {
                problems.add("没有设置项「" + key + "」");
            }
        }
        Set<String> required = new LinkedHashSet<>();
        if (schema.has("required") && schema.get("required").isJsonArray()) {
            schema.getAsJsonArray("required").forEach(e -> required.add(text(e)));
        }
        for (Map.Entry<String, JsonObject> e : props.entrySet()) {
            String key = e.getKey();
            JsonObject p = e.getValue();
            String title = p.has("title") ? text(p.get("title")) : key;
            JsonElement v = values.get(key);
            if (v == null || v.isJsonNull() || (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && v.getAsString().isEmpty())) {
                if (required.contains(key)) {
                    problems.add("「" + title + "」必须填");
                }
                continue;
            }
            String type = p.has("type") ? text(p.get("type")) : "string";
            switch (type) {
                case "string" -> {
                    if (!isString(v)) {
                        problems.add("「" + title + "」要填文字");
                        continue;
                    }
                    String s = v.getAsString();
                    if (p.has("minLength") && s.length() < p.get("minLength").getAsInt()) {
                        problems.add("「" + title + "」至少 " + p.get("minLength").getAsInt() + " 个字");
                    }
                    if (p.has("maxLength") && s.length() > p.get("maxLength").getAsInt()) {
                        problems.add("「" + title + "」最多 " + p.get("maxLength").getAsInt() + " 个字");
                    }
                    if (p.has("pattern") && !Pattern.compile(text(p.get("pattern"))).matcher(s).matches()) {
                        problems.add("「" + title + "」格式不对");
                    }
                }
                case "number", "integer" -> {
                    if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                        problems.add("「" + title + "」要填数字");
                        continue;
                    }
                    double d = v.getAsDouble();
                    if (type.equals("integer") && d != Math.rint(d)) {
                        problems.add("「" + title + "」要填整数");
                    }
                    if (p.has("minimum") && d < p.get("minimum").getAsDouble()) {
                        problems.add("「" + title + "」不能小于 " + num(p.get("minimum")));
                    }
                    if (p.has("maximum") && d > p.get("maximum").getAsDouble()) {
                        problems.add("「" + title + "」不能大于 " + num(p.get("maximum")));
                    }
                }
                case "boolean" -> {
                    if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
                        problems.add("「" + title + "」只能是开或关");
                    }
                }
                case "array" -> {
                    if (!v.isJsonArray()) {
                        problems.add("「" + title + "」要是一个列表");
                        continue;
                    }
                    String item = p.has("items") && p.get("items").isJsonObject() && p.getAsJsonObject("items").has("type")
                            ? text(p.getAsJsonObject("items").get("type")) : "string";
                    for (JsonElement x : v.getAsJsonArray()) {
                        boolean ok = item.equals("string") ? isString(x) : x.isJsonPrimitive() && x.getAsJsonPrimitive().isNumber();
                        if (!ok) {
                            problems.add("「" + title + "」列表里有不对的值");
                            break;
                        }
                    }
                }
                default -> { }
            }
            if (p.has("enum") && p.get("enum").isJsonArray() && !contains(p.getAsJsonArray("enum"), v)) {
                problems.add("「" + title + "」只能选列出来的值");
            }
        }
        return problems;
    }

    private static boolean contains(JsonArray options, JsonElement v) {
        for (JsonElement o : options) {
            if (o.equals(v) || (o.isJsonPrimitive() && v.isJsonPrimitive() && o.getAsString().equals(v.getAsString()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isString(JsonElement v) {
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
    }

    private static String num(JsonElement e) {
        double d = e.getAsDouble();
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    static boolean bool(JsonObject p, String key) {
        return p.has(key) && p.get(key).isJsonPrimitive() && p.get(key).getAsJsonPrimitive().isBoolean() && p.get(key).getAsBoolean();
    }

    private static String text(JsonElement e) {
        return e instanceof JsonPrimitive p ? p.getAsString() : String.valueOf(e);
    }
}
