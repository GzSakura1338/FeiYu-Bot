package dafeiyu.agent.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.model.ModelLimits;
import dafeiyu.agent.model.ModelProfile;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;

final class AnthropicProtocol implements ModelProtocol {

    private static final String VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 8_192;
    private static final int OUTPUT_TOKEN_MAX = 32_000;

    @Override
    public ChatResult stream(ModelProfile profile, ChatRequest request, StreamListener listener) throws AgentException {
        JsonArray system = new JsonArray();
        List<JsonObject> turns = new ArrayList<>();
        for (ChatMessage m : request.messages()) {
            if (m.role().equals("system")) {
                system.add(textBlock(m.content()));
                continue;
            }
            String role = m.isAssistant() ? "assistant" : "user";
            JsonObject last = turns.isEmpty() ? null : turns.getLast();
            if (last != null && optString(last, "role").equals(role)) {
                last.getAsJsonArray("content").addAll(blocks(m));
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("role", role);
            item.add("content", blocks(m));
            turns.add(item);
        }
        if (!system.isEmpty()) {
            markCache(system.get(system.size() - 1).getAsJsonObject());
        }
        if (!turns.isEmpty()) {
            JsonArray tail = turns.getLast().getAsJsonArray("content");
            markCache(tail.get(tail.size() - 1).getAsJsonObject());
        }
        JsonArray messages = new JsonArray();
        turns.forEach(messages::add);

        int known = ModelLimits.lookup(profile.model()).output();
        JsonObject body = new JsonObject();
        body.addProperty("model", profile.model());
        body.addProperty("max_tokens", request.maxOutput() > 0 ? request.maxOutput()
                : known > 0 ? Math.min(known, OUTPUT_TOKEN_MAX) : DEFAULT_MAX_TOKENS);
        if (!system.isEmpty()) {
            body.add("system", system);
        }
        body.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec spec : request.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("name", spec.name());
                tool.addProperty("description", spec.description());
                tool.add("input_schema", spec.parameters());
                tools.add(tool);
            }
            body.add("tools", tools);
            JsonObject choice = new JsonObject();
            choice.addProperty("type", request.toolChoiceNone() ? "none" : "auto");
            body.add("tool_choice", choice);
        }
        if (profile.temperature() != null) {
            body.addProperty("temperature", Math.min(profile.temperature(), 1.0));
        }
        body.addProperty("stream", true);

        StreamCollector out = new StreamCollector(listener, profile.model());
        Map<Integer, Integer> toolBlocks = new HashMap<>();
        int[] usage = {-1, -1, 0, 0};
        HttpJson.stream(auth(HttpJson.post(HttpJson.uri(base(profile.baseUrl()), "/messages"), body), profile.apiKey()).build(), event -> {
            JsonObject data = HttpJson.parseObject(event.data());
            if (data == null) {
                return true;
            }
            switch (optString(data, "type")) {
                case "message_start" -> {
                    JsonObject message = optObject(data, "message");
                    if (message != null) {
                        out.model(optString(message, "model"));
                        readUsage(optObject(message, "usage"), usage);
                    }
                }
                case "content_block_start" -> {
                    JsonObject block = optObject(data, "content_block");
                    if (block != null && optString(block, "type").equals("tool_use")) {
                        int slot = out.callCount();
                        StreamCollector.PartialCall call = out.call(slot);
                        call.id = optString(block, "id");
                        call.name = optString(block, "name");
                        toolBlocks.put(HttpJson.intOr(data, "index", -1), slot);
                    }
                }
                case "content_block_delta" -> {
                    JsonObject delta = optObject(data, "delta");
                    if (delta == null) {
                        return true;
                    }
                    switch (optString(delta, "type")) {
                        case "text_delta" -> out.text(optString(delta, "text"));
                        case "thinking_delta" -> out.reasoning(optString(delta, "thinking"));
                        case "input_json_delta" -> {
                            Integer slot = toolBlocks.get(HttpJson.intOr(data, "index", -1));
                            if (slot != null) {
                                out.call(slot).arguments.append(optString(delta, "partial_json"));
                            }
                        }
                        default -> { }
                    }
                }
                case "message_delta" -> {
                    JsonObject delta = optObject(data, "delta");
                    if (delta != null && !optString(delta, "stop_reason").isEmpty()) {
                        out.finish(optString(delta, "stop_reason"));
                    }
                    readUsage(optObject(data, "usage"), usage);
                }
                case "message_stop" -> {
                    out.finish(null);
                    return false;
                }
                case "error" -> throw HttpJson.streamError(data, event.data());
                default -> { }
            }
            return true;
        });
        out.promptTokens = usage[0] < 0 ? -1 : usage[0] + usage[2] + usage[3];
        out.completionTokens = usage[1];
        out.cachedTokens = usage[2];
        return out.result("max_tokens");
    }

    @Override
    public List<String> listModels(String baseUrl, String apiKey) throws AgentException {
        return HttpJson.modelIds(HttpJson.send(auth(HttpJson.get(HttpJson.uri(base(baseUrl), "/models?limit=1000")), apiKey).build()));
    }

    private static void readUsage(JsonObject u, int[] usage) {
        if (u == null) {
            return;
        }
        usage[0] = HttpJson.intOr(u, "input_tokens", usage[0]);
        usage[1] = HttpJson.intOr(u, "output_tokens", usage[1]);
        usage[2] = HttpJson.intOr(u, "cache_read_input_tokens", usage[2]);
        usage[3] = HttpJson.intOr(u, "cache_creation_input_tokens", usage[3]);
    }

    private static JsonArray blocks(ChatMessage m) {
        JsonArray blocks = new JsonArray();
        if (m.isTool()) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "tool_result");
            block.addProperty("tool_use_id", m.toolCallId());
            block.addProperty("content", m.content().isEmpty() ? "(no output)" : m.content());
            if (m.toolError()) {
                block.addProperty("is_error", true);
            }
            blocks.add(block);
            return blocks;
        }
        for (ImagePart image : m.images()) {
            JsonObject source = new JsonObject();
            source.addProperty("type", "base64");
            source.addProperty("media_type", image.mime());
            source.addProperty("data", image.base64());
            JsonObject block = new JsonObject();
            block.addProperty("type", "image");
            block.add("source", source);
            blocks.add(block);
        }
        if (!m.content().isBlank()) {
            blocks.add(textBlock(m.content()));
        }
        for (ToolCall call : m.toolCalls()) {
            JsonObject input = HttpJson.parseObject(call.arguments());
            JsonObject block = new JsonObject();
            block.addProperty("type", "tool_use");
            block.addProperty("id", call.id());
            block.addProperty("name", call.name());
            block.add("input", input == null ? new JsonObject() : input);
            blocks.add(block);
        }
        if (blocks.isEmpty()) {
            blocks.add(textBlock("(empty)"));
        }
        return blocks;
    }

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    private static void markCache(JsonObject block) {
        JsonObject cache = new JsonObject();
        cache.addProperty("type", "ephemeral");
        block.add("cache_control", cache);
    }

    private static String base(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        return url.matches(".*/v\\d+$") ? url : url + "/v1";
    }

    private static HttpRequest.Builder auth(HttpRequest.Builder builder, String apiKey) {
        builder.header("anthropic-version", VERSION);
        return apiKey == null || apiKey.isBlank() ? builder : builder.header("x-api-key", apiKey.trim());
    }
}
