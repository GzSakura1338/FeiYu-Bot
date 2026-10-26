package dafeiyu.web;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.image.VideoFrames;
import dafeiyu.log.LogBuffer;

import java.io.IOException;

final class VideoHandler {

    private static final String PREFIX = "/api/agent/video";

    private record UpdateRequest(Integer intervalMs) {}

    private final ChatAgent agent;
    private final LogBuffer log;

    VideoHandler(ChatAgent agent, LogBuffer log) {
        this.agent = agent;
        this.log = log;
    }

    void handle(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getPath().substring(PREFIX.length());
        String method = ex.getRequestMethod();
        try {
            if ((rest.isEmpty() || rest.equals("/")) && method.equals("GET")) {
                agent.videoFrames().relocate();
                HttpResponses.json(ex, state());
            } else if ((rest.isEmpty() || rest.equals("/")) && method.equals("PUT") && Requests.isJson(ex)) {
                UpdateRequest body = Requests.readJson(ex, UpdateRequest.class);
                if (body.intervalMs() == null || body.intervalMs() < 0) {
                    throw new IllegalArgumentException("抽帧间隔要填毫秒数，0 是不看");
                }
                VideoFrames.Config saved = agent.videoFrames().save(body.intervalMs());
                log.info("agent: video frames every " + saved.intervalMs() + " ms" + (saved.enabled() ? "" : " (off)"));
                HttpResponses.json(ex, state());
            } else {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            }
        } catch (IllegalArgumentException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        }
    }

    private String state() {
        VideoFrames.Config config = agent.videoFrames().config();
        JsonObject body = new JsonObject();
        body.addProperty("intervalMs", config.intervalMs());
        body.addProperty("ffmpeg", agent.videoFrames().ffmpeg());
        body.addProperty("vision", agent.vision());
        return body.toString();
    }

    private static String error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o.toString();
    }
}
