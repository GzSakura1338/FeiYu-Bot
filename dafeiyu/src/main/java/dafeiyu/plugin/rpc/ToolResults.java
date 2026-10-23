package dafeiyu.plugin.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.tool.ToolResult;
import dafeiyu.util.Jsons;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class ToolResults {

    private static final int MAX_IMAGES = 4;

    private ToolResults() {}

    public static ToolResult outcome(JsonElement r) {
        if (r == null || r.isJsonNull()) {
            return ToolResult.text("(no output)");
        }
        if (!r.isJsonObject()) {
            return ToolResult.text(r.isJsonPrimitive() ? r.getAsString() : r.toString());
        }
        JsonObject o = r.getAsJsonObject();
        boolean error = o.has("isError") && o.get("isError").isJsonPrimitive() && o.get("isError").getAsBoolean();
        List<ImagePart> images = new ArrayList<>();
        String text = o.has("content") ? parts(o.get("content"), images) : "";
        if (text.isBlank() && o.has("structuredContent")) {
            text = o.get("structuredContent").toString();
        }
        if (text.isBlank() && images.isEmpty() && !o.has("content")) {
            text = o.toString();
        }
        return new ToolResult(text.isBlank() ? "(no output)" : text, error, images);
    }

    public static String parts(JsonElement content, List<ImagePart> images) {
        if (content == null || content.isJsonNull()) {
            return "";
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
            String line = switch (Jsons.optString(p, "type")) {
                case "text" -> Jsons.optString(p, "text");
                case "image" -> image(Jsons.optString(p, "data"), Jsons.optString(p, "mimeType"), "图片", images);
                case "audio" -> "[音频 " + Jsons.optString(p, "mimeType") + "，约 " + kb(Jsons.optString(p, "data")) + " KB，听不了]";
                case "resource_link" -> "[资源链接「" + label(p) + "」" + Jsons.optString(p, "uri") + mime(p)
                        + (Jsons.optString(p, "description").isEmpty() ? "" : "：" + Jsons.optString(p, "description")) + "，可以用 mcp_read_resource 读]";
                case "resource" -> resource(Jsons.optObject(p, "resource"), images);
                default -> p.has("text") ? Jsons.optString(p, "text") : part.toString();
            };
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }

    public static String resource(JsonObject res, List<ImagePart> images) {
        if (res == null) {
            return "";
        }
        String uri = Jsons.optString(res, "uri");
        if (res.has("text")) {
            return "【资源 " + uri + mime(res) + "】\n" + Jsons.optString(res, "text");
        }
        String blob = Jsons.optString(res, "blob");
        if (Jsons.optString(res, "mimeType").startsWith("image/")) {
            return image(blob, Jsons.optString(res, "mimeType"), "资源 " + uri + " 是图片", images);
        }
        return "[资源 " + uri + mime(res) + "：二进制内容，约 " + kb(blob) + " KB，读不出文字]";
    }

    private static String image(String data, String mime, String what, List<ImagePart> images) {
        if (images.size() >= MAX_IMAGES) {
            return "[" + what + " " + mime + "，这次给你看的图已经够多了，没附上]";
        }
        try {
            images.add(ImageLoader.fromBytes(Base64.getDecoder().decode(data)));
            return "[" + what + " " + mime + "，已经附给你看]";
        } catch (AgentException | IllegalArgumentException e) {
            return "[" + what + " " + mime + "，打不开：" + e.getMessage() + "]";
        }
    }

    private static String label(JsonObject p) {
        String title = Jsons.optString(p, "title");
        return title.isEmpty() ? Jsons.optString(p, "name") : title;
    }

    private static String mime(JsonObject p) {
        String mime = Jsons.optString(p, "mimeType");
        return mime.isEmpty() ? "" : "（" + mime + "）";
    }

    private static long kb(String base64) {
        return Math.max(1, base64.length() * 3L / 4 / 1024);
    }
}
