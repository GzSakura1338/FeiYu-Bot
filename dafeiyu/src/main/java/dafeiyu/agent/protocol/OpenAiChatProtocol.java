package dafeiyu.agent.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.model.ModelProfile;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;

final class OpenAiChatProtocol implements ModelProtocol {

    static void effort(JsonObject body, String model, String effort) {
        if (effort.isEmpty()) {
            return;
        }
        if (model.toLowerCase(java.util.Locale.ROOT).contains("deepseek")) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", effort.equals("none") ? "disabled" : "enabled");
            body.add("thinking", thinking);
            if (!effort.equals("none")) {
                body.addProperty("reasoning_effort", effort.equals("medium") ? "high" : effort);
            }
            return;
        }
        body.addProperty("reasoning_effort", effort.equals("max") ? "high" : effort);
    }

    @Override
    public ChatResult stream(ModelProfile profile, ChatRequest request, StreamListener listener) throws AgentException {
        URI uri = HttpJson.uri(HttpJson.withDefaultVersion(profile.baseUrl()), "/chat/completions");
        JsonObject body = new JsonObject();
        body.addProperty("model", profile.model());
        body.add("messages", messages(request, requiresReasoning(profile)));
        if (!request.tools().isEmpty()) {
            body.add("tools", tools(request.tools()));
            if (request.toolChoiceNone()) {
                body.addProperty("tool_choice", "none");
            }
        }
        body.addProperty("stream", true);
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        body.add("stream_options", streamOptions);
        if (profile.temperature() != null) {
            body.addProperty("temperature", profile.temperature());
        }
        if (request.maxOutput() > 0) {
            body.addProperty("max_tokens", request.maxOutput());
        }
        effort(body, profile.model(), request.effort());
        String cacheKey = request.cacheKey(OpenAi.PROMPT_CACHE_KEY_MAX);
        if ("api.openai.com".equalsIgnoreCase(uri.getHost()) && cacheKey != null) {
            body.addProperty("prompt_cache_key", cacheKey);
        }

        StreamCollector out = new StreamCollector(listener, profile.model());
        Map<Integer, Integer> slots = new HashMap<>();
        HttpJson.stream(OpenAi.auth(HttpJson.post(uri, body), profile.apiKey()).build(), event -> {
            if (event.data().equals("[DONE]")) {
                out.finish(null);
                return false;
            }
            JsonObject chunk = HttpJson.parseObject(event.data());
            if (chunk == null) {
                return true;
            }
            if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
                throw HttpJson.streamError(chunk, event.data());
            }
            out.model(optString(chunk, "model"));
            JsonArray choices = HttpJson.array(chunk, "choices");
            if (!choices.isEmpty() && choices.get(0).isJsonObject()) {
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject delta = optObject(choice, "delta");
                if (delta != null) {
                    String thinking = optString(delta, "reasoning_content");
                    out.reasoning(thinking.isEmpty() ? optString(delta, "reasoning") : thinking);
                    out.text(optString(delta, "content"));
                    readToolCalls(HttpJson.array(delta, "tool_calls"), out, slots);
                }
                String finish = optString(choice, "finish_reason");
                if (!finish.isEmpty()) {
                    out.finish(finish);
                }
            }
            JsonObject u = optObject(chunk, "usage");
            if (u != null) {
                out.promptTokens = HttpJson.intOr(u, "prompt_tokens", out.promptTokens);
                out.completionTokens = HttpJson.intOr(u, "completion_tokens", out.completionTokens);
                out.cachedTokens = HttpJson.firstInt(u, "prompt_tokens_details.cached_tokens", "prompt_cache_hit_tokens",
                        "cached_tokens", "cache_read_input_tokens");
                out.finish(null);
            }
            return true;
        });
        return out.result("length");
    }

    @Override
    public List<String> listModels(String baseUrl, String apiKey) throws AgentException {
        return OpenAi.listModels(baseUrl, apiKey);
    }

    private static void readToolCalls(JsonArray deltas, StreamCollector out, Map<Integer, Integer> slots) {
        for (int i = 0; i < deltas.size(); i++) {
            if (!deltas.get(i).isJsonObject()) {
                continue;
            }
            JsonObject delta = deltas.get(i).getAsJsonObject();
            int index = HttpJson.intOr(delta, "index", i);
            String id = optString(delta, "id");
            Integer slot = slots.get(index);
            if (slot == null || (!id.isEmpty() && !out.call(slot).id.isEmpty() && !out.call(slot).id.equals(id))) {
                slot = out.callCount();
                slots.put(index, slot);
            }
            StreamCollector.PartialCall call = out.call(slot);
            if (!id.isEmpty()) {
                call.id = id;
            }
            JsonObject function = optObject(delta, "function");
            if (function != null) {
                String name = optString(function, "name");
                if (call.name.isEmpty() && !name.isEmpty()) {
                    call.name = name;
                }
                call.arguments.append(optString(function, "arguments"));
            }
        }
    }

    private static JsonArray messages(ChatRequest request, boolean requireReasoning) {
        int lastUser = request.lastUserIndex();
        JsonArray list = new JsonArray();
        List<ChatMessage> messages = request.messages();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            JsonObject item = new JsonObject();
            item.addProperty("role", m.role());
            if (m.isTool()) {
                item.addProperty("tool_call_id", m.toolCallId());
                item.addProperty("content", m.content());
            } else if (!m.images().isEmpty()) {
                item.add("content", parts(m));
            } else if (m.content().isEmpty() && !m.toolCalls().isEmpty()) {
                item.add("content", JsonNull.INSTANCE);
            } else {
                item.addProperty("content", m.content());
            }
            if (!m.toolCalls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (ToolCall call : m.toolCalls()) {
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name());
                    function.addProperty("arguments", call.arguments());
                    JsonObject c = new JsonObject();
                    c.addProperty("id", call.id());
                    c.addProperty("type", "function");
                    c.add("function", function);
                    calls.add(c);
                }
                item.add("tool_calls", calls);
            }
            if (requireReasoning && m.isAssistant()) {
                item.addProperty("reasoning_content", i > lastUser ? m.reasoning() : "");
            }
            list.add(item);
        }
        return list;
    }

    private static JsonArray tools(List<ToolSpec> specs) {
        JsonArray tools = new JsonArray();
        for (ToolSpec spec : specs) {
            JsonObject function = new JsonObject();
            function.addProperty("name", spec.name());
            function.addProperty("description", spec.description());
            function.add("parameters", spec.parameters());
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", function);
            tools.add(tool);
        }
        return tools;
    }

    private static JsonArray parts(ChatMessage m) {
        JsonArray parts = new JsonArray();
        if (!m.content().isEmpty()) {
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", m.content());
            parts.add(text);
        }
        for (ImagePart image : m.images()) {
            JsonObject url = new JsonObject();
            url.addProperty("url", image.dataUrl());
            JsonObject part = new JsonObject();
            if (image.mime() != null && image.mime().startsWith("video/")) {
                part.addProperty("type", "video_url");
                part.add("video_url", url);
            } else {
                part.addProperty("type", "image_url");
                part.add("image_url", url);
            }
            parts.add(part);
        }
        return parts;
    }

    private static boolean requiresReasoning(ModelProfile profile) {
        return profile.model().toLowerCase().contains("deepseek") || profile.baseUrl().toLowerCase().contains("deepseek.com");
    }
}
