package dafeiyu.agent.qq;

import com.google.gson.JsonObject;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;

public final class QqVoice {

    private static final Duration TRANSCRIBE_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration CONVERT_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._-]{1,120}");
    private static final int MAX_TEXT = 500;
    private static final long[] RETRY_DELAYS_MS = {1_500, 2_500, 4_000};

    private final QqOutbox outbox;
    private final Path cache;

    QqVoice(QqOutbox outbox, Path cache) {
        this.outbox = outbox;
        this.cache = cache;
    }

    public String transcribe(String messageId) throws ToolFailure {
        JsonObject params = new JsonObject();
        try {
            params.addProperty("message_id", Long.parseLong(messageId));
        } catch (NumberFormatException e) {
            params.addProperty("message_id", messageId);
        }
        for (int attempt = 0; ; attempt++) {
            try {
                JsonObject data = optObject(outbox.call("fetch_ptt_text", params, TRANSCRIBE_TIMEOUT), "data");
                String text = data == null ? "" : optString(data, "text").replaceAll("\\s+", " ").strip();
                return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
            } catch (ToolFailure e) {
                if (attempt >= RETRY_DELAYS_MS.length || e.getMessage() == null || !e.getMessage().contains("转文字结果")) {
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    public byte[] mp3(String file) throws ToolFailure {
        if (file == null || !FILE.matcher(file).matches()) {
            throw new ToolFailure("语音文件名不对");
        }
        Path cached = cache.resolve(file + ".mp3");
        try {
            if (Files.isRegularFile(cached)) {
                return Files.readAllBytes(cached);
            }
        } catch (IOException ignored) {
        }
        JsonObject params = new JsonObject();
        params.addProperty("file", file);
        params.addProperty("out_format", "mp3");
        JsonObject data = optObject(outbox.call("get_record", params, CONVERT_TIMEOUT), "data");
        String base64 = data == null ? "" : optString(data, "base64");
        if (base64.isEmpty()) {
            throw new ToolFailure("QQ 没给出转换好的语音");
        }
        byte[] audio = Base64.getDecoder().decode(base64);
        try {
            Files.createDirectories(cache);
            Files.write(cached, audio);
            // 和 Workspace 落盘同样的道理：容器里的 QQ 以 uid=1000 跑，
            // createTempFile/Files.write 给的是 600，它读不到就发不出去。
            Workspace.makeWorldReadable(cached);
        } catch (IOException e) {
            System.err.println("WARN voice: 语音缓存写不进去（这次发送不受影响）：" + e.getMessage());
        }
        return audio;
    }
}
