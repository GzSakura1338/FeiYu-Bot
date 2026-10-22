package dafeiyu.plugin.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.plugin.PluginHost;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

abstract class McpClient implements AutoCloseable {

    static final String PROTOCOL = "2025-06-18";
    static final Set<String> SUPPORTED = Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    static final long INIT_TIMEOUT_MS = 30_000;

    protected volatile String serverName = "";
    protected volatile String negotiated = PROTOCOL;

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
        notification("notifications/initialized", new JsonObject());
    }

    List<JsonObject> listTools() throws Exception {
        List<JsonObject> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            JsonObject params = new JsonObject();
            if (cursor != null) {
                params.addProperty("cursor", cursor);
            }
            JsonElement result = request("tools/list", params, INIT_TIMEOUT_MS);
            JsonObject r = result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
            JsonArray list = r.has("tools") && r.get("tools").isJsonArray() ? r.getAsJsonArray("tools") : new JsonArray();
            list.forEach(e -> {
                if (e.isJsonObject()) {
                    tools.add(e.getAsJsonObject());
                }
            });
            cursor = r.has("nextCursor") && r.get("nextCursor").isJsonPrimitive() ? r.get("nextCursor").getAsString() : null;
            if (cursor == null || cursor.isEmpty()) {
                break;
            }
        }
        return tools;
    }

    JsonElement callTool(String name, JsonObject arguments, long timeoutMs) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments);
        return request("tools/call", params, timeoutMs);
    }
}
