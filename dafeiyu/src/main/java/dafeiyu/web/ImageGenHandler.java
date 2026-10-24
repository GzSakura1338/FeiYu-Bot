package dafeiyu.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.image.ImageGen;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.log.LogBuffer;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

final class ImageGenHandler {

    private static final String PREFIX = "/api/agent/image-gen";
    private static final String TEST_PROMPT = "一只 Q 版的蓝色小鲸鱼女孩在海边挥手，柔和的粉彩配色，可爱插画风格";

    private record UpdateRequest(Boolean enabled, String baseUrl, String apiKey, String model, String size) {}

    private record TestRequest(String prompt) {}

    private record ModelsRequest(String baseUrl, String apiKey) {}

    private final ChatAgent agent;
    private final LogBuffer log;

    ImageGenHandler(ChatAgent agent, LogBuffer log) {
        this.agent = agent;
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        try {
            if ((rest.isEmpty() || rest.equals("/")) && method.equals("GET")) {
                HttpResponses.json(ex, state(agent.imageGen().config()));
            } else if ((rest.isEmpty() || rest.equals("/")) && method.equals("PUT") && Requests.isJson(ex)) {
                UpdateRequest body = Requests.readJson(ex, UpdateRequest.class);
                ImageGen.Config old = agent.imageGen().config();
                ImageGen.Config saved = agent.imageGen().save(body.enabled() == null ? old.enabled() : body.enabled(),
                        body.baseUrl() == null ? old.baseUrl() : body.baseUrl(), body.apiKey(),
                        body.model() == null ? old.model() : body.model(), body.size() == null ? old.size() : body.size());
                log.info("agent: image generation " + (saved.enabled() ? "ON" : "OFF") + " (" + saved.model() + ")");
                HttpResponses.json(ex, state(saved));
            } else if (rest.equals("/test") && method.equals("POST") && Requests.isJson(ex)) {
                test(ex, Requests.readJson(ex, TestRequest.class));
            } else if (rest.equals("/models") && method.equals("POST") && Requests.isJson(ex)) {
                ModelsRequest body = Requests.readJson(ex, ModelsRequest.class);
                try {
                    List<String> models = agent.imageGen().listModels(body.baseUrl(), body.apiKey());
                    JsonObject out = new JsonObject();
                    JsonArray list = new JsonArray();
                    for (String id : models) {
                        JsonObject o = new JsonObject();
                        o.addProperty("id", id);
                        o.addProperty("image", ImageGen.imageModel(id));
                        list.add(o);
                    }
                    out.add("models", list);
                    HttpResponses.json(ex, out.toString());
                } catch (ToolFailure e) {
                    HttpResponses.json(ex, 502, error(e.getMessage()));
                }
            } else {
                HttpResponses.json(ex, 405, error("不支持这个请求"));
            }
        } catch (IllegalArgumentException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        }
    }

    private void test(HttpExchange ex, TestRequest body) throws IOException {
        String prompt = body == null || body.prompt() == null || body.prompt().isBlank() ? TEST_PROMPT : body.prompt().strip();
        long started = System.currentTimeMillis();
        try {
            ImageGen.Picture picture = agent.imageGen().generate(prompt, "", 1).pictures().getFirst();
            String mime = ImageLoader.mime(picture.bytes());
            JsonObject out = new JsonObject();
            out.addProperty("image", "data:" + (mime == null ? "image/png" : mime) + ";base64," + Base64.getEncoder().encodeToString(picture.bytes()));
            out.addProperty("seconds", (System.currentTimeMillis() - started) / 1000.0);
            log.info("agent: image generation test ok in " + out.get("seconds").getAsString() + "s");
            HttpResponses.json(ex, out.toString());
        } catch (ToolFailure e) {
            log.warn("agent: image generation test failed: " + e.getMessage());
            HttpResponses.json(ex, 502, error(e.getMessage()));
        }
    }

    private static String state(ImageGen.Config config) {
        JsonObject body = new JsonObject();
        body.addProperty("enabled", config.enabled());
        body.addProperty("baseUrl", config.baseUrl());
        body.addProperty("model", config.model());
        body.addProperty("size", config.size());
        body.addProperty("hasKey", !config.apiKey().isEmpty());
        String key = config.apiKey();
        body.addProperty("keyHint", key.length() > 8 ? key.substring(0, 3) + "…" + key.substring(key.length() - 4) : key.isEmpty() ? "" : "已保存");
        return body.toString();
    }

    private static String error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o.toString();
    }
}
