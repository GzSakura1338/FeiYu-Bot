package dafeiyu.agent.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.util.Jsons;
import dafeiyu.util.Texts;

public final class Args {

    private static final String QQ = "\\d{5,12}";

    private final JsonObject json;

    public Args(JsonObject json) {
        this.json = json == null ? new JsonObject() : json;
    }

    public static Args parse(String arguments) throws ToolFailure {
        JsonObject json = Jsons.parseObject(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (json == null) {
            throw new ToolFailure("Invalid arguments: expected a JSON object, got " + Texts.abbreviate(arguments, 200));
        }
        return new Args(json);
    }

    public static JsonObject schema(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    public JsonObject json() {
        return json;
    }

    public boolean has(String key) {
        JsonElement e = json.get(key);
        return e != null && !e.isJsonNull();
    }

    public JsonElement get(String key) {
        return has(key) ? json.get(key) : null;
    }

    public String string(String key) throws ToolFailure {
        String value = optString(key);
        if (value == null) {
            throw new ToolFailure("Missing required parameter: " + key);
        }
        return value;
    }

    public String optString(String key) {
        JsonElement e = get(key);
        if (e == null) {
            return null;
        }
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public String text(String key) {
        String value = optString(key);
        return value == null ? "" : value.strip();
    }

    public Integer optInt(String key) throws ToolFailure {
        String value = optString(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double number = Double.parseDouble(value.strip());
            if (number != Math.rint(number) || number < 0 || number > Integer.MAX_VALUE) {
                throw new NumberFormatException();
            }
            return (int) number;
        } catch (NumberFormatException e) {
            throw new ToolFailure("Parameter " + key + " must be a non-negative integer, got: " + value);
        }
    }

    public int integer(String key, int fallback) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number(key, fallback)));
    }

    public long number(String key, long fallback) {
        String value = text(key);
        try {
            return value.isEmpty() ? fallback : Math.round(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean bool(String key, boolean fallback) {
        String value = optString(key);
        return value == null ? fallback : value.strip().equalsIgnoreCase("true");
    }

    public String qq(String key) throws ToolFailure {
        String id = text(key).replace("user:", "").strip();
        if (!id.matches(QQ)) {
            throw new ToolFailure("QQ 号不对：" + id + "，不知道就先用 find_person 找");
        }
        return id;
    }

    public String optQq(String key) throws ToolFailure {
        return text(key).replace("user:", "").isBlank() ? "" : qq(key);
    }

    public String group(String key) throws ToolFailure {
        String id = text(key).replace("group:", "").strip();
        if (!id.matches(QQ)) {
            throw new ToolFailure("群号不对：" + id);
        }
        return id;
    }
}
