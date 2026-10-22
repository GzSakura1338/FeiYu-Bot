package dafeiyu.agent.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.tool.FileSink;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.NapCatService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    private static final long MAX_INLINE_IMAGE = 10L * 1024 * 1024;
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(3);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    private final ChatAgent agent;
    private final NapCatService napcat;
    private final MessageArchive archive;
    private final LogBuffer log;
    private final Map<String, GroupState> groups;
    private volatile MessageGate gate = MessageGate.NONE;

    QqOutbox(ChatAgent agent, NapCatService napcat, MessageArchive archive, LogBuffer log, Map<String, GroupState> groups) {
        this.agent = agent;
        this.napcat = napcat;
        this.archive = archive;
        this.log = log;
        this.groups = groups;
    }

    String selfId() {
        return String.valueOf(napcat.status().selfId());
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
        return StickerStore.limit(text, false, id -> {
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
        if (!words.isEmpty()) {
            JsonArray message = new JsonArray();
            if (target.group() && quote != null && !quote.isEmpty()) {
                message.add(segment("reply", "id", quote));
            }
            message.addAll(content(target, words));
            send(target, message, QqMessages.withoutTails(words), true);
        }
        for (StickerStore.Sticker sticker : stickers) {
            sendSticker(target, sticker);
        }
        gate.said(target.session(), filtered);
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
        return StickerStore.tags(text).stream().limit(1).map(store::get).filter(s -> s != null && !s.file().isEmpty()).toList();
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
            if (IMAGE_EXTENSIONS.contains(extension) && size <= MAX_INLINE_IMAGE) {
                JsonArray message = new JsonArray();
                message.add(segment("image", "file", "base64://" + Base64.getEncoder().encodeToString(read(file, name))));
                send(target, message, "[图片] " + name);
                log.info("agent [" + target.where() + "] sent image " + name);
                return "Sent image " + name + " to the user.";
            }
            JsonObject params = destination(target);
            params.addProperty("file", file.toAbsolutePath().toString());
            params.addProperty("name", name);
            call(target.group() ? "upload_group_file" : "upload_private_file", params, UPLOAD_TIMEOUT);
            archive(target, null, "[文件] " + name);
            log.info("agent [" + target.where() + "] sent file " + name + " (" + size / 1024 + " KB)");
            return "Sent file " + name + " to the user.";
        };
    }

    void send(Target target, JsonArray message, String archiveText) throws ToolFailure {
        send(target, message, archiveText, true);
    }

    private void send(Target target, JsonArray message, String archiveText, boolean mine) throws ToolFailure {
        JsonObject params = destination(target);
        params.add("message", message);
        JsonObject data = optObject(call(target.group() ? "send_group_msg" : "send_private_msg", params, Duration.ofSeconds(15)), "data");
        String messageId = data == null ? null : optString(data, "message_id");
        archive(target, messageId, archiveText);
        if (target.group() && mine) {
            groups.computeIfAbsent(target.groupId(), GroupState::new).spoke(messageId, archiveText);
        }
    }

    JsonObject call(String action, JsonObject params, Duration timeout) throws ToolFailure {
        JsonObject response;
        try {
            response = napcat.callAction(action, params, timeout).get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        } catch (Exception e) {
            throw new ToolFailure(e.getCause() != null ? String.valueOf(e.getCause().getMessage()) : e.toString());
        }
        if (!optString(response, "status").equals("ok")) {
            String reason = optString(response, "wording").isEmpty() ? optString(response, "message") : optString(response, "wording");
            throw new ToolFailure(reason.isEmpty() ? "QQ rejected the request" : reason);
        }
        return response;
    }

    private static JsonObject destination(Target target) {
        JsonObject params = new JsonObject();
        String key = target.group() ? "group_id" : "user_id";
        String id = target.group() ? target.groupId() : target.userId();
        try {
            params.addProperty(key, Long.parseLong(id));
        } catch (NumberFormatException e) {
            params.addProperty(key, id);
        }
        return params;
    }

    private void archive(Target target, String messageId, String text) {
        String nickname = napcat.status().nickname();
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
