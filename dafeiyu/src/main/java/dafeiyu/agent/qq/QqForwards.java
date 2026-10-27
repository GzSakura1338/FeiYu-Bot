package dafeiyu.agent.qq;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.ToolResult;
import dafeiyu.db.Database;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.message.StoredMessage;
import dafeiyu.util.Clock;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dafeiyu.agent.qq.QqMessages.unforgeable;
import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class QqForwards {

    public record Line(String name, String userId, long time, String text, List<String> images, String attachments) {}

    private static final int MAX_LINES = 200;
    private static final int MAX_TEXT = 20000;
    private static final int MAX_CACHE = 500;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Gson GSON = new Gson();
    private static final Type LINES = new TypeToken<List<Line>>() {}.getType();

    private final Database db;
    private final MessageArchive archive;
    private final QqOutbox outbox;
    private final QqImages images;
    private final LogBuffer log;
    private final Map<String, List<Line>> cache = Collections.synchronizedMap(new LinkedHashMap<>());

    QqForwards(Database db, MessageArchive archive, QqOutbox outbox, QqImages images, LogBuffer log) {
        this.db = db;
        this.archive = archive;
        this.outbox = outbox;
        this.images = images;
        this.log = log;
    }

    String latestId() {
        return db.one("SELECT id FROM forwards ORDER BY created_at DESC LIMIT 1", rs -> rs.getString(1));
    }

    public List<Line> read(String id) throws ToolFailure {
        String key = id == null ? "" : id.strip();
        if (key.isEmpty() || key.length() > 200) {
            throw new ToolFailure("合并转发的编号不对：" + id);
        }
        List<Line> known = cache.get(key);
        if (known != null) {
            return known;
        }
        known = load(key);
        if (known != null) {
            keep(key, known);
            return known;
        }
        JsonArray nodes = fetch(key);
        StoredMessage source = nodes == null || nodes.isEmpty() ? archive.withForward(key) : null;
        if (source != null) {
            JsonArray again = fromHistory(source, key);
            if (again != null && (nodes == null || !again.isEmpty())) {
                nodes = again;
            }
        }
        if (nodes == null) {
            throw new ToolFailure("读不到这段合并转发（" + (source == null ? "它可能套在另一段合并转发里，先读外面那段" : "太久了，QQ 那边已经找不到这条消息") + "）");
        }
        if (nodes.isEmpty()) {
            throw new ToolFailure("读不到这段合并转发（QQ 没把里面的内容给过来）");
        }
        return remember(key, lines(nodes));
    }

    /**
     * 读合并转发，并且把里面的图片真的下载下来一起交给模型。
     * 以前只返回 describe() 的文字（Line.images 完全没用），模型只能看到 [图片#N] 编号，
     * 于是她只能说"图只有编号、画面没进来"（2026-10-27 实测）。
     */
    public ToolResult readWithImages(String id, int maxImages) throws ToolFailure {
        List<Line> lines = read(id);
        StringBuilder out = new StringBuilder("这段合并转发的聊天记录一共 " + lines.size() + " 条：\n");
        List<String> urls = new ArrayList<>();
        for (Line l : lines) {
            String row = (l.time() > 0 ? "[" + Clock.ago(l.time()) + "] " : "") + l.name() + "："
                    + l.text().replace("\n", " / ") + "\n";
            if (out.length() + row.length() > MAX_TEXT) {
                out.append("……后面还有，太长了没列完\n");
                break;
            }
            out.append(row);
            if (l.images() != null && !l.images().isEmpty()) {
                urls.addAll(l.images());
            }
        }
        out.append("（里面再套的合并转发，用它的编号再读一次）");

        List<ImagePart> parts = new ArrayList<>();
        int missed = 0;
        int limit = Math.max(1, maxImages);
        for (String url : urls) {
            if (parts.size() >= limit) {
                missed++;
                continue;
            }
            try {
                parts.add(ImageLoader.download(url));
            } catch (AgentException | RuntimeException e) {
                missed++;
            }
        }
        if (!urls.isEmpty()) {
            out.append("\n（这段里一共 ").append(urls.size()).append(" 张图，这次真的附上了 ")
               .append(parts.size()).append(" 张，跟着这条消息一起给你，能直接看到画面");
            if (missed > 0) {
                out.append("；另外 ").append(missed).append(" 张没附上（超出上限或已过期）");
            }
            out.append("）");
        }
        return ToolResult.withImages(out.toString(), parts);
    }

    public String describe(String id) throws ToolFailure {
        List<Line> lines = read(id);
        StringBuilder out = new StringBuilder("这段合并转发的聊天记录一共 " + lines.size() + " 条：\n");
        for (Line l : lines) {
            String row = (l.time() > 0 ? "[" + Clock.ago(l.time()) + "] " : "") + l.name() + "：" + l.text().replace("\n", " / ") + "\n";
            if (out.length() + row.length() > MAX_TEXT) {
                out.append("……后面还有，太长了没列完\n");
                break;
            }
            out.append(row);
        }
        return out.append("（里面再套的合并转发，用它的编号再读一次）").toString();
    }

    public void prefetch(JsonObject event) {
        for (JsonObject data : forwardSegments(event)) {
            String id = optString(data, "id");
            if (!id.isBlank() && !cache.containsKey(id)) {
                Thread.ofVirtual().start(() -> {
                    try {
                        read(id);
                    } catch (ToolFailure ignored) {
                    }
                });
            }
        }
    }

    private JsonArray fetch(String key) {
        JsonObject params = new JsonObject();
        params.addProperty("message_id", key);
        params.addProperty("id", key);
        try {
            JsonObject response = outbox.call("get_forward_msg", params, TIMEOUT);
            JsonArray nodes = nodes(response == null ? null : response.get("data"));
            if (nodes == null || nodes.isEmpty()) {
                log.warn("agent: get_forward_msg " + key + " returned no messages: " + abbreviate(String.valueOf(response), 600));
                return new JsonArray();
            }
            return nodes;
        } catch (ToolFailure e) {
            log.warn("agent: get_forward_msg " + key + " failed: " + e.getMessage());
            return null;
        }
    }

    private JsonArray fromHistory(StoredMessage source, String key) {
        boolean group = "group".equals(source.messageType());
        String peer = group ? source.groupId() : source.userId();
        if (peer == null || peer.isBlank()) {
            return null;
        }
        JsonObject params = new JsonObject();
        params.addProperty(group ? "group_id" : "user_id", peer);
        params.addProperty("message_seq", key);
        if (source.messageId() != null && !source.messageId().isBlank()) {
            params.addProperty("message_id", source.messageId());
        }
        params.addProperty("count", 1);
        params.addProperty("parse_mult_msg", true);
        try {
            JsonObject data = optObject(outbox.call(group ? "get_group_msg_history" : "get_friend_msg_history", params, TIMEOUT), "data");
            JsonElement messages = data == null ? null : data.get("messages");
            if (messages == null || !messages.isJsonArray()) {
                return null;
            }
            for (JsonElement message : messages.getAsJsonArray()) {
                for (JsonObject segment : message.isJsonObject() ? forwardSegments(message.getAsJsonObject()) : List.<JsonObject>of()) {
                    if (key.equals(optString(segment, "id"))) {
                        JsonArray nodes = nodes(segment);
                        log.info("agent: forward " + key + " re-read through chat history, " + (nodes == null ? 0 : nodes.size()) + " message(s)");
                        return nodes == null ? new JsonArray() : nodes;
                    }
                }
            }
            return null;
        } catch (ToolFailure e) {
            log.warn("agent: forward " + key + " not found in chat history: " + e.getMessage());
            return null;
        }
    }

    private static List<JsonObject> forwardSegments(JsonObject event) {
        JsonElement message = event.get("message");
        if (message == null || !message.isJsonArray()) {
            return List.of();
        }
        List<JsonObject> found = new ArrayList<>();
        for (JsonElement segment : message.getAsJsonArray()) {
            JsonObject seg = segment.isJsonObject() ? segment.getAsJsonObject() : null;
            JsonObject data = seg == null ? null : optObject(seg, "data");
            if (data != null && "forward".equals(optString(seg, "type"))) {
                found.add(data);
            }
        }
        return found;
    }

    private static JsonArray nodes(JsonElement data) {
        if (data == null || data.isJsonNull()) {
            return null;
        }
        if (data.isJsonArray()) {
            return data.getAsJsonArray();
        }
        JsonObject object = data.getAsJsonObject();
        for (String name : List.of("messages", "message", "content", "nodes")) {
            if (object.has(name) && object.get(name).isJsonArray()) {
                return object.getAsJsonArray(name);
            }
        }
        return null;
    }

    private List<Line> load(String key) {
        try {
            String json = db.one("SELECT lines FROM forwards WHERE id = ?", rs -> rs.getString(1), key);
            List<Line> lines = json == null ? null : GSON.fromJson(json, LINES);
            return lines == null ? null : List.copyOf(lines);
        } catch (RuntimeException e) {
            log.warn("agent: saved forward " + key + " is unreadable: " + e.getMessage());
            return null;
        }
    }

    private List<Line> remember(String key, List<Line> lines) {
        keep(key, lines);
        try {
            db.update("INSERT OR REPLACE INTO forwards (id, lines, created_at) VALUES (?, ?, ?)", key, GSON.toJson(lines), System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("agent: could not save forward " + key + ": " + e.getMessage());
        }
        return lines;
    }

    private void keep(String key, List<Line> lines) {
        synchronized (cache) {
            while (cache.size() >= MAX_CACHE) {
                cache.remove(cache.keySet().iterator().next());
            }
            cache.put(key, List.copyOf(lines));
        }
    }

    private void cacheNested(JsonObject event) {
        for (JsonObject data : forwardSegments(event)) {
            String id = optString(data, "id");
            JsonArray inner = nodes(data);
            if (!id.isBlank() && inner != null && !inner.isEmpty()) {
                remember(id, lines(inner));
            }
        }
    }

    private List<Line> lines(JsonArray nodes) {
        String self = outbox.selfId();
        List<Line> lines = new ArrayList<>();
        for (JsonElement e : nodes) {
            if (!e.isJsonObject() || lines.size() >= MAX_LINES) {
                continue;
            }
            JsonObject node = e.getAsJsonObject();
            JsonObject data2 = optObject(node, "data");
            JsonObject event = node.has("message") || node.has("raw_message") ? node : data2 != null && data2.has("content") ? content(data2) : node;
            JsonObject sender = optObject(event, "sender");
            String userId = sender == null ? optString(event, "user_id") : optString(sender, "user_id");
            String name = sender == null ? optString(data2 == null ? event : data2, "nickname")
                    : !optString(sender, "card").isBlank() ? optString(sender, "card") : optString(sender, "nickname");
            QqMessages.Incoming in = QqMessages.parse(event, self, (qq, nick) -> nick.isBlank() ? qq : nick, null,
                    (file, url) -> images.number(file, url, "forward"));
            long time = event.has("time") ? event.get("time").getAsLong() * 1000 : 0;
            lines.add(new Line(unforgeable(name.isBlank() ? "QQ " + userId : name), userId, time, in.text(), in.imageUrls(), in.attachments()));
            cacheNested(event);
        }
        return lines;
    }

    private static JsonObject content(JsonObject data) {
        JsonObject event = new JsonObject();
        event.add("message", data.get("content"));
        JsonObject sender = new JsonObject();
        sender.addProperty("user_id", optString(data, "user_id"));
        sender.addProperty("nickname", optString(data, "nickname"));
        event.add("sender", sender);
        return event;
    }
}
