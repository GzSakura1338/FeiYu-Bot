package dafeiyu.agent.qq;

import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.db.Database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;

final class QqImages {

    private record Row(String file, String url) {}

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final Database db;
    private final QqOutbox outbox;

    QqImages(Database db, QqOutbox outbox) {
        this.db = db;
        this.outbox = outbox;
    }

    String number(String file, String url, String session) {
        String key = file == null ? "" : file.strip();
        String where = session == null ? "" : session;
        long now = System.currentTimeMillis();
        try {
            Long id = db.transaction(c -> {
                if (!key.isEmpty()) {
                    List<Long> found = Database.list(c, "SELECT id FROM images WHERE file = ? ORDER BY id DESC LIMIT 1", rs -> rs.getLong(1), key);
                    if (!found.isEmpty()) {
                        Database.update(c, "UPDATE images SET url = ?, session = ?, created_at = ? WHERE id = ?", url, where, now, found.getFirst());
                        return found.getFirst();
                    }
                }
                Database.update(c, "INSERT INTO images (file, url, session, created_at) VALUES (?, ?, ?, ?)", key, url, where, now);
                return Database.list(c, "SELECT last_insert_rowid()", rs -> rs.getLong(1)).getFirst();
            });
            return String.valueOf(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    byte[] load(long id) throws ToolFailure {
        Row row = db.one("SELECT file, url FROM images WHERE id = ?", rs -> new Row(rs.getString(1), rs.getString(2)), id);
        if (row == null) {
            throw new ToolFailure("没有图片#" + id);
        }
        try {
            return ImageLoader.fetch(row.url());
        } catch (AgentException ignored) {
        }
        if (!row.file().isBlank()) {
            JsonObject params = new JsonObject();
            params.addProperty("file", row.file());
            JsonObject data;
            try {
                data = optObject(outbox.call("get_image", params, TIMEOUT), "data");
            } catch (ToolFailure e) {
                data = null;
            }
            String local = data == null ? "" : optString(data, "file");
            if (!local.isBlank()) {
                try {
                    Path path = Path.of(local);
                    if (Files.isRegularFile(path)) {
                        return Files.readAllBytes(path);
                    }
                } catch (IOException | RuntimeException ignored) {
                }
            }
            String fresh = data == null ? "" : optString(data, "url");
            if (fresh.startsWith("http://") || fresh.startsWith("https://")) {
                try {
                    return ImageLoader.fetch(fresh);
                } catch (AgentException ignored) {
                }
            }
        }
        throw new ToolFailure("图片#" + id + " 已经过期了，QQ 那边拿不到原图了");
    }
}
