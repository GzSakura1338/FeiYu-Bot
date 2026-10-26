package dafeiyu.agent.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.model.AgentStore;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.tool.FileSink;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.OneBotService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static dafeiyu.agent.qq.QqMessages.segment;
import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

final class QqOutbox {

    record Target(String messageType, String groupId, String userId, String selfId, String messageId, String where) {

        boolean group() {
            return messageType.equals("group");
        }

        String session() {
            return group() ? "group:" + groupId : "private:" + userId;
        }

        static Target group(String groupId, String userId, String selfId, String messageId) {
            return new Target("group", groupId, userId, selfId, messageId, "group " + groupId);
        }

        static Target direct(String userId, String selfId, String messageId) {
            return new Target("private", "", userId, selfId, messageId, "private " + userId);
        }
    }

    static final int MAX_REPLY_LENGTH = 3000;
    private static final long MAX_IMAGE = 30L * 1024 * 1024;
    private static final int INLINE_SIDE = 2048;
    private static final long INLINE_BYTES = 2L * 1024 * 1024;
    private static final Duration TEXT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MEDIA_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<String> MEDIA_SEGMENTS = Set.of("image", "record", "video", "file");
    private static final Pattern UNREADABLE_PATH = Pattern.compile("(?i)识别\\s*URL|ENOENT|no such file|not exist|不存在|文件处理失败|找不到");
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(3);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    private final ChatAgent agent;
    private final OneBotService onebot;
    private volatile java.util.function.BiFunction<Path, String, String> sentImages = (file, session) -> null;
    private final MessageArchive archive;
    private final LogBuffer log;
    private final Map<String, GroupState> groups;
    private volatile MessageGate gate = MessageGate.NONE;
    private final Map<String, String> tempGroups = new ConcurrentHashMap<>();
    private volatile boolean pathsUnreadable;

    QqOutbox(ChatAgent agent, OneBotService onebot, MessageArchive archive, LogBuffer log, Map<String, GroupState> groups) {
        this.agent = agent;
        this.onebot = onebot;
        this.archive = archive;
        this.log = log;
        this.groups = groups;
    }

    String selfId() {
        return String.valueOf(onebot.status().selfId());
    }

    Target targetOf(String session) {
        if (session.startsWith("group:")) {
            return Target.group(session.substring(6), "", selfId(), "");
        }
        if (session.startsWith("private:")) {
            return Target.direct(session.substring(8), selfId(), "");
        }
        return null;
    }

    String prepare(String raw) {
        String text = QqFaces.limit(QqText.format(abbreviate(raw, MAX_REPLY_LENGTH)), false);
        StickerStore store = agent.stickers().store();
        return StickerStore.limit(text, id -> {
            StickerStore.Sticker s = store.get(id);
            return s != null && s.usable();
        });
    }

    void review(String context, String text) throws ToolFailure {
        ContentGuard.Verdict verdict = agent.guard().review(agent.store().active(), context, text);
        if (verdict != null) {
            throw new ToolFailure("这句话不能发（涉及" + verdict.category().label() + "），换个说法或者别说了");
        }
    }

    boolean hasContent(String text) {
        return !StickerStore.withoutTags(text).isEmpty() || !stickers(text).isEmpty();
    }

    void rememberTempGroup(String userId, String groupId) {
        if (groupId == null || groupId.isBlank() || groupId.equals("0")) {
            return;
        }
        tempGroups.put(userId, groupId);
    }

    void forgetTempGroup(String userId) {
        tempGroups.remove(userId);
    }

    boolean hasTempGroup(String userId) {
        return tempGroups.containsKey(userId);
    }

    void numberSentImages(java.util.function.BiFunction<Path, String, String> numbering) {
        this.sentImages = numbering;
    }

    void setGate(MessageGate gate) {
        this.gate = gate == null ? MessageGate.NONE : gate;
    }

    void deliver(Target target, String text, String quote) throws ToolFailure {
        String filtered = gate.outgoing(target.session(), text);
        String words = StickerStore.withoutTags(filtered);
        List<StickerStore.Sticker> stickers = stickers(filtered);
        if (words.isEmpty() && stickers.isEmpty()) {
            throw new ToolFailure("没有要发的内容");
        }
        boolean quoted = false;
        int stickerSent = 0;
        ToolFailure stickerFailed = null;
        Map<Long, StickerStore.Sticker> byId = new HashMap<>();
        stickers.forEach(s -> byId.put(s.id(), s));
        List<StickerStore.Piece> pieces = stickers.isEmpty() ? List.of(new StickerStore.Piece(words, 0)) : StickerStore.pieces(filtered);
        AgentStore.Pacing pacing = agent.store().pacing();
        boolean sentAny = false;
        for (StickerStore.Piece piece : pieces) {
            if (piece.isSticker()) {
                StickerStore.Sticker sticker = byId.get(piece.sticker());
                if (sticker == null || stickerSent >= StickerStore.MAX_PER_MESSAGE) {
                    continue;
                }
                stickerSent++;
                if (sentAny) {
                    pause(QqText.bubblePause("", pacing.intervalMs()));
                }
                sentAny = true;
                try {
                    sendSticker(target, sticker);
                } catch (ToolFailure e) {
                    stickerFailed = e;
                }
                continue;
            }
            List<String> bubbles = QqText.bubbles(piece.text(), pacing.maxPieces());
            for (String bubble : bubbles) {
                if (sentAny) {
                    pause(QqText.bubblePause(bubble, pacing.intervalMs()));
                }
                sentAny = true;
                JsonArray message = new JsonArray();
                if (!quoted && target.group() && quote != null && !quote.isEmpty()) {
                    message.add(segment("reply", "id", quote));
                }
                quoted = true;
                message.addAll(content(target, bubble));
                send(target, message, QqMessages.withoutTails(bubble), true);
            }
        }
        gate.said(target.session(), filtered);
        if (stickerFailed != null) {
            if (words.isEmpty()) {
                throw new ToolFailure("表情包没发出去：" + stickerFailed.getMessage());
            }
            log.warn("agent [" + target.where() + "] sticker send failed: " + stickerFailed.getMessage());
            agent.hear(target.session(), "（你上面那句的文字发出去了，表情包没发出去：" + abbreviate(stickerFailed.getMessage(), 80) + "）");
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void deliverForPlugin(Target target, String text, String imageUrl, String quote) throws ToolFailure {
        JsonArray message = new JsonArray();
        if (target.group() && quote != null && !quote.isEmpty()) {
            message.add(segment("reply", "id", quote));
        }
        if (!text.isEmpty()) {
            message.addAll(QqFaces.segments(QqMessages.withoutTails(text)));
        }
        if (imageUrl != null && !imageUrl.isEmpty()) {
            message.add(segment("image", "file", imageUrl));
        }
        if (message.isEmpty()) {
            throw new ToolFailure("没有要发的内容");
        }
        send(target, message, text.isEmpty() ? "[图片]" : QqMessages.withoutTails(text) + (imageUrl == null || imageUrl.isEmpty() ? "" : " [图片]"), false);
    }

    private List<StickerStore.Sticker> stickers(String text) {
        StickerStore store = agent.stickers().store();
        return StickerStore.tags(text).stream().distinct().limit(StickerStore.MAX_PER_MESSAGE).map(store::get)
                .filter(s -> s != null && !s.file().isEmpty()).toList();
    }

    void sendSticker(Target target, StickerStore.Sticker sticker) throws ToolFailure {
        Path file = agent.stickers().store().path(sticker);
        JsonObject data = new JsonObject();
        data.addProperty("file", "base64://" + Base64.getEncoder().encodeToString(read(file, sticker.file())));
        data.addProperty("sub_type", 1);
        data.addProperty("summary", "[动画表情]");
        JsonObject segment = new JsonObject();
        segment.addProperty("type", "image");
        segment.add("data", data);
        JsonArray message = new JsonArray();
        message.add(segment);
        send(target, message, "[表情包#" + sticker.id() + "]");
        agent.stickers().store().sent(sticker.id());
    }

    void poke(Target target, String userId) throws ToolFailure {
        JsonObject params = new JsonObject();
        params.addProperty("user_id", Long.parseLong(userId));
        if (target.group()) {
            params.addProperty("group_id", Long.parseLong(target.groupId()));
        }
        try {
            call(target.group() ? "group_poke" : "friend_poke", params, Duration.ofSeconds(10));
        } catch (ToolFailure e) {
            call("send_poke", params, Duration.ofSeconds(10));
        }
    }

    JsonArray content(Target target, String text) {
        GroupState state = target.group() ? groups.get(target.groupId()) : null;
        return state == null ? QqFaces.segments(QqMessages.withoutTails(text)) : QqMessages.content(text, state.speakers());
    }

    FileSink files(Target target) {
        return (file, name) -> {
            String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                throw new ToolFailure("Unable to read " + name + ": " + e.getMessage());
            }
            if (IMAGE_EXTENSIONS.contains(extension) && size <= MAX_IMAGE) {
                String number = sentImages.apply(file, target.session());
                String label = number == null ? "[图片]" : "[图片#" + number + "]";
                Attempt sent = sendImageFile(target, file, name, label + " " + name);
                log.info("agent [" + target.where() + "] sent image " + name + " via " + sent.via() + " (message " + sent.messageId() + ")");
                return "Sent image " + name + " to the user" + (number == null ? "." : " as " + label + " (view_image " + number + " shows it again).");
            }
            String action = target.group() ? "upload_group_file" : "upload_private_file";
            JsonObject params = destination(target);
            params.addProperty("name", name);
            boolean byPath = !pathsUnreadable;
            if (byPath) {
                params.addProperty("file", file.toAbsolutePath().toString());
                JsonObject response = request(action, params, UPLOAD_TIMEOUT);
                if (!ok(response)) {
                    markPathsUnreadable(reason(response));
                    if (!pathsUnreadable) {
                        throw new ToolFailure(reason(response));
                    }
                    byPath = false;
                }
            }
            if (!byPath) {
                if (size > MAX_IMAGE) {
                    throw new ToolFailure("SnowLuma 读不到这台电脑上的文件（多半跑在没挂工作目录的容器里），超过 " + MAX_IMAGE / 1024 / 1024
                            + " MB 的文件发不了；把工作目录按相同路径挂进 SnowLuma 的容器就能发");
                }
                params.addProperty("file", reachable(file));
                call(action, params, UPLOAD_TIMEOUT);
            }
            archive(target, null, "[文件] " + name);
            log.info("agent [" + target.where() + "] sent file " + name + " (" + size / 1024 + " KB) via " + (byPath ? "path" : "base64"));
            return "Sent file " + name + " to the user.";
        };
    }

    void send(Target target, JsonArray message, String archiveText) throws ToolFailure {
        send(target, message, archiveText, true);
    }

    private void send(Target target, JsonArray message, String archiveText, boolean mine) throws ToolFailure {
        Attempt sent = attempt(target, message, archiveText, mine, media(message) ? MEDIA_TIMEOUT : TEXT_TIMEOUT);
        if (sent.error() != null) {
            throw new ToolFailure(sent.error());
        }
    }

    private record Attempt(String error, String messageId, String via) {}

    private Attempt attempt(Target target, JsonArray message, String archiveText, boolean mine, Duration timeout) throws ToolFailure {
        JsonObject params = destination(target);
        params.add("message", message);
        JsonObject response = request(target.group() ? "send_group_msg" : "send_private_msg", params, timeout);
        if (!ok(response)) {
            return new Attempt(reason(response), null, null);
        }
        JsonObject data = optObject(response, "data");
        String messageId = data == null ? null : optString(data, "message_id");
        archive(target, messageId, archiveText);
        if (target.group() && mine) {
            groups.computeIfAbsent(target.groupId(), GroupState::new).spoke(messageId, archiveText);
        }
        return new Attempt(null, messageId, null);
    }

    private Attempt sendImageFile(Target target, Path file, String name, String archiveText) throws ToolFailure {
        String pathError = null;
        if (!pathsUnreadable) {
            JsonArray byPath = new JsonArray();
            byPath.add(segment("image", "file", file.toAbsolutePath().toString()));
            Attempt sent = attempt(target, byPath, archiveText, true, MEDIA_TIMEOUT);
            if (sent.error() == null) {
                return new Attempt(null, sent.messageId(), "path");
            }
            pathError = sent.error();
            if (!UNREADABLE_PATH.matcher(pathError).find()) {
                throw new ToolFailure("发图失败：" + pathError);
            }
            pathsUnreadable = true;
            log.warn("qq: SnowLuma cannot read local files (" + pathError + "), sending images inline from now on. "
                    + "If SnowLuma runs in a container, mount the working directory at the same path to send by path");
        }
        JsonArray inline = new JsonArray();
        inline.add(segment("image", "file", "base64://" + Base64.getEncoder().encodeToString(inlineImage(file, name))));
        Attempt sent = attempt(target, inline, archiveText, true, MEDIA_TIMEOUT);
        if (sent.error() != null) {
            throw new ToolFailure(pathError == null ? "发图失败：" + sent.error()
                    : "发图失败：按文件路径发（" + pathError + "）和直接带图片数据发（" + sent.error() + "）都不行");
        }
        return new Attempt(null, sent.messageId(), "base64");
    }

    String reachable(Path file) throws ToolFailure {
        if (!pathsUnreadable) {
            return file.toAbsolutePath().toString();
        }
        String name = file.getFileName().toString();
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        byte[] bytes = IMAGE_EXTENSIONS.contains(extension) ? inlineImage(file, name) : read(file, name);
        return "base64://" + Base64.getEncoder().encodeToString(bytes);
    }

    boolean pathsUnreadable() {
        return pathsUnreadable;
    }

    void markPathsUnreadable(String error) {
        if (!pathsUnreadable && UNREADABLE_PATH.matcher(error).find()) {
            pathsUnreadable = true;
            log.warn("qq: SnowLuma cannot read local files (" + error + "), sending files inline from now on");
        }
    }

    private byte[] inlineImage(Path file, String name) throws ToolFailure {
        byte[] bytes = read(file, name);
        return name.toLowerCase(Locale.ROOT).endsWith(".gif") ? bytes : ImageLoader.shrink(bytes, INLINE_SIDE, INLINE_BYTES);
    }

    private static boolean media(JsonArray message) {
        for (int i = 0; i < message.size(); i++) {
            if (message.get(i).isJsonObject() && MEDIA_SEGMENTS.contains(optString(message.get(i).getAsJsonObject(), "type"))) {
                return true;
            }
        }
        return false;
    }

    JsonObject call(String action, JsonObject params, Duration timeout) throws ToolFailure {
        JsonObject response = request(action, params, timeout);
        if (!ok(response)) {
            throw new ToolFailure(reason(response));
        }
        return response;
    }

    private JsonObject request(String action, JsonObject params, Duration timeout) throws ToolFailure {
        try {
            return onebot.callAction(action, params, timeout).get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        } catch (Exception e) {
            throw new ToolFailure(e.getCause() != null ? String.valueOf(e.getCause().getMessage()) : e.toString());
        }
    }

    private static boolean ok(JsonObject response) {
        return optString(response, "status").equals("ok");
    }

    private static String reason(JsonObject response) {
        String reason = optString(response, "wording").isEmpty() ? optString(response, "message") : optString(response, "wording");
        return reason.isEmpty() ? "QQ rejected the request" : reason;
    }

    private JsonObject destination(Target target) {
        JsonObject params = new JsonObject();
        String key = target.group() ? "group_id" : "user_id";
        String id = target.group() ? target.groupId() : target.userId();
        put(params, key, id);
        String temp = target.group() ? null : tempGroups.get(id);
        if (temp != null) {
            put(params, "group_id", temp);
        }
        return params;
    }

    private static void put(JsonObject params, String key, String id) {
        try {
            params.addProperty(key, Long.parseLong(id));
        } catch (NumberFormatException e) {
            params.addProperty(key, id);
        }
    }

    private void archive(Target target, String messageId, String text) {
        String nickname = onebot.status().nickname();
        try {
            archive.recordOutgoing(target.messageType(), target.groupId(), target.userId(), target.selfId(),
                    nickname == null ? "大肥鱼" : nickname, messageId, text);
        } catch (RuntimeException e) {
            log.warn("agent: saving reply to database failed: " + e.getMessage());
        }
    }

    private static byte[] read(Path file, String name) throws ToolFailure {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ToolFailure("Unable to read " + name + ": " + e.getMessage());
        }
    }
}
