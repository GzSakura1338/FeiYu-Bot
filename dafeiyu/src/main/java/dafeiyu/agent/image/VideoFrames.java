package dafeiyu.agent.image;

import com.google.gson.JsonObject;
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
import dafeiyu.util.Jsons;

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

    public record Config(int intervalMs) {

        public Config {
            intervalMs = Math.max(0, intervalMs);
        }

        public boolean enabled() {
            return intervalMs > 0;
        }
    }

    public record Frames(List<ImagePart> parts, String note) {}

    private record Probe(double seconds, String size, double fps, boolean audio) {}

    private static final int DEFAULT_INTERVAL_MS = 1000;
    private static final String KEY = "agent.video";
    private static final Duration DOWNLOAD = Duration.ofMinutes(30);
    private static final long RUN_BASE_MS = 120_000;
    private static final Pattern DURATION = Pattern.compile("Duration:\\s*(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    private static final Pattern VIDEO_STREAM = Pattern.compile("Stream #[^\\n]*Video:[^\\n]*");
    private static final Pattern SIZE = Pattern.compile("\\b(\\d{2,5})x(\\d{2,5})\\b");
    private static final Pattern FPS = Pattern.compile("(\\d+(?:\\.\\d+)?) fps");
    private static final Pattern AUDIO_STREAM = Pattern.compile("Stream #[^\\n]*Audio:");
    private static final Semaphore SLOTS = new Semaphore(2);

    private final Settings settings;
    private final Path temp;
    private volatile String ffmpeg;
    private volatile boolean located;

    public VideoFrames(Database db) {
        this.settings = new Settings(db);
        this.temp = db.file().toAbsolutePath().getParent().resolve("video-tmp");
    }

    public Config config() {
        JsonObject saved = Jsons.parseObject(settings.get(KEY));
        if (saved != null) {
            try {
                if (saved.has("intervalMs")) {
                    return new Config(saved.get("intervalMs").getAsInt());
                }
                if (saved.has("frames") && saved.get("frames").getAsInt() <= 0) {
                    return new Config(0);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return new Config(DEFAULT_INTERVAL_MS);
    }

    public synchronized Config save(int intervalMs) {
        Config next = new Config(intervalMs);
        JsonObject o = new JsonObject();
        o.addProperty("intervalMs", next.intervalMs());
        settings.put(KEY, o.toString());
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
            if (!SLOTS.tryAcquire(10, TimeUnit.MINUTES)) {
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
            PublicWeb.save(url, video, Long.MAX_VALUE, DOWNLOAD);
            Probe probe = probe(exe, dir, video);
            double wanted = 1000.0 / config.intervalMs();
            double fps = probe.fps() > 0 ? Math.min(wanted, probe.fps()) : wanted;
            long expected = probe.seconds() > 0 ? (long) Math.ceil(probe.seconds() * fps) : 0;
            int side = expected <= 300 ? 1024 : expected <= 600 ? 768 : expected <= 1200 ? 512 : 384;
            long runMs = probe.seconds() > 0 ? RUN_BASE_MS + (long) (probe.seconds() * 2000) : TimeUnit.MINUTES.toMillis(30);
            Sandbox.Result result = run(exe, dir, List.of(exe, "-hide_banner", "-loglevel", "error", "-nostdin", "-threads", "2",
                    "-i", video.toString(),
                    "-vf", "fps=" + number(fps) + ",scale=w=" + side + ":h=" + side + ":force_original_aspect_ratio=decrease",
                    "-q:v", "4", dir.resolve("f%06d.jpg").toString()), runMs);
            List<ImagePart> parts = new ArrayList<>();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path frame : files.filter(p -> p.getFileName().toString().matches("f\\d+\\.jpg")).sorted(Comparator.naturalOrder()).toList()) {
                    parts.add(ImageLoader.fromBytes(Files.readAllBytes(frame)));
                }
            }
            if (parts.isEmpty()) {
                throw new ToolFailure("ffmpeg 没截出画面" + (result.stderr().isBlank() ? "" : "：" + result.stderr().strip()));
            }
            return new Frames(parts, note(parts.size(), probe, 1.0 / fps, side));
        } catch (IOException e) {
            throw new ToolFailure("看视频失败：" + e.getMessage());
        } catch (AgentException e) {
            throw new ToolFailure("视频截图读不出来：" + e.getMessage());
        } finally {
            delete(dir);
            SLOTS.release();
        }
    }

    private static String note(int count, Probe probe, double step, int side) {
        StringBuilder out = new StringBuilder("（附上的 ").append(count).append(" 张图是这段视频从头到尾按时间顺序截的画面");
        if (probe.seconds() > 0) {
            out.append("，视频长 ").append(clock(probe.seconds()));
        }
        if (!probe.size().isEmpty()) {
            out.append("，原画面 ").append(probe.size());
        }
        out.append("；每隔 ").append(number(step)).append(" 秒截一张，第 k 张大约在第 (k-1)×").append(number(step)).append(" 秒");
        if (count > 1) {
            out.append("（最后一张约在 ").append(clock(step * (count - 1))).append("）");
        }
        out.append("；截图最长边缩到 ").append(side).append(" 像素");
        out.append(probe.audio() ? "；视频有声音，但你听不到，只能看画面" : "；这段视频没有声音").append("）");
        return out.toString();
    }

    private static String clock(double seconds) {
        long total = Math.round(seconds);
        return total >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
                : String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    private Probe probe(String exe, Path dir, Path video) throws ToolFailure {
        String info = run(exe, dir, List.of(exe, "-hide_banner", "-nostdin", "-i", video.toString()), RUN_BASE_MS).stderr();
        Matcher d = DURATION.matcher(info);
        double seconds = d.find() ? Integer.parseInt(d.group(1)) * 3600 + Integer.parseInt(d.group(2)) * 60 + Double.parseDouble(d.group(3)) : -1;
        Matcher v = VIDEO_STREAM.matcher(info);
        String size = "";
        double fps = -1;
        if (v.find()) {
            Matcher s = SIZE.matcher(v.group());
            size = s.find() ? s.group(1) + "×" + s.group(2) : "";
            Matcher f = FPS.matcher(v.group());
            fps = f.find() ? Double.parseDouble(f.group(1)) : -1;
        }
        return new Probe(seconds, size, fps, AUDIO_STREAM.matcher(info).find());
    }

    private Sandbox.Result run(String exe, Path dir, List<String> argv, long runMs) throws ToolFailure {
        Sandbox sandbox = Sandboxes.current();
        boolean isolated = sandbox.missing() == null;
        Map<String, String> env = isolated ? sandbox.environment(dir, List.of(Path.of(exe).getParent())) : new HashMap<>(System.getenv());
        Sandbox.Spec spec = Sandbox.Spec.exec(argv, dir, env)
                .isolated(isolated, "", false)
                .reading(List.of(), Sandboxes.runtimeRoots(Path.of(exe)))
                .writing(List.of(temp))
                .limits(1024L << 20, 50, 16);
        return sandbox.run(spec, runMs);
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
