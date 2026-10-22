package dafeiyu.agent.memory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SkillPack {

    public record Entry(String name, String description, String content, boolean truncated) {}

    public record Outcome(List<String> added, List<String> overwritten, List<String> skipped, List<String> truncated) {}

    public enum Mode { ASK, OVERWRITE, SKIP }

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_FILE = 256 * 1024;
    private static final long MAX_TOTAL = 20L * 1024 * 1024;
    private static final Pattern KEY = Pattern.compile("^([A-Za-z_][\\w-]*):\\s*(.*)$");
    private static final Pattern HEADING = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

    private SkillPack() {}

    public static List<Entry> read(byte[] data, String fileName) {
        if (data.length > MAX_BYTES) {
            throw new IllegalArgumentException("文件太大了，技能包不能超过 5 MB");
        }
        List<Entry> found = new ArrayList<>();
        if (isZip(data)) {
            try {
                found.addAll(fromZip(data, StandardCharsets.UTF_8));
            } catch (IllegalStateException e) {
                found.addAll(fromZip(data, Charset.forName("GBK")));
            }
        } else {
            Entry e = parse(new String(data, StandardCharsets.UTF_8), stem(fileName));
            if (e == null) {
                throw new IllegalArgumentException("这个文件里没有技能名字或内容：开头写 name:，或者用 # 标题当名字");
            }
            found.add(e);
        }
        Map<String, Entry> unique = new LinkedHashMap<>();
        found.forEach(e -> unique.putIfAbsent(e.name().toLowerCase(Locale.ROOT), e));
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("没有找到能导入的技能：需要 SKILL.md，或者装着 SKILL.md 的 zip");
        }
        return List.copyOf(unique.values());
    }

    public static List<String> conflicts(SkillStore store, String personaId, List<Entry> entries) {
        return entries.stream().filter(e -> store.named(personaId, e.name()) != null).map(Entry::name).toList();
    }

    public static Outcome save(SkillStore store, String personaId, List<Entry> entries, Mode mode) {
        List<String> added = new ArrayList<>();
        List<String> overwritten = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> truncated = new ArrayList<>();
        for (Entry e : entries) {
            boolean exists = store.named(personaId, e.name()) != null;
            if (exists && mode != Mode.OVERWRITE) {
                skipped.add(e.name());
                continue;
            }
            store.add(personaId, e.name(), e.description(), e.content(), "import");
            (exists ? overwritten : added).add(e.name());
            if (e.truncated()) {
                truncated.add(e.name());
            }
        }
        return new Outcome(added, overwritten, skipped, truncated);
    }

    static Entry parse(String raw, String fallback) {
        String text = raw.replace("\r\n", "\n");
        if (!text.isEmpty() && text.charAt(0) == (char) 0xFEFF) {
            text = text.substring(1);
        }
        String name = "";
        String description = "";
        String body = text;
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) {
                Map<String, String> meta = frontmatter(text.substring(4, end));
                name = meta.getOrDefault("name", "");
                description = meta.getOrDefault("description", "");
                int after = text.indexOf('\n', end + 4);
                body = after < 0 ? "" : text.substring(after + 1);
            }
        }
        if (name.isBlank()) {
            Matcher h = HEADING.matcher(body);
            if (h.find()) {
                name = h.group(1).strip();
            }
        }
        if (name.isBlank()) {
            name = fallback;
        }
        String content = body.replaceAll("\n{3,}", "\n\n").strip();
        if (name.isBlank() || content.isEmpty()) {
            return null;
        }
        return new Entry(name.strip(), description.strip(), content, content.length() > SkillStore.MAX_CONTENT);
    }

    private static Map<String, String> frontmatter(String block) {
        Map<String, String> out = new HashMap<>();
        String key = null;
        StringBuilder value = new StringBuilder();
        for (String line : block.split("\n")) {
            Matcher m = KEY.matcher(line);
            if (m.matches()) {
                if (key != null) {
                    out.put(key, unquote(value.toString()));
                }
                key = m.group(1).toLowerCase(Locale.ROOT);
                String v = m.group(2).strip();
                value.setLength(0);
                if (!v.matches("[>|][+-]?")) {
                    value.append(v);
                }
            } else if (key != null && (line.startsWith(" ") || line.startsWith("\t")) && !line.isBlank()) {
                value.append(value.isEmpty() ? "" : " ").append(line.strip());
            }
        }
        if (key != null) {
            out.put(key, unquote(value.toString()));
        }
        return out;
    }

    private static String unquote(String v) {
        String s = v.strip();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static List<Entry> fromZip(byte[] data, Charset names) {
        List<Entry> out = new ArrayList<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data), names)) {
            ZipEntry e;
            while ((e = nextEntry(zip)) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new IllegalArgumentException("压缩包里文件太多了（超过 " + MAX_ENTRIES + " 个）");
                }
                String path = e.getName().replace('\\', '/');
                String file = path.substring(path.lastIndexOf('/') + 1);
                if (e.isDirectory() || !file.equalsIgnoreCase("SKILL.md")) {
                    continue;
                }
                byte[] bytes = zip.readNBytes(MAX_FILE + 1);
                if (bytes.length > MAX_FILE) {
                    throw new IllegalArgumentException(path + " 太大了，单个 SKILL.md 不能超过 256 KB");
                }
                total += bytes.length;
                if (total > MAX_TOTAL) {
                    throw new IllegalArgumentException("压缩包解开后太大了");
                }
                String folder = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
                Entry entry = parse(new String(bytes, StandardCharsets.UTF_8), folder.substring(folder.lastIndexOf('/') + 1));
                if (entry != null) {
                    out.add(entry);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("压缩包读不了：" + e.getMessage());
        }
        return out;
    }

    private static ZipEntry nextEntry(ZipInputStream zip) throws IOException {
        try {
            return zip.getNextEntry();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("bad entry name", e);
        }
    }

    private static boolean isZip(byte[] data) {
        return data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
    }

    private static String stem(String fileName) {
        String name = fileName == null ? "" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.equalsIgnoreCase("SKILL") ? "" : stem;
    }
}
