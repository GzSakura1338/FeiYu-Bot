package dafeiyu.web;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.image.VideoFrames;
import dafeiyu.log.LogBuffer;

import java.io.IOException;

final class VideoHandler {

    private static final String PREFIX = "/api/agent/video";

    private record UpdateRequest(Integer intervalMs, Integer gifFrames) {}

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
                if (body.intervalMs() != null && body.intervalMs() < 0) {
                    throw new IllegalArgumentException("抽帧间隔要填毫秒数，0 是不看");
                }
                if (body.gifFrames() != null && (body.gifFrames() < 1 || body.gifFrames() > VideoFrames.MAX_GIF_FRAMES)) {
                    throw new IllegalArgumentException("动图抽几张要在 1 到 " + VideoFrames.MAX_GIF_FRAMES + " 之间");
                }
                VideoFrames.Config saved = agent.videoFrames().save(body.intervalMs(), body.gifFrames());
                log.info("agent: video frames every " + saved.intervalMs() + " ms" + (saved.enabled() ? "" : " (off)") + ", gif " + saved.gifFrames());
                HttpResponses.json(ex, state());
            } else if (rest.equals("/probe") && method.equals("POST")) {
                JsonObject o = new JsonObject();
                o.addProperty("native", agent.nativeVideo());
                o.addProperty("ok", true);
                HttpResponses.json(ex, o.toString());
            } else {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            }
        } catch (IllegalArgumentException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("agent: video settings failed: " + e);
            HttpResponses.json(ex, 500, error(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private String state() {
        VideoFrames.Config config = agent.videoFrames().config();
        JsonObject body = new JsonObject();
        body.addProperty("intervalMs", config.intervalMs());
        body.addProperty("gifFrames", config.gifFrames());
        body.addProperty("ffmpeg", agent.videoFrames().ffmpeg());
        body.addProperty("vision", agent.vision());
        Boolean nativeVideo = agent.videoNative();
        if (nativeVideo != null) {
            body.addProperty("native", nativeVideo);
        }
        return body.toString();
    }

    private static String error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o.toString();
    }
}
