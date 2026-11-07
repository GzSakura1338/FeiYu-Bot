package dafeiyu.agent.qq;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.image.VideoFrames;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static dafeiyu.agent.qq.QqMessages.unforgeable;
import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class QqForwards {

    public record Line(String name, String userId, long time, String text, List<String> images, String attachments) {}

    private record Page(List<Line> lines, String text) {}

    private static final int MAX_LINES = 1000;
    private static final int MAX_TEXT = 12000;
    private static final int MAX_ROW = 3000;
    private static final int MAX_CACHE = 500;
    private static final String BARE_FORWARD = "[合并转发的聊天记录]";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Gson GSON = new Gson();
    private static final Type LINES = new TypeToken<List<Line>>() {}.getType();

    private final Database db;
    private final MessageArchive archive;
    private final QqOutbox outbox;
    private final QqImages images;
    private final VideoFrames videos;
    private final LogBuffer log;
    private final Map<String, List<Line>> cache = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<String> refetched = ConcurrentHashMap.newKeySet();

    QqForwards(Database db, MessageArchive archive, QqOutbox outbox, QqImages images, VideoFrames videos, LogBuffer log) {
        this.db = db;
        this.archive = archive;
        this.outbox = outbox;
        this.images = images;
        this.videos = videos;
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
        if (known == null) {
            known = load(key);
            if (known != null) {
                keep(key, known);
            }
        }
        if (known != null && (!unpeeled(known) || !refetched.add(key))) {
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
        if ((nodes == null || nodes.isEmpty()) && known != null) {
            return known;
        }
        if (nodes == null) {
            throw new ToolFailure("读不到这段合并转发（" + (source == null ? "它可能套在另一段合并转发里，先读外面那段" : "太久了，QQ 那边已经找不到这条消息") + "）");
        }
        if (nodes.isEmpty()) {
            throw new ToolFailure("读不到这段合并转发（QQ 没把里面的内容给过来）");
        }
        return remember(key, lines(key, nodes));
    }

    private static boolean unpeeled(List<Line> lines) {
        return lines.stream().anyMatch(l -> l.text() != null && l.text().contains(BARE_FORWARD));
    }

    public ToolResult readWithImages(String id, int from, int maxImages) throws ToolFailure {
        Page page = page(id, from);
        StringBuilder out = new StringBuilder(page.text());
        List<String> refs = page.lines().stream().flatMap(l -> l.images() == null ? Stream.<String>empty() : l.images().stream()).distinct().toList();
        List<String> urls = refs.stream().filter(url -> !url.startsWith(QqMessages.VIDEO_REF)).toList();
        List<String> clips = refs.stream().filter(url -> url.startsWith(QqMessages.VIDEO_REF)).toList();
        List<ImagePart> parts = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> over = new ArrayList<>();
        int limit = Math.max(1, maxImages);
        for (String url : urls) {
            String number = images.numberOf(url);
            String label = number == null ? "一张图" : "图片#" + number;
            if (parts.size() >= limit) {
                over.add(label);
                continue;
            }
            try {
                parts.add(picture(url, number));
            } catch (AgentException | ToolFailure | RuntimeException e) {
                failed.add(label);
            }
        }
        if (!urls.isEmpty()) {
            out.append("\n（这一段里有 ").append(urls.size()).append(" 张图")
                    .append(parts.isEmpty() ? "，一张都没附上" : "，附上了 " + parts.size() + " 张，画面在后面那条消息里，按上面出现的顺序排")
                    .append(failed.isEmpty() ? "" : "；" + String.join("、", failed) + " 下载不下来（QQ 的链接多半过期了）")
                    .append(over.isEmpty() ? "" : "；" + String.join("、", over) + " 超出一次能附的 " + limit + " 张没附")
                    .append(parts.size() < urls.size() ? "。没附上的别猜画面，想看就用 view_image 填它的图片编号）" : "）");
        }
        if (!clips.isEmpty()) {
            out.append("\n").append(watch(clips, parts));
        }
        return ToolResult.withImages(out.toString(), parts);
    }

    private ImagePart picture(String url, String number) throws AgentException, ToolFailure {
        if (number == null) {
            return ImageLoader.download(url);
        }
        try {
            return ImageLoader.download(url);
        } catch (AgentException e) {
            if (ImageLoader.TOO_LARGE.equals(e.getMessage())) {
                throw e;
            }
            return ImageLoader.fromBytes(images.load(Long.parseLong(number)));
        }
    }

    private String watch(List<String> clips, List<ImagePart> parts) {
        if (videos == null || !videos.config().enabled()) {
            return "（这一段里有 " + clips.size() + " 个视频，主人把看视频关了，只能看到“[视频]”标记，别猜里面是什么）";
        }
        String url = QqMessages.videoUrl(clips.getFirst());
        String rest = clips.size() > 1 ? "；这一段还有 " + (clips.size() - 1) + " 个视频没截，一次只截一个" : "";
        if (url.isEmpty()) {
            return "（这一段里有 " + clips.size() + " 个视频，QQ 没给下载地址，截不了图，别猜里面是什么" + rest + "）";
        }
        try {
            VideoFrames.Frames frames = videos.extract(url);
            int start = parts.size() + 1;
            parts.addAll(frames.parts());
            return "（第 " + start + " 到第 " + parts.size() + " 张图是这一段里第一个视频的截图" + rest + "）" + frames.note();
        } catch (ToolFailure e) {
            log.warn("agent: forward video skipped: " + e.getMessage());
            return "（这一段里的视频没截到画面：" + abbreviate(String.valueOf(e.getMessage()), 120) + "，别猜里面是什么" + rest + "）";
        }
    }

    public String describe(String id, int from) throws ToolFailure {
        return page(id, from).text();
    }

    private Page page(String id, int from) throws ToolFailure {
        List<Line> lines = read(id);
        int total = lines.size();
        int start = Math.max(1, from);
        if (start > total) {
            throw new ToolFailure("这段合并转发一共只有 " + total + " 条，from 填 1 到 " + total);
        }
        StringBuilder out = new StringBuilder("这段合并转发的聊天记录一共 " + total + " 条" + (start > 1 ? "，下面从第 " + start + " 条开始" : "") + "：\n");
        int end = start - 1;
        for (int i = start - 1; i < total; i++) {
            Line l = lines.get(i);
            String row = (i + 1) + ". " + (l.time() > 0 ? "[" + Clock.ago(l.time()) + "] " : "") + l.name() + "："
                    + abbreviate(l.text().replace("\n", " / "), MAX_ROW) + "\n";
            if (end >= start && out.length() + row.length() > MAX_TEXT) {
                break;
            }
            out.append(row);
            end = i + 1;
        }
        if (end < total) {
            out.append("（这次列到第 ").append(end).append(" 条，后面还有 ").append(total - end)
                    .append(" 条没列：用 read_forward 填同一个编号、from 填 ").append(end + 1).append(" 接着读）\n");
        }
        out.append("（里面再套的合并转发，用它的编号再读一次）");
        return new Page(lines.subList(start - 1, end), out.toString());
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
                remember(id, lines(id, inner));
            }
        }
    }

    private static void nameNested(JsonObject event, String prefix) {
        int n = 0;
        for (JsonObject data : forwardSegments(event)) {
            n++;
            JsonArray inner = nodes(data);
            if (optString(data, "id").isBlank() && inner != null && !inner.isEmpty() && prefix.length() < 190) {
                data.addProperty("id", prefix + (n > 1 ? "." + n : ""));
            }
        }
    }

    private List<Line> lines(String key, JsonArray nodes) {
        String self = outbox.selfId();
        List<Line> lines = new ArrayList<>();
        int dropped = 0;
        for (JsonElement e : nodes) {
            if (!e.isJsonObject()) {
                continue;
            }
            if (lines.size() >= MAX_LINES) {
                dropped++;
                continue;
            }
            JsonObject node = e.getAsJsonObject();
            JsonObject data2 = optObject(node, "data");
            JsonObject event = node.has("message") || node.has("raw_message") ? node : data2 != null && data2.has("content") ? content(data2) : node;
            JsonObject sender = optObject(event, "sender");
            String userId = sender == null ? optString(event, "user_id") : optString(sender, "user_id");
            String name = sender == null ? optString(data2 == null ? event : data2, "nickname")
                    : !optString(sender, "card").isBlank() ? optString(sender, "card") : optString(sender, "nickname");
            nameNested(event, key + "~" + (lines.size() + 1));
            QqMessages.Incoming in = QqMessages.parse(event, self, (qq, nick) -> nick.isBlank() ? qq : nick, null,
                    (file, url) -> images.number(file, url, "forward"));
            long time = event.has("time") ? event.get("time").getAsLong() * 1000 : 0;
            lines.add(new Line(unforgeable(name.isBlank() ? "QQ " + userId : name), userId, time, in.text(), in.imageUrls(), in.attachments()));
            cacheNested(event);
        }
        if (dropped > 0) {
            lines.add(new Line("（系统）", "", 0, "后面还有 " + dropped + " 条，太多了没存下来", List.of(), ""));
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
