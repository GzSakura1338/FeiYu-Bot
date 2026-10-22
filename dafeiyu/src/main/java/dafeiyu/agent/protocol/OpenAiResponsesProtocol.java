package dafeiyu.agent.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.model.ModelProfile;

import java.util.List;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;

final class OpenAiResponsesProtocol implements ModelProtocol {

    @Override
    public ChatResult stream(ModelProfile profile, ChatRequest request, StreamListener listener) throws AgentException {
        StringBuilder instructions = new StringBuilder();
        JsonArray input = new JsonArray();
        for (ChatMessage m : request.messages()) {
            if (m.role().equals("system")) {
                instructions.append(instructions.isEmpty() ? "" : "\n\n").append(m.content());
            } else if (m.isTool()) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "function_call_output");
                item.addProperty("call_id", m.toolCallId());
                item.addProperty("output", m.content());
                input.add(item);
            } else {
                if (!m.content().isEmpty() || !m.images().isEmpty() || m.toolCalls().isEmpty()) {
                    input.add(message(m));
                }
                for (ToolCall call : m.toolCalls()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("type", "function_call");
                    item.addProperty("call_id", call.id());
                    item.addProperty("name", call.name());
                    item.addProperty("arguments", call.arguments());
                    input.add(item);
                }
            }
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", profile.model());
        body.add("input", input);
        if (!instructions.isEmpty()) {
            body.addProperty("instructions", instructions.toString());
        }
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec spec : request.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.addProperty("name", spec.name());
                tool.addProperty("description", spec.description());
                tool.add("parameters", spec.parameters());
                tools.add(tool);
            }
            body.add("tools", tools);
            if (request.toolChoiceNone()) {
                body.addProperty("tool_choice", "none");
            }
        }
        if (profile.temperature() != null) {
            body.addProperty("temperature", profile.temperature());
        }
        if (request.maxOutput() > 0) {
            body.addProperty("max_output_tokens", request.maxOutput());
        }
        if (!request.effort().isEmpty()) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", request.effort().equals("max") ? "high" : request.effort());
            body.add("reasoning", reasoning);
        }
        String cacheKey = request.cacheKey(OpenAi.PROMPT_CACHE_KEY_MAX);
        if (cacheKey != null) {
            body.addProperty("prompt_cache_key", cacheKey);
        }
        body.addProperty("store", false);
        body.addProperty("stream", true);

        StreamCollector out = new StreamCollector(listener, profile.model());
        HttpJson.stream(OpenAi.auth(
                HttpJson.post(HttpJson.uri(HttpJson.withDefaultVersion(profile.baseUrl()), "/responses"), body), profile.apiKey()).build(), event -> {
            JsonObject data = HttpJson.parseObject(event.data());
            if (data == null) {
                return true;
            }
            switch (optString(data, "type")) {
                case "response.output_text.delta" -> out.text(optString(data, "delta"));
                case "response.reasoning_summary_text.delta", "response.reasoning_text.delta", "response.reasoning.delta" ->
                        out.reasoning(optString(data, "delta"));
                case "response.output_item.done" -> {
                    JsonObject item = optObject(data, "item");
                    if (item != null && optString(item, "type").equals("function_call")) {
                        out.addCall(optString(item, "call_id"), optString(item, "name"), optString(item, "arguments"));
                    }
                }
                case "response.completed", "response.incomplete" -> {
                    JsonObject response = optObject(data, "response");
                    JsonObject incomplete = response == null ? null : optObject(response, "incomplete_details");
                    JsonObject usage = response == null ? null : optObject(response, "usage");
                    if (response != null) {
                        out.model(optString(response, "model"));
                    }
                    out.promptTokens = HttpJson.intOr(usage, "input_tokens", -1);
                    out.completionTokens = HttpJson.intOr(usage, "output_tokens", -1);
                    out.cachedTokens = HttpJson.firstInt(usage == null ? new JsonObject() : usage, "input_tokens_details.cached_tokens");
                    String reason = incomplete == null ? "" : optString(incomplete, "reason");
                    out.finish(reason.isEmpty() ? (out.callCount() > 0 ? "tool_calls" : "stop") : reason);
                    return false;
                }
                case "response.failed" -> {
                    JsonObject response = optObject(data, "response");
                    JsonObject error = response == null ? null : optObject(response, "error");
                    throw HttpJson.streamError(error == null ? new JsonObject() : wrap(error), event.data());
                }
                case "error" -> throw HttpJson.streamError(data, event.data());
                default -> { }
            }
            return true;
        });
        return out.result("max_output_tokens");
    }

    @Override
    public List<String> listModels(String baseUrl, String apiKey) throws AgentException {
        return OpenAi.listModels(baseUrl, apiKey);
    }

    private static JsonObject message(ChatMessage m) {
        boolean assistant = m.isAssistant();
        JsonArray content = new JsonArray();
        if (!m.content().isEmpty() || m.images().isEmpty()) {
            JsonObject part = new JsonObject();
            part.addProperty("type", assistant ? "output_text" : "input_text");
            part.addProperty("text", m.content());
            content.add(part);
        }
        for (ImagePart image : m.images()) {
            JsonObject part = new JsonObject();
            part.addProperty("type", "input_image");
            part.addProperty("image_url", image.dataUrl());
            content.add(part);
        }
        JsonObject item = new JsonObject();
        item.addProperty("role", assistant ? "assistant" : "user");
        item.add("content", content);
        return item;
    }

    private static JsonObject wrap(JsonObject error) {
        JsonObject json = new JsonObject();
        json.add("error", error);
        return json;
    }
}
