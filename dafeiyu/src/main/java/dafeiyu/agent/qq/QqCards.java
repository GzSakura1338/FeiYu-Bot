package dafeiyu.agent.qq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.BiFunction;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

final class QqCards {

    record Card(String type, String source, String title, String desc, String url, String image, String prompt) {

        String text(String cover) {
            if (title.isEmpty()) {
                return prompt.isEmpty() ? "[卡片消息]" : prompt.startsWith("[") ? prompt : "[卡片：" + prompt + "]";
            }
            String link = url.isEmpty() ? "" : " 链接：" + url;
            String picture = cover == null || cover.isEmpty() ? "" : " 封面是图片#" + cover;
            return switch (type) {
                case "music" -> "[音乐 · " + or(source, "音乐分享") + "：" + title + (desc.isEmpty() ? "" : " - " + desc) + link + "]";
                case "contact" -> "[" + or(source, "推荐联系人") + "：" + title + (desc.isEmpty() ? "" : "（" + desc + "）") + "]";
                case "location" -> "[位置：" + title + (desc.isEmpty() ? "" : "，" + desc) + "]";
                case "notice" -> "[群公告：" + (title.equals("群公告") ? desc : desc.isEmpty() ? title : title + " " + desc) + "]";
                default -> "[卡片 · " + or(source, "分享") + "：" + title + (desc.isEmpty() ? "" : "——" + desc) + link + picture + "]";
            };
        }

        JsonObject attachment() {
            JsonObject a = new JsonObject();
            a.addProperty("kind", "card");
            a.addProperty("type", type);
            a.addProperty("name", title.isEmpty() ? prompt : title);
            put(a, "url", url);
            put(a, "source", source);
            put(a, "desc", desc);
            put(a, "image", image);
            return a;
        }
    }

    private static final Map<String, String> CONTACT_KIND = Map.of("com.tencent.troopsharecard", "推荐群", "com.tencent.contact.lua", "推荐好友");

    private QqCards() {}

    static Card parse(String json) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return new Card("card", "", "", "", "", "", "");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return new Card("card", "", "", "", "", "", "");
        }
        String app = optString(root, "app");
        String prompt = clean(optString(root, "prompt"), 80);
        JsonObject meta = optObject(root, "meta");
        if (meta == null) {
            return new Card("card", "", "", "", "", "", prompt);
        }
        JsonObject music = optObject(meta, "music");
        if (music != null) {
            return card("music", music, "tag", "title", "desc", prompt, "jumpUrl", "preview");
        }
        JsonObject news = optObject(meta, "news");
        if (news != null) {
            return card("link", news, "tag", "title", "desc", prompt, "jumpUrl", "preview");
        }
        JsonObject detail = optObject(meta, "detail_1");
        if (detail != null) {
            return card("app", detail, "title", "desc", "", prompt, "qqdocurl", "preview", "url", "icon");
        }
        JsonObject contact = optObject(meta, "contact");
        if (contact != null) {
            Card c = card("contact", contact, "tag", "nickname", "contact", prompt, "jumpUrl", "avatar");
            return c.source().isEmpty() ? new Card("contact", CONTACT_KIND.getOrDefault(app, "推荐联系人"), c.title(), c.desc(), c.url(), c.image(), prompt) : c;
        }
        JsonObject location = optObject(meta, "Location.Search");
        if (location != null) {
            return new Card("location", "位置", clean(optString(location, "name"), 60), clean(optString(location, "address"), 100), "", "", prompt);
        }
        JsonObject notice = optObject(meta, "mannounce");
        if (notice != null) {
            String title = clean(decoded(optString(notice, "title")), 60);
            return new Card("notice", "群公告", title.isEmpty() ? "群公告" : title, clean(decoded(optString(notice, "text")), 300), "", "", prompt);
        }
        for (Map.Entry<String, JsonElement> entry : meta.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                JsonObject any = entry.getValue().getAsJsonObject();
                if (!optString(any, "title").isBlank() || !optString(any, "desc").isBlank()) {
                    return card("card", any, "tag", "title", "desc", prompt, "jumpUrl", "preview", "qqdocurl", "url", "icon");
                }
            }
        }
        return new Card("card", "", "", "", "", "", prompt);
    }

    static String render(String json, com.google.gson.JsonArray attachments, BiFunction<String, String, String> photos) {
        Card card = parse(json);
        String cover = card.image().isEmpty() || photos == null || card.title().isEmpty() ? null : photos.apply("", card.image());
        if (attachments != null) {
            attachments.add(card.attachment());
        }
        return card.text(cover);
    }

    private static Card card(String type, JsonObject o, String sourceKey, String titleKey, String descKey, String prompt, String... links) {
        String url = "";
        String image = "";
        for (String key : links) {
            String value = link(optString(o, key));
            if (value.isEmpty()) {
                continue;
            }
            boolean picture = key.equals("preview") || key.equals("icon") || key.equals("avatar");
            if (picture && image.isEmpty()) {
                image = value;
            } else if (!picture && url.isEmpty()) {
                url = value;
            }
        }
        String title = clean(optString(o, titleKey), 80);
        String desc = descKey.isEmpty() ? "" : clean(optString(o, descKey), 120);
        return new Card(type, clean(optString(o, sourceKey), 20), title.isEmpty() ? desc : title, title.isEmpty() ? "" : desc, url, image, prompt);
    }

    private static String link(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.startsWith("//")) {
            s = "https:" + s;
        } else if (!s.isEmpty() && !s.contains("://") && s.matches("[\\w.-]+\\.[a-z]{2,}(/.*)?")) {
            s = "https://" + s;
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            return "";
        }
        if (s.contains("b23.tv/") && s.contains("?")) {
            s = s.substring(0, s.indexOf('?'));
        }
        return abbreviate(s, 300);
    }

    private static String decoded(String value) {
        try {
            return new String(Base64.getDecoder().decode(value.strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String clean(String value, int max) {
        String s = value == null ? "" : value.replaceAll("[\\[\\]]", "").replaceAll("\\s+", " ").strip();
        return abbreviate(s, max);
    }

    private static String or(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    private static void put(JsonObject a, String key, String value) {
        if (value != null && !value.isEmpty()) {
            a.addProperty(key, value);
        }
    }
}
