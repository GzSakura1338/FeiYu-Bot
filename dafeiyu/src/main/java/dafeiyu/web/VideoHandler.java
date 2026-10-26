package dafeiyu.web;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.image.VideoFrames;
import dafeiyu.log.LogBuffer;

import java.io.IOException;

final class VideoHandler {

    private static final String PREFIX = "/api/agent/video";

    private record UpdateRequest(Integer secondsPerFrame, Integer maxSeconds, Integer maxFrames, Integer gifFrames) {}

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
                if (body.secondsPerFrame() != null
                        && (body.secondsPerFrame() < 0 || body.secondsPerFrame() > VideoFrames.MAX_SPF)) {
                    throw new IllegalArgumentException("几秒一张要在 0（不看）到 " + VideoFrames.MAX_SPF + " 之间");
                }
                if (body.maxSeconds() != null
                        && (body.maxSeconds() < 0 || body.maxSeconds() > VideoFrames.MAX_SECONDS)) {
                    throw new IllegalArgumentException("看多长要在 0（整段）到 " + VideoFrames.MAX_SECONDS + " 秒之间");
                }
                if (body.maxFrames() != null && (body.maxFrames() < 1 || body.maxFrames() > VideoFrames.MAX_FRAMES)) {
                    throw new IllegalArgumentException("最多截几张要在 1 到 " + VideoFrames.MAX_FRAMES + " 之间");
                }
                if (body.gifFrames() != null
                        && (body.gifFrames() < 1 || body.gifFrames() > VideoFrames.MAX_GIF_FRAMES)) {
                    throw new IllegalArgumentException("动图抽几张要在 1 到 " + VideoFrames.MAX_GIF_FRAMES + " 之间");
                }
                VideoFrames.Config saved = agent.videoFrames().save(
                        body.secondsPerFrame(), body.maxSeconds(), body.maxFrames(), body.gifFrames());
                log.info("agent: video " + saved.secondsPerFrame() + "s/frame, max " + saved.maxSeconds()
                        + "s, maxFrames " + saved.maxFrames() + ", gif " + saved.gifFrames());
                HttpResponses.json(ex, state());
            } else if (rest.equals("/probe") && method.equals("POST")) {
                // 调试键：当场探测模型能不能直接看视频
                boolean native_ = agent.nativeVideo();
                JsonObject o = new JsonObject();
                o.addProperty("native", native_);
                o.addProperty("ok", true);
                HttpResponses.json(ex, o.toString());
            } else {
                HttpResponses.json(ex, 404, error("没有这个接口"));
            }
        } catch (IllegalArgumentException e) {
            HttpResponses.json(ex, 400, error(e.getMessage()));
        } catch (RuntimeException e) {
            // 别让异常冒泡把连接直接断掉（那样浏览器只看到 Empty reply，什么线索都没有）
            log.error("agent: video settings failed: " + e);
            HttpResponses.json(ex, 500, error(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private String state() {
        VideoFrames.Config config = agent.videoFrames().config();
        JsonObject body = new JsonObject();
        body.addProperty("secondsPerFrame", config.secondsPerFrame());
        body.addProperty("maxSeconds", config.maxSeconds());
        body.addProperty("maxFrames", config.maxFrames());
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
