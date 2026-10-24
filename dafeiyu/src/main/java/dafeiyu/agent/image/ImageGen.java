package dafeiyu.agent.image;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.protocol.Protocols;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class ImageGen {

    public record Config(boolean enabled, String baseUrl, String apiKey, String model, String size) {

        public Config {
            baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
            apiKey = apiKey == null ? "" : apiKey.strip();
            model = model == null ? "" : model.strip();
            size = size == null || size.isBlank() ? DEFAULT_SIZE : size.strip();
        }

        public boolean filled() {
            return !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
        }

        public boolean ready() {
            return enabled && filled();
        }
    }

    public record Picture(byte[] bytes, String extension) {}

    public record Result(List<Picture> pictures, String revisedPrompt) {}

    public static final String DEFAULT_SIZE = "1024x1024";
    public static final int MAX_PICTURES = 4;

    private static final String KEY = "agent.imageGen";
    private static final java.util.regex.Pattern IMAGE_MODEL = java.util.regex.Pattern.compile(
            "(?i)image|dall-?e|flux|stable-?diffusion|sdxl|\\bsd[-\\d]|midjourney|\\bmj\\b|imagen|kolors|seedream|cogview|wanx|jimeng|hunyuan-?image|recraft|ideogram|playground|banana");
    private static final String ENDPOINT = "/images/generations";
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final int ERROR_MAX = 300;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Gson GSON = new Gson();

    private final Settings settings;
    private final Path folder;

    public ImageGen(Database db) {
        this.settings = new Settings(db);
        this.folder = db.file().toAbsolutePath().getParent().resolve("generated");
    }

    public Path folder() {
        return folder;
    }

    public Config config() {
        String raw = settings.get(KEY);
        if (raw != null && !raw.isBlank()) {
            try {
                Config saved = GSON.fromJson(raw, Config.class);
                if (saved != null) {
                    return new Config(saved.enabled(), saved.baseUrl(), saved.apiKey(), saved.model(), saved.size());
                }
            } catch (JsonParseException ignored) {
            }
        }
        return new Config(false, "", "", "", DEFAULT_SIZE);
    }

    public synchronized Config save(boolean enabled, String baseUrl, String apiKey, String model, String size) {
        Config old = config();
        Config next = new Config(enabled, baseUrl, apiKey == null || apiKey.isBlank() ? old.apiKey() : apiKey, model, size);
        if (!next.baseUrl().isEmpty() && !next.baseUrl().matches("(?i)https?://\\S+")) {
            throw new IllegalArgumentException("地址要以 http:// 或 https:// 开头");
        }
        if (!next.size().matches("\\d{2,5}x\\d{2,5}|auto")) {
            throw new IllegalArgumentException("尺寸写成 1024x1024 这样，或者 auto");
        }
        if (next.enabled() && !next.filled()) {
            throw new IllegalArgumentException("先填好地址、API Key 和模型才能开启");
        }
        settings.put(KEY, GSON.toJson(next));
        return next;
    }

    public Result generate(String prompt, String size, int count) throws ToolFailure {
        Config config = config();
        if (!config.filled()) {
            throw new ToolFailure("生图还没配置好：要先在设置里填好地址、API Key 和模型");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("prompt", prompt);
        body.addProperty("size", size == null || size.isBlank() ? config.size() : size.strip());
        body.addProperty("n", Math.max(1, Math.min(MAX_PICTURES, count)));
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint(config.baseUrl()))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new ToolFailure("生图超时了（等了 " + TIMEOUT.toMinutes() + " 分钟没出图）");
        } catch (IOException e) {
            throw new ToolFailure("生图接口连不上：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("生图被打断了");
        }
        JsonObject json = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new ToolFailure("生图失败（HTTP " + response.statusCode() + "）：" + message(json, response.body()));
        }
        JsonElement data = json == null ? null : json.get("data");
        if (data == null || !data.isJsonArray() || data.getAsJsonArray().isEmpty()) {
            throw new ToolFailure("生图接口没返回图片：" + abbreviate(response.body(), ERROR_MAX));
        }
        List<Picture> pictures = new ArrayList<>();
        String revised = "";
        for (JsonElement item : data.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject o = item.getAsJsonObject();
            byte[] bytes = picture(o);
            if (bytes != null) {
                pictures.add(new Picture(bytes, extension(bytes)));
            }
            if (revised.isEmpty()) {
                revised = optString(o, "revised_prompt");
            }
        }
        if (pictures.isEmpty()) {
            throw new ToolFailure("生图接口返回的数据里没有图片");
        }
        return new Result(pictures, revised);
    }

    private static byte[] picture(JsonObject item) throws ToolFailure {
        String b64 = optString(item, "b64_json");
        if (!b64.isBlank()) {
            try {
                return Base64.getMimeDecoder().decode(b64.replaceFirst("^data:[^,]*,", ""));
            } catch (IllegalArgumentException e) {
                throw new ToolFailure("生图接口返回的图片数据坏了");
            }
        }
        String url = optString(item, "url");
        if (url.isBlank()) {
            return null;
        }
        if (url.startsWith("data:")) {
            return Base64.getMimeDecoder().decode(url.substring(url.indexOf(',') + 1));
        }
        try {
            return ImageLoader.fetch(url);
        } catch (AgentException e) {
            throw new ToolFailure("图生成好了但下载失败：" + e.getMessage());
        }
    }

    public List<String> listModels(String baseUrl, String apiKey) throws ToolFailure {
        Config config = config();
        String url = baseUrl == null || baseUrl.isBlank() ? config.baseUrl() : apiBase(baseUrl);
        if (url.isEmpty()) {
            throw new ToolFailure("先填接口地址");
        }
        String key = apiKey == null || apiKey.isBlank() ? sameServer(config.baseUrl(), url) ? config.apiKey() : "" : apiKey.strip();
        try {
            List<String> models = new ArrayList<>(Protocols.get("openai-chat").listModels(apiBase(url), key));
            models.sort(Comparator.comparing((String id) -> !IMAGE_MODEL.matcher(id).find()));
            return models;
        } catch (AgentException e) {
            throw new ToolFailure(e.getMessage());
        }
    }

    public static boolean imageModel(String id) {
        return IMAGE_MODEL.matcher(id).find();
    }

    private static String apiBase(String baseUrl) {
        return baseUrl.strip().replaceAll("/+$", "").replaceFirst("(?i)/images/generations$", "");
    }

    private static boolean sameServer(String a, String b) {
        try {
            URI x = URI.create(a);
            URI y = URI.create(b);
            return x.getHost() != null && x.getHost().equalsIgnoreCase(y.getHost()) && x.getPort() == y.getPort();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static URI endpoint(String baseUrl) throws ToolFailure {
        String url = apiBase(baseUrl);
        try {
            URI base = URI.create(url);
            if (base.getHost() == null) {
                throw new IllegalArgumentException();
            }
            String path = base.getPath();
            return URI.create(url + (path == null || path.isEmpty() ? "/v1" : "") + ENDPOINT);
        } catch (IllegalArgumentException e) {
            throw new ToolFailure("生图地址写得不对：" + baseUrl);
        }
    }

    private static JsonObject parse(String body) {
        try {
            JsonElement json = JsonParser.parseString(body == null ? "" : body);
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static String message(JsonObject json, String body) {
        JsonObject error = json == null ? null : optObject(json, "error");
        String text = error != null ? optString(error, "message") : json != null ? optString(json, "message") : "";
        return abbreviate(text.isBlank() ? body == null ? "" : body.strip() : text, ERROR_MAX);
    }

    private static String extension(byte[] bytes) {
        return switch (String.valueOf(ImageLoader.mime(bytes))) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }
}
