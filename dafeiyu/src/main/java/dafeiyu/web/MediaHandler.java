package dafeiyu.web;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.agent.tool.ToolFailure;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

final class MediaHandler {

    private final QqChatBridge bridge;

    MediaHandler(QqChatBridge bridge) {
        this.bridge = bridge;
    }

    void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (ex.getRequestMethod().equals("GET") && path.equals("/api/media/record-text")) {
            JsonObject body = new JsonObject();
            try {
                body.addProperty("text", bridge.voice().transcribe(query(ex, "message_id")));
                HttpResponses.json(ex, body.toString());
            } catch (ToolFailure e) {
                body.addProperty("error", "转不出文字：" + e.getMessage());
                HttpResponses.json(ex, 502, body.toString());
            }
            return;
        }
        if (ex.getRequestMethod().equals("GET") && path.equals("/api/media/image")) {
            try {
                byte[] image = bridge.image(Long.parseLong(query(ex, "id")));
                ex.getResponseHeaders().set("Content-Type", imageType(image));
                ex.getResponseHeaders().set("Cache-Control", "private, max-age=86400");
                ex.sendResponseHeaders(200, image.length);
                try (var out = ex.getResponseBody()) {
                    out.write(image);
                }
            } catch (ToolFailure | NumberFormatException e) {
                HttpResponses.notFound(ex);
            }
            return;
        }
        if (!ex.getRequestMethod().equals("GET") || !path.equals("/api/media/record")) {
            HttpResponses.notFound(ex);
            return;
        }
        try {
            String file = query(ex, "file");
            byte[] audio = bridge.voice().mp3(file.isEmpty() ? bridge.recordFile(query(ex, "url")) : file);
            ex.getResponseHeaders().set("Content-Type", "audio/mpeg");
            ex.getResponseHeaders().set("Cache-Control", "private, max-age=86400");
            ex.sendResponseHeaders(200, audio.length);
            try (var out = ex.getResponseBody()) {
                out.write(audio);
            }
        } catch (ToolFailure e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "语音放不了：" + e.getMessage());
            HttpResponses.json(ex, 502, error.toString());
        }
    }

    private static String imageType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0x89 && b[1] == 'P') {
            return "image/png";
        }
        if (b.length > 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "image/gif";
        }
        if (b.length > 11 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static String query(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith(key + "=")) {
                    return URLDecoder.decode(pair.substring(key.length() + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }
}
