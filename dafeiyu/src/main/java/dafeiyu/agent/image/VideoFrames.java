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

    public record Config(int frames, int seconds) {

        public Config {
            frames = frames <= 0 ? 0 : Math.min(frames, MAX_FRAMES);
            seconds = seconds <= 0 ? DEFAULT_SECONDS : Math.clamp(seconds, MIN_SECONDS, MAX_SECONDS);
        }

        public boolean enabled() {
            return frames > 0;
        }
    }

    public record Frames(List<ImagePart> parts, String note) {}

    public static final int MAX_FRAMES = 16;
    public static final int MIN_SECONDS = 5;
    public static final int MAX_SECONDS = 300;
    private static final int DEFAULT_FRAMES = 8;
    private static final int DEFAULT_SECONDS = 60;
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
                Config saved = GSON.fromJson(raw, Config.class);
                if (saved != null) {
                    return new Config(saved.frames(), saved.seconds());
                }
            } catch (JsonParseException ignored) {
            }
        }
        return new Config(DEFAULT_FRAMES, DEFAULT_SECONDS);
    }

    public synchronized Config save(Integer frames, Integer seconds) {
        Config old = config();
        Config next = new Config(frames == null ? old.frames() : frames, seconds == null ? old.seconds() : seconds);
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

    private static String locate() {
        String name = Platform.windows() ? "ffmpeg.exe" : "ffmpeg";
        String path = System.getenv().getOrDefault("PATH", System.getenv().getOrDefault("Path", ""));
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(dir.strip(), name);
                if (Files.isRegularFile(p) && (Platform.windows() || Files.isExecutable(p))) {
                    return p.toString();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
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
            double span = duration > 0 ? Math.min(duration, config.seconds()) : config.seconds();
            int count = duration > 0 && duration < config.frames() ? Math.max(1, (int) Math.ceil(duration)) : config.frames();
            double fps = count / Math.max(span, 0.5);
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
            String part = duration > span ? "只看了前 " + Math.round(span) + " 秒，" : "";
            return new Frames(parts, "（附上的 " + parts.size() + " 张图是那段视频按时间顺序截的画面，" + length + part
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
