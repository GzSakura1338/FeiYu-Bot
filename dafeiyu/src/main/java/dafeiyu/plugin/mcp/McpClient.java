package dafeiyu.plugin.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.plugin.PluginHost;
import dafeiyu.util.Jsons;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

abstract class McpClient implements AutoCloseable {

    static final String PROTOCOL = "2025-06-18";
    static final Set<String> SUPPORTED = Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    static final long INIT_TIMEOUT_MS = 30_000;
    static final String TOOLS = "tools";
    static final String RESOURCES = "resources";
    static final String PROMPTS = "prompts";
    private static final int MAX_PAGES = 20;

    protected volatile String serverName = "";
    protected volatile String negotiated = PROTOCOL;
    protected volatile JsonObject capabilities = new JsonObject();

    abstract JsonElement request(String method, JsonObject params, long timeoutMs) throws Exception;

    abstract void notification(String method, JsonObject params) throws Exception;

    abstract boolean alive();

    @Override
    public abstract void close();

    void initialize() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL);
        params.add("capabilities", new JsonObject());
        JsonObject client = new JsonObject();
        client.addProperty("name", "FeiYu Bot");
        client.addProperty("version", PluginHost.VERSION);
        params.add("clientInfo", client);
        JsonElement result = request("initialize", params, INIT_TIMEOUT_MS);
        JsonObject r = result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
        String version = r.has("protocolVersion") ? r.get("protocolVersion").getAsString() : "";
        if (!SUPPORTED.contains(version)) {
            throw new IllegalStateException("protocol version not supported: " + (version.isEmpty() ? "(none)" : version));
        }
        negotiated = version;
        if (r.has("serverInfo") && r.get("serverInfo").isJsonObject() && r.getAsJsonObject("serverInfo").has("name")) {
            serverName = r.getAsJsonObject("serverInfo").get("name").getAsString();
        }
        JsonObject declared = Jsons.optObject(r, "capabilities");
        capabilities = declared == null ? new JsonObject() : declared;
        notification("notifications/initialized", new JsonObject());
    }

    boolean supports(String feature) {
        return capabilities.has(feature);
    }

    List<JsonObject> listTools() throws Exception {
        if (supports(TOOLS)) {
            return list("tools/list", "tools");
        }
        try {
            return list("tools/list", "tools");
        } catch (Exception e) {
            return List.of();
        }
    }

    List<JsonObject> listResources() throws Exception {
        return supports(RESOURCES) ? list("resources/list", "resources") : List.of();
    }

    List<JsonObject> listResourceTemplates() throws Exception {
        return supports(RESOURCES) ? list("resources/templates/list", "resourceTemplates") : List.of();
    }

    List<JsonObject> listPrompts() throws Exception {
        return supports(PROMPTS) ? list("prompts/list", "prompts") : List.of();
    }

    private List<JsonObject> list(String method, String field) throws Exception {
        List<JsonObject> items = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonObject params = new JsonObject();
            if (cursor != null) {
                params.addProperty("cursor", cursor);
            }
            JsonElement result = request(method, params, INIT_TIMEOUT_MS);
            JsonObject r = result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
            JsonArray list = r.has(field) && r.get(field).isJsonArray() ? r.getAsJsonArray(field) : new JsonArray();
            list.forEach(e -> {
                if (e.isJsonObject()) {
                    items.add(e.getAsJsonObject());
                }
            });
            cursor = r.has("nextCursor") && r.get("nextCursor").isJsonPrimitive() ? r.get("nextCursor").getAsString() : null;
            if (cursor == null || cursor.isEmpty()) {
                break;
            }
        }
        return items;
    }

    JsonElement callTool(String name, JsonObject arguments, long timeoutMs) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments);
        return request("tools/call", params, timeoutMs);
    }

    JsonElement readResource(String uri, long timeoutMs) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        return request("resources/read", params, timeoutMs);
    }

    JsonElement getPrompt(String name, JsonObject arguments, long timeoutMs) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments);
        return request("prompts/get", params, timeoutMs);
    }

    void cancelled(long requestId) {
        JsonObject params = new JsonObject();
        params.addProperty("requestId", requestId);
        params.addProperty("reason", "timed out");
        try {
            notification("notifications/cancelled", params);
        } catch (Exception ignored) {
        }
    }

    static String logLine(JsonObject params) {
        String level = Jsons.optString(params, "level");
        String logger = Jsons.optString(params, "logger");
        JsonElement data = params == null ? null : params.get("data");
        String text = data == null ? "" : data.isJsonPrimitive() ? data.getAsString() : data.toString();
        return "log " + (level.isEmpty() ? "info" : level) + (logger.isEmpty() ? "" : " [" + logger + "]") + ": " + text;
    }
}
