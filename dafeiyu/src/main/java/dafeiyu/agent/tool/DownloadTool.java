package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DownloadTool extends TextTool {

    static final long MAX_BYTES = 200L * 1024 * 1024;

    private static final Duration TOTAL = Duration.ofMinutes(10);
    private static final String FOLDER = "下载";
    private static final int MAX_NAME = 120;
    private static final Pattern STAR_NAME = Pattern.compile("(?i)filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)");
    private static final Pattern PLAIN_NAME = Pattern.compile("(?i)filename\\s*=\\s*\"?([^\";]+)\"?");
    private static final Pattern BAD_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("application/zip", "zip"), Map.entry("application/x-zip-compressed", "zip"), Map.entry("application/pdf", "pdf"),
            Map.entry("application/json", "json"), Map.entry("application/x-7z-compressed", "7z"), Map.entry("application/x-rar-compressed", "rar"),
            Map.entry("application/java-archive", "jar"), Map.entry("application/gzip", "gz"), Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"), Map.entry("image/gif", "gif"), Map.entry("image/webp", "webp"), Map.entry("text/html", "html"),
            Map.entry("text/plain", "txt"), Map.entry("text/csv", "csv"), Map.entry("audio/mpeg", "mp3"), Map.entry("video/mp4", "mp4"));

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "url": {"type": "string", "description": "The public http(s) URL of the file to download"},
              "path": {"type": "string", "description": "Optional. Where to save it in the working directory: a folder (ending with /) or a full file path. Defaults to 下载/ with the file's own name"}
            }, "required": ["url"]}""");

    @Override
    public String name() {
        return "download";
    }

    @Override
    public String description(ToolContext context) {
        return "Download a file from a public URL into the working directory (by default into the 下载/ folder, keeping the file's own name). "
                + "Use it whenever you need the file itself rather than a web page's text: someone shares a link to an installer, archive, mod, image, "
                + "PDF or dataset, or a task needs a resource from the internet. Afterwards you can read it, unpack or run it with shell if available, "
                + "or deliver it with send_file. Only public http/https addresses are allowed, files up to " + MAX_BYTES / 1024 / 1024
                + " MB, and an existing file is never overwritten unless you give its exact path.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    protected String text(Args args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        if (ws == null) {
            throw new ToolFailure("No working directory is set, so there is nowhere to save the download.");
        }
        String url = args.string("url").strip();
        if (url.isEmpty()) {
            throw new ToolFailure("url is required");
        }
        String path = args.text("path");
        Path temp;
        try {
            temp = Files.createTempFile("dafeiyu-download-", ".part");
        } catch (IOException e) {
            throw new ToolFailure("Unable to create a temporary file: " + e.getMessage());
        }
        try {
            PublicWeb.Saved saved = PublicWeb.save(url, temp, MAX_BYTES, TOTAL);
            if (saved.size() == 0) {
                throw new ToolFailure("The server returned an empty file.");
            }
            boolean exact = !path.isEmpty() && !path.endsWith("/") && !path.endsWith("\\") && !Files.isDirectory(ws.resolve(path));
            Path target = exact ? ws.resolve(path) : unique(ws.resolve((path.isEmpty() ? FOLDER : path.replaceAll("[/\\\\]+$", "")) + "/" + fileName(saved)));
            ws.place(target, temp);
            String note = saved.type().startsWith("text/html") && !fileName(saved).toLowerCase(Locale.ROOT).endsWith(".html")
                    ? "\nNote: the server sent a web page (text/html), not a file. The link may need a login, a captcha or a direct-download address; check it with read or webfetch."
                    : "";
            return "Downloaded " + saved.uri() + " to " + ws.relative(target) + " (" + size(saved.size())
                    + (saved.type().isEmpty() ? "" : ", " + saved.type().replaceFirst(";.*", "")) + ")." + note;
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    static String fileName(PublicWeb.Saved saved) {
        String name = "";
        Matcher star = STAR_NAME.matcher(saved.disposition());
        Matcher plain = PLAIN_NAME.matcher(saved.disposition());
        if (star.find()) {
            name = decode(star.group(2).strip(), star.group(1).isBlank() ? "UTF-8" : star.group(1).strip());
        } else if (plain.find()) {
            name = plain.group(1).strip();
        }
        if (name.isBlank()) {
            String last = saved.uri().getPath() == null ? "" : saved.uri().getPath().replaceAll("/+$", "");
            name = decode(last.substring(last.lastIndexOf('/') + 1), "UTF-8");
        }
        name = BAD_CHARS.matcher(name).replaceAll("_").strip().replaceAll("[. ]+$", "");
        if (name.isEmpty() || name.equals("_")) {
            name = "download";
        }
        if (!name.contains(".")) {
            String extension = EXTENSIONS.get(saved.type().replaceFirst(";.*", "").strip());
            name += extension == null ? "" : "." + extension;
        }
        if (name.length() > MAX_NAME) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, MAX_NAME - extension.length()) + extension;
        }
        return name;
    }

    private static String decode(String text, String charset) {
        try {
            return URLDecoder.decode(text.replace("+", "%2B"), charset);
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
            return text;
        }
    }

    private static Path unique(Path target) {
        if (!Files.exists(target)) {
            return target;
        }
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path next = target.resolveSibling(base + " (" + i + ")" + extension);
            if (!Files.exists(next)) {
                return next;
            }
        }
    }

    private static String size(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024) : Math.max(1, bytes / 1024) + " KB";
    }
}
