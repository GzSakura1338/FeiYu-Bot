package dafeiyu.agent.sticker;

import dafeiyu.db.Database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dafeiyu.util.Texts.abbreviate;

public final class StickerStore {

    public record Sticker(long id, String hash, String source, String url, String file, String mime, String description, String status,
                          boolean hidden, int seenCount, int sentCount, long firstSeen, long lastSeen, long lastSent) {

        public boolean usable() {
            return !hidden && status.equals(READY) && !file.isEmpty();
        }

        public String label() {
            return "[表情包#" + id + (description.isEmpty() || !status.equals(READY) ? "" : "：" + description) + "]";
        }
    }

    static final String NEW = "new";
    static final String SAVED = "saved";
    static final String READY = "ready";
    static final String FAILED = "failed";
    static final String UNSAFE = "unsafe";
    static final Pattern TAG = Pattern.compile(
            "[\\[<【［＜]\\s*表情包\\s*[:：#]\\s*#?\\s*(\\d{1,9})[^\\]>】］＞\\n]{0,40}[\\]>】］＞](?:\\s*[<＜]\\s*/\\s*表情包\\s*[>＞])?");
    public static final int MAX_PER_MESSAGE = 3;
    private static final long RECENT_SENT_MS = 2 * 3_600_000L;
    private static final String COLUMNS = "id, hash, source, url, file, mime, description, status, hidden, seen_count, sent_count, first_seen, last_seen, last_sent";

    private final Database db;
    private final Path dir;

    public StickerStore(Database db, Path dir) {
        this.db = db;
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    synchronized Sticker noticed(String hash, String source, String url) {
        long now = System.currentTimeMillis();
        Sticker old = byHash(hash);
        if (old == null) {
            db.update("INSERT INTO stickers (hash, source, url, seen_count, first_seen, last_seen) VALUES (?, ?, ?, ?, ?, ?)",
                    hash, source, url, source.equals("seen") ? 1 : 0, now, now);
        } else {
            db.update("""
                    UPDATE stickers SET source = CASE WHEN ? = 'favorite' THEN 'favorite' ELSE source END,
                        url = CASE WHEN ? <> '' THEN ? ELSE url END, seen_count = seen_count + ?, last_seen = ?,
                        status = CASE WHEN status = 'failed' AND ? <> '' THEN 'new' ELSE status END
                    WHERE id = ?
                    """, source, url, url, source.equals("seen") ? 1 : 0, now, url, old.id());
        }
        return byHash(hash);
    }

    public Sticker get(long id) {
        return db.one("SELECT " + COLUMNS + " FROM stickers WHERE id = ?", StickerStore::map, id);
    }

    Sticker byHash(String hash) {
        return db.one("SELECT " + COLUMNS + " FROM stickers WHERE hash = ?", StickerStore::map, hash);
    }

    public List<Sticker> list(String filter) {
        String where = switch (filter == null ? "" : filter) {
            case "favorite" -> "WHERE source = 'favorite' AND hidden = 0";
            case "collected" -> "WHERE source = 'collected' AND hidden = 0";
            case "seen" -> "WHERE source = 'seen' AND hidden = 0";
            case "hidden" -> "WHERE hidden = 1 OR status = 'unsafe'";
            default -> "WHERE hidden = 0 AND status <> 'unsafe'";
        };
        return db.list("SELECT " + COLUMNS + " FROM stickers " + where + " ORDER BY source = 'favorite' DESC, source = 'collected' DESC, last_seen DESC LIMIT 600",
                StickerStore::map);
    }

    List<Sticker> pending(int limit) {
        return db.list("SELECT " + COLUMNS + " FROM stickers WHERE status IN ('new', 'saved') AND hidden = 0 ORDER BY source = 'favorite' DESC, last_seen DESC LIMIT ?",
                StickerStore::map, limit);
    }

    List<Sticker> catalog(int limit) {
        return db.list("""
                SELECT %s FROM stickers WHERE status = 'ready' AND hidden = 0 AND file <> ''
                ORDER BY (source IN ('favorite', 'collected')) * 20 - MIN(sent_count, 10) * 4 - (last_sent > ?) * 80 + ABS(RANDOM() %% 60) DESC LIMIT ?
                """.formatted(COLUMNS), StickerStore::map, System.currentTimeMillis() - RECENT_SENT_MS, limit);
    }

    void saved(long id, String file, String mime) {
        db.update("UPDATE stickers SET file = ?, mime = ?, status = CASE WHEN status = 'new' OR status = 'failed' THEN 'saved' ELSE status END WHERE id = ?",
                file, mime, id);
    }

    void described(long id, String description, String status) {
        db.update("UPDATE stickers SET description = ?, status = ? WHERE id = ?", abbreviate(description, 16), status, id);
    }

    void failed(long id) {
        db.update("UPDATE stickers SET status = 'failed' WHERE id = ? AND file = ''", id);
    }

    public void sent(long id) {
        db.update("UPDATE stickers SET sent_count = sent_count + 1, last_sent = ? WHERE id = ?", System.currentTimeMillis(), id);
    }

    public boolean collect(long id) {
        return db.update("UPDATE stickers SET source = 'collected' WHERE id = ? AND source = 'seen'", id) > 0;
    }

    public boolean setHidden(long id, boolean hidden) {
        return db.update("UPDATE stickers SET hidden = ? WHERE id = ?", hidden ? 1 : 0, id) > 0;
    }

    public boolean setDescription(long id, String description) {
        String text = description == null ? "" : description.replaceAll("\\s+", " ").strip();
        return db.update("UPDATE stickers SET description = ?, status = CASE WHEN file <> '' AND ? <> '' AND status <> 'unsafe' THEN 'ready' ELSE status END WHERE id = ?",
                abbreviate(text, 16), text, id) > 0;
    }

    public boolean delete(long id) {
        Sticker sticker = get(id);
        if (sticker == null) {
            return false;
        }
        if (!sticker.file().isEmpty()) {
            try {
                Files.deleteIfExists(dir.resolve(sticker.file()));
            } catch (IOException ignored) {
            }
        }
        return db.update("DELETE FROM stickers WHERE id = ?", id) > 0;
    }

    public Path path(Sticker sticker) {
        return sticker.file().isEmpty() ? null : dir.resolve(sticker.file());
    }

    public String note(int limit) {
        List<Sticker> list = catalog(limit);
        if (list.isEmpty()) {
            return "";
        }
        List<String> items = new ArrayList<>();
        for (Sticker s : list) {
            items.add("#" + s.id() + " " + s.description());
        }
        return "你能发的表情包：" + String.join("、", items) + "\n";
    }

    public static boolean hasTag(String text) {
        return TAG.matcher(text).find();
    }

    public static String limit(String text, LongPredicate usable) {
        Matcher m = TAG.matcher(text);
        StringBuilder out = new StringBuilder();
        int kept = 0;
        while (m.find()) {
            long id = Long.parseLong(m.group(1));
            boolean keep = kept < MAX_PER_MESSAGE && usable.test(id);
            m.appendReplacement(out, keep ? Matcher.quoteReplacement("[表情包:" + id + "]") : "");
            kept += keep ? 1 : 0;
        }
        m.appendTail(out);
        return out.toString().replaceAll("\\s{2,}", " ").strip();
    }

    public static List<Long> tags(String text) {
        List<Long> ids = new ArrayList<>();
        Matcher m = TAG.matcher(text);
        while (m.find()) {
            ids.add(Long.parseLong(m.group(1)));
        }
        return ids;
    }

    public record Piece(String text, long sticker) {

        public boolean isSticker() {
            return sticker > 0;
        }
    }

    public static List<Piece> pieces(String text) {
        List<Piece> out = new ArrayList<>();
        Matcher m = TAG.matcher(text);
        int last = 0;
        while (m.find()) {
            addText(out, text.substring(last, m.start()));
            out.add(new Piece("", Long.parseLong(m.group(1))));
            last = m.end();
        }
        addText(out, text.substring(last));
        return out;
    }

    private static void addText(List<Piece> out, String text) {
        String clean = text.replaceAll("[ \\t]{2,}", " ").strip();
        if (!clean.isEmpty()) {
            out.add(new Piece(clean, 0));
        }
    }

    public static String withoutTags(String text) {
        return TAG.matcher(text).replaceAll("").replaceAll("\\s{2,}", " ").strip();
    }

    private static Sticker map(ResultSet rs) throws SQLException {
        return new Sticker(rs.getLong("id"), rs.getString("hash"), rs.getString("source"), rs.getString("url"), rs.getString("file"),
                rs.getString("mime"), rs.getString("description"), rs.getString("status"), rs.getInt("hidden") == 1, rs.getInt("seen_count"),
                rs.getInt("sent_count"), rs.getLong("first_seen"), rs.getLong("last_seen"), rs.getLong("last_sent"));
    }
}
