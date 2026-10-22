package dafeiyu.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class Jsons {

    private Jsons() {}

    public static String optString(JsonObject json, String key) {
        JsonElement e = json == null ? null : json.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    public static JsonObject optObject(JsonObject json, String key) {
        JsonElement e = json == null ? null : json.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonObject parseObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
