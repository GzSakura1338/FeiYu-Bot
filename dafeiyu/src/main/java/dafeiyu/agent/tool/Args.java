package dafeiyu.agent.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class Args {

    private Args() {}

    static JsonObject schema(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    static String string(JsonObject args, String key) throws ToolFailure {
        String value = optString(args, key);
        if (value == null) {
            throw new ToolFailure("Missing required parameter: " + key);
        }
        return value;
    }

    static String optString(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    static Integer optInt(JsonObject args, String key) throws ToolFailure {
        String value = optString(args, key);
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

    static boolean optBool(JsonObject args, String key, boolean fallback) {
        String value = optString(args, key);
        return value == null ? fallback : value.strip().equalsIgnoreCase("true");
    }
}
