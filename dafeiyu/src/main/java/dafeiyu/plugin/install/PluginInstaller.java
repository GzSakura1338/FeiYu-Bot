package dafeiyu.plugin.install;

import dafeiyu.log.LogBuffer;
import dafeiyu.plugin.PluginHost;
import dafeiyu.plugin.PluginView;
import dafeiyu.plugin.manifest.Permission;
import dafeiyu.plugin.manifest.PluginManifest;
import dafeiyu.plugin.manifest.PluginVersion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class PluginInstaller {

    public record Staged(String token, PluginManifest manifest, String source, String installedVersion, boolean downgrade,
                         List<Permission> newPermissions, long at) {}

    public static final long MAX_DOWNLOAD = 50L * 1024 * 1024;
    public static final long MAX_UNPACKED = 100L * 1024 * 1024;
    public static final int MAX_FILES = 2000;
    private static final long STAGE_TTL_MS = 60 * 60_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private record Entry(Staged staged, Path folder, Path root) {}

    private final PluginHost host;
    private final Path staging;
    private final LogBuffer log;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public PluginInstaller(PluginHost host, Path staging, LogBuffer log) {
        this.host = host;
        this.staging = staging.toAbsolutePath().normalize();
        this.log = log;
    }

    public Staged stageZip(byte[] zip, String source) throws IOException {
        cleanup();
        if (zip.length > MAX_DOWNLOAD) {
            throw new IllegalArgumentException("压缩包太大了（最多 " + MAX_DOWNLOAD / 1024 / 1024 + " MB）");
        }
        if (zip.length < 4 || zip[0] != 'P' || zip[1] != 'K') {
            throw new IllegalArgumentException("这不是 zip 压缩包");
        }
        checkCentralDirectory(zip);
        String token = UUID.randomUUID().toString();
        Path root = staging.resolve(token);
        Files.createDirectories(root);
        try {
            unzip(zip, root);
            return stage(token, root, locate(root), source);
        } catch (IOException | RuntimeException e) {
            delete(root);
            throw e;
        }
    }

    public Staged stageUrl(String url) throws IOException, InterruptedException {
        URI uri = uri(url);
        if (uri.getHost() != null && uri.getHost().equalsIgnoreCase("github.com") && !uri.getPath().endsWith(".zip")) {
            return stageGit(url);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).header("User-Agent", "FeiYu Bot plugin installer").GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IllegalArgumentException("下载失败：HTTP " + response.statusCode());
        }
        byte[] data;
        try (InputStream in = response.body()) {
            data = in.readNBytes((int) MAX_DOWNLOAD + 1);
        }
        if (data.length > MAX_DOWNLOAD) {
            throw new IllegalArgumentException("下载的文件太大了（最多 " + MAX_DOWNLOAD / 1024 / 1024 + " MB）");
        }
        return stageZip(data, url);
    }

    public Staged stageGit(String url) throws IOException, InterruptedException {
        cleanup();
        URI uri = uri(url);
        String token = UUID.randomUUID().toString();
        Path root = staging.resolve(token);
        Files.createDirectories(root);
        Path repo = root.resolve("repo");
        String out = clone(uri, repo, true);
        if (out != null && out.contains("shallow")) {
            delete(repo);
            out = clone(uri, repo, false);
        }
        if (out != null) {
            delete(root);
            throw new IllegalArgumentException("Git 下载失败：" + last(out));
        }
        delete(repo.resolve(".git"));
        try {
            checkTree(repo);
            return stage(token, root, locate(repo), url);
        } catch (IOException | RuntimeException e) {
            delete(root);
            throw e;
        }
    }

    private static String clone(URI uri, Path repo, boolean shallow) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.symlinks=false", "clone"));
        if (shallow) {
            command.addAll(List.of("--depth", "1"));
        }
        command.addAll(List.of("--", uri.toString(), repo.toString()));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(3, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return "timed out";
        }
        return p.exitValue() == 0 ? null : out;
    }

    private static URI uri(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("网址格式不对");
        }
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException("只支持 http:// 或 https:// 的网址");
        }
        return uri;
    }

    private Staged stage(String token, Path root, Path folder, String source) {
        PluginManifest manifest;
        try {
            manifest = PluginManifest.parse(Files.readString(folder.resolve(PluginManifest.FILE), StandardCharsets.UTF_8), null);
        } catch (PluginManifest.Invalid e) {
            throw new IllegalArgumentException("插件清单有问题：" + String.join("；", e.problems()));
        } catch (IOException e) {
            throw new IllegalArgumentException("读不到 plugin.json：" + e.getMessage());
        }
        if (!manifest.hostSatisfied(PluginHost.VERSION)) {
            throw new IllegalArgumentException("这个插件需要主程序版本 " + manifest.host() + "，现在是 " + PluginHost.VERSION);
        }
        PluginView existing = host.views().stream().filter(v -> v.id().equals(manifest.id())).findFirst().orElse(null);
        String installed = existing == null || existing.manifest() == null ? "" : existing.manifest().version();
        boolean downgrade = !installed.isEmpty() && PluginVersion.parse(manifest.version()).compareTo(PluginVersion.parse(installed)) < 0;
        Set<Permission> approved = existing == null ? Set.of() : existing.approved();
        List<Permission> fresh = manifest.permissions().stream().filter(p -> !approved.contains(p)).sorted().toList();
        Staged staged = new Staged(token, manifest, source, installed, downgrade, fresh, System.currentTimeMillis());
        entries.put(token, new Entry(staged, folder, root));
        return staged;
    }

    public Staged staged(String token) {
        Entry e = entries.get(token);
        return e == null ? null : e.staged();
    }

    public synchronized String confirm(String token, Set<Permission> approve, boolean enable) throws IOException {
        Entry e = entries.remove(token);
        if (e == null) {
            throw new IllegalArgumentException("安装已过期，请重新上传");
        }
        PluginManifest m = e.staged().manifest();
        Path target = host.pluginsDir().resolve(m.id());
        PluginView existing = host.views().stream().filter(v -> v.id().equals(m.id())).findFirst().orElse(null);
        if (existing != null && !existing.folder().equals(target)) {
            delete(e.root());
            throw new IllegalArgumentException("已经有一个 id 为「" + m.id() + "」的" + ("example".equals(existing.origin()) ? "示例" : "") + "插件了");
        }
        boolean wasEnabled = existing != null && existing.enabled();
        if (existing != null) {
            host.disable(m.id());
        }
        Path backup = null;
        if (Files.exists(target)) {
            backup = staging.resolve(token + "-previous");
            Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(e.folder(), target);
        } catch (IOException ex) {
            if (backup != null) {
                Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            }
            throw ex;
        } finally {
            delete(e.root());
        }
        if (backup != null) {
            delete(backup);
        }
        host.scan();
        log.info("plugins: installed " + m.id() + " " + m.version() + " from " + e.staged().source());
        if (enable || wasEnabled) {
            host.enable(m.id(), approve);
        }
        return m.id();
    }

    public void cancel(String token) {
        Entry e = entries.remove(token);
        if (e != null) {
            delete(e.root());
        }
    }

    public synchronized void uninstall(String id, boolean deleteData) {
        PluginView view = host.view(id);
        if (!"installed".equals(view.origin())) {
            throw new IllegalArgumentException("示例插件不能卸载，可以停用");
        }
        host.disable(id);
        host.forgetSlot(id);
        delete(view.folder());
        host.store().forget(id, deleteData);
        if (deleteData) {
            delete(host.dataDir().resolve(id));
        }
        host.scan();
        log.info("plugins: uninstalled " + id + (deleteData ? " and its data" : ""));
    }

    private static Path locate(Path root) throws IOException {
        if (Files.isRegularFile(root.resolve(PluginManifest.FILE))) {
            return root;
        }
        List<Path> dirs;
        try (Stream<Path> list = Files.list(root)) {
            dirs = list.filter(Files::isDirectory).toList();
        }
        for (Path dir : dirs) {
            if (Files.isRegularFile(dir.resolve(PluginManifest.FILE))) {
                return dir;
            }
        }
        if (dirs.size() == 1) {
            return locate(dirs.getFirst());
        }
        throw new IllegalArgumentException("压缩包里没有 plugin.json，这不是 FeiYu Bot 插件");
    }

    static void unzip(byte[] zip, Path root) throws IOException {
        long total = 0;
        int files = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = in.getNextEntry()) != null) {
                Path target = safePath(root, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (++files > MAX_FILES) {
                    throw new IllegalArgumentException("压缩包里文件太多了（最多 " + MAX_FILES + " 个）");
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_UNPACKED) {
                            throw new IllegalArgumentException("解压后太大了（超过 " + MAX_UNPACKED / 1024 / 1024 + " MB），可能是压缩炸弹");
                        }
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    static Path safePath(Path root, String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.matches("^[A-Za-z]:.*") || n.contains("\0")) {
            throw new IllegalArgumentException("压缩包里有绝对路径，拒绝安装：" + name);
        }
        for (String part : n.split("/")) {
            if (part.equals("..")) {
                throw new IllegalArgumentException("压缩包里有 ../ 路径，拒绝安装：" + name);
            }
        }
        Path target = root.resolve(n).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("压缩包里有跳出目录的路径，拒绝安装：" + name);
        }
        return target;
    }

    static void checkCentralDirectory(byte[] zip) {
        ByteBuffer b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 65535); i--) {
            if (b.getInt(i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new IllegalArgumentException("zip 文件损坏（找不到目录）");
        }
        int count = Short.toUnsignedInt(b.getShort(eocd + 10));
        long offset = Integer.toUnsignedLong(b.getInt(eocd + 16));
        if (count > MAX_FILES) {
            throw new IllegalArgumentException("压缩包里文件太多了（最多 " + MAX_FILES + " 个）");
        }
        long declared = 0;
        int pos = (int) offset;
        for (int i = 0; i < count; i++) {
            if (pos < 0 || pos + 46 > zip.length || b.getInt(pos) != 0x02014b50) {
                throw new IllegalArgumentException("zip 文件损坏（目录项不对）");
            }
            int madeBy = Short.toUnsignedInt(b.getShort(pos + 4)) >> 8;
            long size = Integer.toUnsignedLong(b.getInt(pos + 24));
            int nameLength = Short.toUnsignedInt(b.getShort(pos + 28));
            int extraLength = Short.toUnsignedInt(b.getShort(pos + 30));
            int commentLength = Short.toUnsignedInt(b.getShort(pos + 32));
            int external = b.getInt(pos + 38);
            String name = new String(zip, pos + 46, Math.min(nameLength, zip.length - pos - 46), StandardCharsets.UTF_8);
            if (madeBy == 3 && ((external >>> 16) & 0xF000) == 0xA000) {
                throw new IllegalArgumentException("压缩包里有符号链接，拒绝安装：" + name);
            }
            declared += size;
            if (declared > MAX_UNPACKED) {
                throw new IllegalArgumentException("解压后太大了（超过 " + MAX_UNPACKED / 1024 / 1024 + " MB），可能是压缩炸弹");
            }
            pos += 46 + nameLength + extraLength + commentLength;
        }
    }

    private static void checkTree(Path root) throws IOException {
        int files = 0;
        long total = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.toList()) {
                if (Files.isSymbolicLink(p)) {
                    throw new IllegalArgumentException("仓库里有符号链接，拒绝安装：" + root.relativize(p));
                }
                if (Files.isRegularFile(p)) {
                    files++;
                    total += Files.size(p);
                }
            }
        }
        if (files > MAX_FILES || total > MAX_UNPACKED) {
            throw new IllegalArgumentException("仓库太大了（最多 " + MAX_FILES + " 个文件、" + MAX_UNPACKED / 1024 / 1024 + " MB）");
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        entries.values().removeIf(e -> {
            if (now - e.staged().at() > STAGE_TTL_MS) {
                delete(e.root());
                return true;
            }
            return false;
        });
    }

    static void delete(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String last(String output) {
        List<String> lines = new ArrayList<>(List.of(output.strip().split("\\R")));
        return lines.isEmpty() ? "" : lines.getLast();
    }
}
