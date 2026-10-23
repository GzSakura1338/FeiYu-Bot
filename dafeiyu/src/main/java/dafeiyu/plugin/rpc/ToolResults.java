package dafeiyu.plugin.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.tool.ToolResult;
import dafeiyu.util.Jsons;

public final class ToolResults {

    private ToolResults() {}

    public static ToolResult outcome(JsonElement r) {
        String text;
        boolean error = false;
        if (r == null || r.isJsonNull()) {
            text = "";
        } else if (r.isJsonPrimitive()) {
            text = r.getAsString();
        } else if (r.isJsonObject()) {
            JsonObject o = r.getAsJsonObject();
            error = o.has("isError") && o.get("isError").isJsonPrimitive() && o.get("isError").getAsBoolean();
            text = content(o.get("content"), o);
        } else {
            text = r.toString();
        }
        return new ToolResult(text.isEmpty() ? "(no output)" : text, error);
    }

    private static String content(JsonElement content, JsonObject whole) {
        if (content == null) {
            return whole.toString();
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return content.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            JsonObject p = part.isJsonObject() ? part.getAsJsonObject() : new JsonObject();
            String type = Jsons.optString(p, "type");
            if (p.has("text") && p.get("text").isJsonPrimitive()) {
                sb.append(p.get("text").getAsString()).append('\n');
            } else if (type.equals("image")) {
                sb.append("[image ").append(Jsons.optString(p, "mimeType")).append(", not shown to text-only models]\n");
            } else if (type.equals("resource") && p.has("resource") && p.get("resource").isJsonObject()) {
                JsonObject res = p.getAsJsonObject("resource");
                sb.append(res.has("text") ? res.get("text").getAsString() : "[resource " + Jsons.optString(res, "uri") + "]").append('\n');
            } else {
                sb.append(part).append('\n');
            }
        }
        return sb.toString().strip();
    }
}
