package dafeiyu.agent.image;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.tool.PublicWeb;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;
import dafeiyu.sandbox.Platform;
import dafeiyu.sandbox.Sandbox;
import dafeiyu.sandbox.Sandboxes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class VideoFrames {

    /**
     * @param secondsPerFrame 几秒截一张；0 表示不看视频
     * @param maxSeconds      最多看多长；0 表示整段全看
     * @param maxFrames       最多截几张（兜底，防止 1 秒 1 帧把长视频截成几百张）
     * @param gifFrames       动图 GIF 抽几张；1 表示只看第一帧
     */
    public record Config(int secondsPerFrame, int maxSeconds, int maxFrames, int gifFrames) {

        public Config {
            secondsPerFrame = secondsPerFrame < 0 ? 0
                    : secondsPerFrame == 0 ? 0 : Math.clamp(secondsPerFrame, MIN_SPF, MAX_SPF);
            maxSeconds = maxSeconds <= 0 ? 0 : Math.clamp(maxSeconds, MIN_SECONDS, MAX_SECONDS);
            maxFrames = Math.clamp(maxFrames <= 0 ? DEFAULT_MAX_FRAMES : maxFrames, 1, MAX_FRAMES);
            gifFrames = Math.clamp(gifFrames <= 0 ? DEFAULT_GIF_FRAMES : gifFrames, 1, MAX_GIF_FRAMES);
        }

        public boolean enabled() {
            return secondsPerFrame > 0;
        }
    }

    public record Frames(List<ImagePart> parts, String note) {}

    public static final int MIN_SPF = 1;
    public static final int MAX_SPF = 60;
    public static final int MIN_SECONDS = 5;
    public static final int MAX_SECONDS = 3600;
    public static final int MAX_FRAMES = 200;
    public static final int MAX_GIF_FRAMES = 12;
    private static final int DEFAULT_MAX_FRAMES = 24;
    private static final int DEFAULT_SPF = 5;
    private static final int DEFAULT_SECONDS = 60;
    private static final int DEFAULT_GIF_FRAMES = 6;
    private static final String KEY = "agent.video";
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final Duration DOWNLOAD = Duration.ofMinutes(2);
    private static final long RUN_MS = 90_000;
    private static final Pattern DURATION = Pattern.compile("Duration:\\s*(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    private static final Semaphore SLOTS = new Semaphore(2);
    private static final Gson GSON = new Gson();

    private final Settings settings;
    private final Path temp;
    private volatile String ffmpeg;
    private volatile boolean located;

    public VideoFrames(Database db) {
        this.settings = new Settings(db);
        this.temp = db.file().toAbsolutePath().getParent().resolve("video-tmp");
    }

    public Config config() {
        String raw = settings.get(KEY);
        if (raw != null && !raw.isBlank()) {
            try {
                com.google.gson.JsonObject o = GSON.fromJson(raw, com.google.gson.JsonObject.class);
                if (o != null) {
                    if (o.has("secondsPerFrame") || o.has("maxFrames")) {
                        return new Config(integer(o, "secondsPerFrame", DEFAULT_SPF),
                                integer(o, "maxSeconds", 0),
                                integer(o, "maxFrames", DEFAULT_MAX_FRAMES),
                                integer(o, "gifFrames", DEFAULT_GIF_FRAMES));
                    }
                    int frames = integer(o, "frames", 0);
                    int seconds = integer(o, "seconds", DEFAULT_SECONDS);
                    if (frames <= 0) {
                        return new Config(0, seconds, DEFAULT_MAX_FRAMES, DEFAULT_GIF_FRAMES);
                    }
                    return new Config(Math.max(1, seconds / frames), seconds, frames, DEFAULT_GIF_FRAMES);
                }
            } catch (JsonParseException | IllegalStateException ignored) {
            }
        }
        return new Config(DEFAULT_SPF, DEFAULT_SECONDS, DEFAULT_MAX_FRAMES, DEFAULT_GIF_FRAMES);
    }

    private static int integer(com.google.gson.JsonObject o, String key, int fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public synchronized Config save(Integer secondsPerFrame, Integer maxSeconds, Integer maxFrames, Integer gifFrames) {
        Config old = config();
        Config next = new Config(
                secondsPerFrame == null ? old.secondsPerFrame() : secondsPerFrame,
                maxSeconds == null ? old.maxSeconds() : maxSeconds,
                maxFrames == null ? old.maxFrames() : maxFrames,
                gifFrames == null ? old.gifFrames() : gifFrames);
        settings.put(KEY, GSON.toJson(next));
        return next;
    }

    public String ffmpeg() {
        if (!located) {
            ffmpeg = locate();
            located = true;
        }
        return ffmpeg;
    }

    public void relocate() {
        located = false;
    }

    /** 常见安装位置：很多人装了但没加进 PATH，先按 PATH 找，再兜这些地方。 */
    private static final List<String> COMMON_DIRS = Platform.windows()
            ? List.of("C:/ffmpeg/bin", "C:/Program Files/ffmpeg/bin",
                      "C:/ProgramData/chocolatey/bin", "C:/Users/Public/ffmpeg/bin")
            : List.of("/usr/bin", "/usr/local/bin", "/opt/homebrew/bin", "/snap/bin", "/bin");

    private static String locate() {
        String name = Platform.windows() ? "ffmpeg.exe" : "ffmpeg";
        String path = System.getenv().getOrDefault("PATH", System.getenv().getOrDefault("Path", ""));
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            String found = tryPath(dir.strip(), name);
            if (found != null) {
                return found;
            }
        }
        for (String dir : COMMON_DIRS) {
            String found = tryPath(dir, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String tryPath(String dir, String name) {
        try {
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && (Platform.windows() || Files.isExecutable(p))) {
                return p.toString();
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** 生成一小段纯红测试视频（1 秒 320x240），用来探测模型能不能直接看视频。 */
    public byte[] sampleVideo() throws ToolFailure {
        String exe = ffmpeg();
        if (exe == null) {
            throw new ToolFailure("这台电脑上找不到 ffmpeg，没法探测视频能力");
        }
        Path dir = temp.resolve("probe-" + UUID.randomUUID());
        try {
            Files.createDirectories(dir);
            Path out = dir.resolve("probe.mp4");
            Sandbox.Result result = run(exe, dir, List.of(exe, "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "lavfi", "-i", "color=c=red:s=320x240:d=1",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", out.toString()));
            if (!Files.isRegularFile(out)) {
                throw new ToolFailure("生成探测视频失败" + (result.stderr().isBlank() ? "" : "：" + result.stderr().strip()));
            }
            return Files.readAllBytes(out);
        } catch (IOException e) {
            throw new ToolFailure("生成探测视频失败：" + e.getMessage());
        } finally {
            delete(dir);
        }
    }

    public Frames extract(String url) throws ToolFailure {
        Config config = config();
        if (!config.enabled()) {
            throw new ToolFailure("看视频关着");
        }
        String exe = ffmpeg();
        if (exe == null) {
            throw new ToolFailure("这台电脑上找不到 ffmpeg，看不了视频");
        }
        try {
            if (!SLOTS.tryAcquire(30, TimeUnit.SECONDS)) {
                throw new ToolFailure("同时在看的视频太多了");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("被打断了");
        }
        Path dir = temp.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(dir);
            Path video = dir.resolve("video");
            PublicWeb.save(url, video, MAX_BYTES, DOWNLOAD);
            double duration = probe(exe, dir, video);
            // 看多长：maxSeconds=0 表示整段
            double span;
            if (config.maxSeconds() == 0) {
                span = duration > 0 ? duration : 60;
            } else {
                span = duration > 0 ? Math.min(duration, config.maxSeconds()) : config.maxSeconds();
            }
            // 关键：视频太长时不截断，而是把间隔拉大，保证整段都被均匀看到
            double effSpf = Math.max(config.secondsPerFrame(), span / config.maxFrames());
            int count = Math.max(1, (int) Math.min(Math.ceil(span / effSpf), config.maxFrames()));
            double fps = 1.0 / effSpf;
            Sandbox.Result result = run(exe, dir, List.of(exe, "-hide_banner", "-loglevel", "error", "-nostdin", "-threads", "2",
                    "-t", number(span), "-i", video.toString(),
                    "-vf", "fps=" + number(fps) + ",scale=w=768:h=768:force_original_aspect_ratio=decrease",
                    "-frames:v", String.valueOf(count), "-q:v", "4", dir.resolve("f%02d.jpg").toString()));
            List<ImagePart> parts = new ArrayList<>();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path frame : files.filter(p -> p.getFileName().toString().matches("f\\d+\\.jpg")).sorted(Comparator.naturalOrder()).toList()) {
                    parts.add(ImageLoader.fromBytes(Files.readAllBytes(frame)));
                }
            }
            if (parts.isEmpty()) {
                throw new ToolFailure("ffmpeg 没截出画面" + (result.stderr().isBlank() ? "" : "：" + result.stderr().strip()));
            }
            String length = duration > 0 ? "视频长约 " + Math.round(duration) + " 秒，" : "";
            String part = config.maxSeconds() > 0 && duration > config.maxSeconds()
                    ? "只看了前 " + Math.round(span) + " 秒，" : "";
            String density = effSpf > config.secondsPerFrame() + 0.01
                    ? "视频较长，间隔自动拉大到每 " + number(effSpf) + " 秒一张铺满整段，" : "";
            return new Frames(parts, "（附上的 " + parts.size() + " 张图是那段视频按时间顺序截的画面，" + length + part + density
                    + "大约每隔 " + number(Math.max(span / parts.size(), 0.1)) + " 秒一张；视频里的声音听不到）");
        } catch (IOException e) {
            throw new ToolFailure("看视频失败：" + e.getMessage());
        } catch (AgentException e) {
            throw new ToolFailure("视频截图读不出来：" + e.getMessage());
        } finally {
            delete(dir);
            SLOTS.release();
        }
    }

    private double probe(String exe, Path dir, Path video) throws ToolFailure {
        Sandbox.Result result = run(exe, dir, List.of(exe, "-hide_banner", "-nostdin", "-i", video.toString()));
        Matcher m = DURATION.matcher(result.stderr());
        return m.find() ? Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3)) : -1;
    }

    private Sandbox.Result run(String exe, Path dir, List<String> argv) throws ToolFailure {
        Sandbox sandbox = Sandboxes.current();
        boolean isolated = sandbox.missing() == null;
        Map<String, String> env = isolated ? sandbox.environment(dir, List.of(Path.of(exe).getParent())) : new HashMap<>(System.getenv());
        Sandbox.Spec spec = Sandbox.Spec.exec(argv, dir, env)
                .isolated(isolated, "", false)
                .reading(List.of(), Sandboxes.runtimeRoots(Path.of(exe)))
                .writing(List.of(temp))
                .limits(1024L << 20, 50, 16);
        return sandbox.run(spec, RUN_MS);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value).replaceAll("\\.?0+$", "");
    }

    private static void delete(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
        }
    }
}
