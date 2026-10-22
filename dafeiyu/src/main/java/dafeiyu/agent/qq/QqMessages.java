package dafeiyu.agent.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class QqMessages {

    record Incoming(String text, boolean mentionedMe, boolean mentionedOthers, List<String> imageUrls, String replyTo, String attachments) {}

    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:([a-z_]+)((?:,[^\\]]*)?)]");
    private static final int MAX_NAME = 24;
    private static final String MENTION_STOP = " \t\n，,。.！!？?：:；;、";
    private static final Pattern LABEL_TAIL = Pattern.compile("(?<=[\\p{IsHan}\\p{L}\\p{N}）)])#\\d{4}(?!\\d)");

    private QqMessages() {}

    static Incoming parse(JsonObject event, String selfId, BiFunction<String, String, String> nameOf) {
        return parse(event, selfId, nameOf, null);
    }

    static Incoming parse(JsonObject event, String selfId, BiFunction<String, String, String> nameOf, BiFunction<String, String, String> stickers) {
        JsonElement message = event.get("message");
        StringBuilder text = new StringBuilder();
        List<String> images = new ArrayList<>();
        Set<String> mentions = new LinkedHashSet<>();
        JsonArray attachments = new JsonArray();
        String replyTo = "";
        if (message != null && message.isJsonArray()) {
            for (JsonElement element : message.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject seg = element.getAsJsonObject();
                JsonObject data = optObject(seg, "data");
                if (data == null) {
                    continue;
                }
                switch (optString(seg, "type")) {
                    case "text" -> text.append(optString(data, "text"));
                    case "at" -> mentions.add(appendMention(text, optString(data, "qq"), optString(data, "name"), selfId, nameOf));
                    case "image", "mface" -> {
                        String type = optString(seg, "type");
                        boolean sticker = type.equals("mface") || optString(data, "sub_type").equals("1");
                        String file = type.equals("mface") ? "mface-" + optString(data, "emoji_id") : optString(data, "file");
                        addImage(text, images, optString(data, "url"), optString(data, "summary"), type, sticker ? stickers : null, file, attachments);
                    }
                    case "face" -> text.append(QqFaces.describe(optString(data, "id")));
                    case "reply" -> replyTo = optString(data, "id");
                    default -> {
                        String label = other(optString(seg, "type"), name -> optString(data, name));
                        text.append(label);
                        attachOther(attachments, optString(seg, "type"), name -> optString(data, name), label);
                    }
                }
            }
        } else {
            String raw = optString(event, "raw_message");
            Matcher m = CQ_CODE.matcher(raw);
            int last = 0;
            while (m.find()) {
                text.append(unescape(raw.substring(last, m.start())));
                Map<String, String> params = cqParams(m.group(2));
                switch (m.group(1)) {
                    case "at" -> mentions.add(appendMention(text, params.getOrDefault("qq", ""), params.getOrDefault("name", ""), selfId, nameOf));
                    case "image", "mface" -> {
                        boolean sticker = m.group(1).equals("mface") || "1".equals(params.get("sub_type"));
                        String file = m.group(1).equals("mface") ? "mface-" + params.getOrDefault("emoji_id", "") : params.getOrDefault("file", "");
                        addImage(text, images, params.getOrDefault("url", ""), params.getOrDefault("summary", ""), m.group(1), sticker ? stickers : null,
                                file, attachments);
                    }
                    case "face" -> text.append(QqFaces.describe(params.getOrDefault("id", "")));
                    case "reply" -> replyTo = params.getOrDefault("id", "");
                    default -> {
                        String label = other(m.group(1), name -> params.getOrDefault(name, ""));
                        text.append(label);
                        attachOther(attachments, m.group(1), name -> params.getOrDefault(name, ""), label);
                    }
                }
                last = m.end();
            }
            text.append(unescape(raw.substring(last)));
        }
        boolean me = mentions.remove(selfId);
        mentions.remove("all");
        return new Incoming(text.toString().strip(), me, !mentions.isEmpty(), images, replyTo, attachments.isEmpty() ? "" : attachments.toString());
    }

    private static String other(String type, Function<String, String> field) {
        return switch (type) {
            case "json" -> card(field.apply("data"));
            case "forward", "node" -> "[合并转发的聊天记录]";
            case "record" -> "[语音]";
            case "video" -> "[视频]";
            case "file" -> {
                String name = field.apply("name").isBlank() ? field.apply("file") : field.apply("name");
                yield "[文件" + (name.isBlank() ? "" : "：" + name) + "]";
            }
            case "location" -> "[位置" + (field.apply("title").isBlank() ? "" : "：" + field.apply("title")) + "]";
            case "share" -> "[链接" + (field.apply("title").isBlank() ? "" : "：" + field.apply("title")) + "]";
            case "music" -> "[音乐分享]";
            case "dice" -> "[掷骰子" + (field.apply("result").isBlank() ? "" : "：" + field.apply("result") + " 点") + "]";
            case "rps" -> "[猜拳]";
            case "poke", "shake" -> "[戳一戳]";
            case "contact" -> "[推荐联系人]";
            case "markdown" -> "[卡片消息]";
            default -> "";
        };
    }

    private static String card(String json) {
        if (json == null || json.isBlank()) {
            return "[卡片消息]";
        }
        try {
            JsonElement parsed = JsonParser.parseString(unescape(json));
            if (parsed.isJsonObject()) {
                String prompt = optString(parsed.getAsJsonObject(), "prompt").strip();
                if (!prompt.isEmpty()) {
                    return prompt.startsWith("[") ? prompt : "[卡片：" + prompt + "]";
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "[卡片消息]";
    }

    public static String plain(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher m = CQ_CODE.matcher(raw);
        StringBuilder text = new StringBuilder();
        int last = 0;
        while (m.find()) {
            text.append(unescape(raw.substring(last, m.start())));
            Map<String, String> params = cqParams(m.group(2));
            switch (m.group(1)) {
                case "image" -> text.append("1".equals(params.get("sub_type")) ? "[表情包]" : "[图片]");
                case "mface" -> text.append("[表情包]");
                case "face" -> text.append(QqFaces.describe(params.getOrDefault("id", "")));
                case "at" -> text.append("@").append(params.getOrDefault("name", "某人")).append(' ');
                case "reply" -> { }
                default -> text.append(other(m.group(1), name -> params.getOrDefault(name, "")));
            }
            last = m.end();
        }
        text.append(unescape(raw.substring(last)));
        return unforgeable(text.toString().replaceAll("\\s+", " ").strip());
    }

    public static String displayName(String raw) {
        return abbreviate(unforgeable(raw == null ? "" : raw).replaceAll("[\\s：:]+", " ").strip(), MAX_NAME);
    }

    public static String unforgeable(String text) {
        return text.replace('【', '[').replace('】', ']');
    }

    static String senderName(JsonObject event) {
        JsonObject sender = optObject(event, "sender");
        if (sender == null) {
            return optString(event, "user_id");
        }
        String card = optString(sender, "card").strip();
        return card.isEmpty() ? optString(sender, "nickname").strip() : card;
    }

    static JsonObject segment(String type, String key, String value) {
        JsonObject data = new JsonObject();
        data.addProperty(key, value);
        JsonObject segment = new JsonObject();
        segment.addProperty("type", type);
        segment.add("data", data);
        return segment;
    }

    static JsonArray content(String text, Map<String, String> speakers) {
        JsonArray out = new JsonArray();
        List<String> names = speakers.keySet().stream()
                .filter(n -> !n.isBlank() && !n.equals("全体成员"))
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        List<String> labels = names.stream().filter(n -> n.contains("#")).toList();
        StringBuilder buffer = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '@' && !names.isEmpty()) {
                Mention mention = mentionAt(text, i + 1, names);
                if (mention != null) {
                    out.addAll(QqFaces.segments(unlabel(buffer.toString(), labels)));
                    buffer.setLength(0);
                    out.add(segment("at", "qq", speakers.get(mention.name())));
                    i += 1 + mention.length();
                    continue;
                }
            }
            buffer.append(text.charAt(i));
            i++;
        }
        out.addAll(QqFaces.segments(unlabel(buffer.toString(), labels)));
        return out;
    }

    private static String unlabel(String text, List<String> labels) {
        for (String label : labels) {
            String base = label.substring(0, label.lastIndexOf('#'));
            text = text.replace(label, base).replace(label.substring(label.lastIndexOf('#')), base);
        }
        return withoutTails(text);
    }

    static String withoutTails(String text) {
        return LABEL_TAIL.matcher(text).replaceAll("");
    }

    private record Mention(String name, int length) {}

    private static Mention mentionAt(String text, int from, List<String> names) {
        for (String name : names) {
            if (text.startsWith(name, from)) {
                return new Mention(name, name.length());
            }
        }
        String token = token(text, from);
        if (token.isEmpty()) {
            return null;
        }
        List<String> partial = names.stream().filter(n -> n.startsWith(token)).toList();
        return partial.size() == 1 ? new Mention(partial.getFirst(), token.length()) : null;
    }

    private static String token(String text, int from) {
        int end = from;
        while (end < text.length() && end - from < 20 && MENTION_STOP.indexOf(text.charAt(end)) < 0) {
            end++;
        }
        return text.substring(from, end);
    }

    private static String appendMention(StringBuilder text, String qq, String name, String selfId, BiFunction<String, String, String> nameOf) {
        if (qq.equals(selfId)) {
            text.append("@你 ");
        } else if (qq.equals("all")) {
            text.append("@全体成员 ");
        } else {
            String known = nameOf.apply(qq, name);
            text.append("@").append(known == null || known.isBlank() ? qq : unforgeable(known)).append(' ');
        }
        return qq;
    }

    private static void addImage(StringBuilder text, List<String> images, String url, String summary, String type,
                                 BiFunction<String, String, String> stickers, String file, JsonArray attachments) {
        String label = !summary.isBlank() ? summary : type.equals("mface") ? "[表情]" : "[图片]";
        String sticker = stickers == null ? null : stickers.apply(file, url);
        text.append(sticker != null ? sticker : label.startsWith("[") ? label : "[" + label + "]");
        boolean web = url.startsWith("http://") || url.startsWith("https://");
        if (web) {
            images.add(url);
        }
        if (sticker == null && web) {
            attachments.add(attachment(type.equals("mface") || !summary.isBlank() ? "sticker" : "image", url, summary, 0));
        }
    }

    private static JsonObject attachment(String kind, String url, String name, long size) {
        JsonObject a = new JsonObject();
        a.addProperty("kind", kind);
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            a.addProperty("url", url);
        }
        if (name != null && !name.isBlank()) {
            a.addProperty("name", abbreviate(name.strip(), 120));
        }
        if (size > 0) {
            a.addProperty("size", size);
        }
        return a;
    }

    private static void attachOther(JsonArray attachments, String type, Function<String, String> field, String label) {
        JsonObject a = switch (type) {
            case "file" -> attachment("file", field.apply("url"), field.apply("name").isBlank() ? field.apply("file") : field.apply("name"),
                    parseSize(field.apply("file_size")));
            case "video" -> attachment("video", field.apply("url"), field.apply("file"), parseSize(field.apply("file_size")));
            case "record" -> attachment("record", field.apply("url"), "", 0);
            case "forward", "node" -> attachment("forward", "", "", 0);
            case "json", "markdown" -> attachment("card", "", label.equals("[卡片消息]") ? ""
                    : label.startsWith("[卡片：") && label.endsWith("]") ? label.substring(4, label.length() - 1) : label, 0);
            case "share" -> attachment("link", field.apply("url"), field.apply("title"), 0);
            case "music" -> attachment("music", field.apply("url"), field.apply("title"), 0);
            case "location" -> attachment("location", "", field.apply("title").isBlank() ? field.apply("content") : field.apply("title"), 0);
            case "dice", "rps" -> attachment(type, "", label, 0);
            case "poke", "shake" -> attachment("poke", "", "", 0);
            case "contact" -> attachment("contact", "", field.apply("id"), 0);
            default -> null;
        };
        if (a != null) {
            attachments.add(a);
        }
    }

    private static long parseSize(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, String> cqParams(String raw) {
        Map<String, String> params = new HashMap<>();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), unescape(pair.substring(eq + 1)));
            }
        }
        return params;
    }

    private static String unescape(String s) {
        return s.replace("&#91;", "[").replace("&#93;", "]").replace("&#44;", ",").replace("&amp;", "&");
    }
}
