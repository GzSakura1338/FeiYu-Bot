package dafeiyu.plugin.manifest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

public final class ArgsCheck {

    private ArgsCheck() {}

    public static String check(JsonObject schema, JsonObject args) {
        JsonObject properties = schema.has("properties") && schema.get("properties").isJsonObject() ? schema.getAsJsonObject("properties") : new JsonObject();
        if (schema.has("required") && schema.get("required").isJsonArray()) {
            for (JsonElement r : schema.getAsJsonArray("required")) {
                String key = r.getAsString();
                if (!args.has(key) || args.get(key).isJsonNull()) {
                    return "missing required argument \"" + key + "\"";
                }
            }
        }
        for (Map.Entry<String, JsonElement> e : args.entrySet()) {
            JsonElement p = properties.get(e.getKey());
            if (p == null || !p.isJsonObject() || !p.getAsJsonObject().has("type")) {
                continue;
            }
            String type = p.getAsJsonObject().get("type").isJsonPrimitive() ? p.getAsJsonObject().get("type").getAsString() : "";
            if (!matches(type, e.getValue())) {
                return "argument \"" + e.getKey() + "\" should be " + type;
            }
        }
        if (schema.has("additionalProperties") && schema.get("additionalProperties").isJsonPrimitive()
                && !schema.get("additionalProperties").getAsBoolean()) {
            for (String key : args.keySet()) {
                if (!properties.has(key)) {
                    return "unknown argument \"" + key + "\"";
                }
            }
        }
        return null;
    }

    private static boolean matches(String type, JsonElement v) {
        if (v.isJsonNull()) {
            return true;
        }
        return switch (type) {
            case "string" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
            case "number" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
            case "integer" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsDouble() == Math.rint(v.getAsDouble());
            case "boolean" -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
            case "array" -> v.isJsonArray();
            case "object" -> v.isJsonObject();
            default -> true;
        };
    }
}
