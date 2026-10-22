package dafeiyu.agent.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QqFaces {

    static final List<String> PALETTE = List.of(
            "得意", "害羞", "调皮", "呲牙", "可爱", "白眼", "傲慢", "困", "流汗", "憨笑", "疑问", "晕", "再见",
            "擦汗", "抠鼻", "坏笑", "哈欠", "委屈", "快哭了", "可怜", "卖萌", "小纠结", "斜眼笑", "doge", "笑哭",
            "托腮", "捂脸", "辣眼睛", "哦哟", "头秃", "问号脸", "暗中观察", "emm", "吃瓜", "我酸了", "摸鱼",
            "期待", "喵喵", "比心", "生气", "贴贴", "裂开", "略略略", "无语", "大哭", "睡", "爱心", "赞");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("好奇", "疑问"), Map.entry("疑惑", "疑问"), Map.entry("问号", "问号脸"), Map.entry("思考", "仔细分析"),
            Map.entry("开心", "呲牙"), Map.entry("高兴", "呲牙"), Map.entry("哈哈", "呲牙"), Map.entry("笑", "憨笑"), Map.entry("嘿嘿", "坏笑"),
            Map.entry("伤心", "难过"), Map.entry("哭", "大哭"), Map.entry("哭哭", "快哭了"), Map.entry("震惊", "惊讶"), Map.entry("吃惊", "惊讶"),
            Map.entry("害怕", "惊恐"), Map.entry("愤怒", "生气"), Map.entry("气", "生气"), Map.entry("炸毛", "生气"), Map.entry("哼", "傲慢"),
            Map.entry("抱抱", "拥抱"), Map.entry("亲亲", "飞吻"), Map.entry("无聊", "哈欠"), Map.entry("累", "困"), Map.entry("晚安", "睡"),
            Map.entry("骄傲", "得意"), Map.entry("撒娇", "卖萌"), Map.entry("汗", "流汗"), Map.entry("心虚", "流汗"), Map.entry("加油", "奋斗"),
            Map.entry("ok", "OK"), Map.entry("爱你", "比心"), Map.entry("喜欢", "爱心"), Map.entry("星星眼", "期待"), Map.entry("脸红", "害羞"),
            Map.entry("叹气", "无奈"), Map.entry("摊手", "无奈"), Map.entry("拜拜", "再见"), Map.entry("生无可恋", "面无表情"),
            Map.entry("委屈巴巴", "委屈"), Map.entry("狗头", "doge"), Map.entry("滑稽", "斜眼笑"), Map.entry("冷笑", "呵呵哒"));
    private static final Set<String> KEEP = Set.of("沉默");
    private static final Pattern NAME_LIKE = Pattern.compile("[\\p{IsHan}A-Za-z+!！]{1,6}");
    private static final Map<String, String> NAMES = new HashMap<>();
    private static final Map<String, String> IDS = new LinkedHashMap<>();
    private static final Pattern TOKEN = Pattern.compile("\\[([^\\[\\]\\n]{1,6})]");

    static {
        try (InputStream in = QqFaces.class.getResourceAsStream("/qq-faces.tsv")) {
            if (in != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        String id = line.substring(0, tab);
                        String name = line.substring(tab + 1).strip();
                        NAMES.put(id, name);
                        IDS.putIfAbsent(name, id);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private QqFaces() {}

    static String describe(String id) {
        String name = NAMES.get(id);
        return name == null ? "[QQ表情]" : "[" + name + "]";
    }

    public static String palette() {
        return String.join(" ", PALETTE.stream().filter(IDS::containsKey).map(n -> "[" + n + "]").toList());
    }

    public static boolean contains(String text) {
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (IDS.containsKey(m.group(1))) {
                return true;
            }
        }
        return false;
    }

    static String resolve(String name) {
        if (IDS.containsKey(name)) {
            return name;
        }
        String alias = ALIASES.get(name.toLowerCase(Locale.ROOT));
        return alias != null && IDS.containsKey(alias) ? alias : null;
    }

    public static String limit(String text, boolean cooldown) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        StringBuilder bare = new StringBuilder();
        int last = 0;
        int kept = 0;
        while (m.find()) {
            String name = resolve(m.group(1));
            if (name == null && (KEEP.contains(m.group(1)) || !NAME_LIKE.matcher(m.group(1)).matches())) {
                continue;
            }
            out.append(text, last, m.start());
            bare.append(text, last, m.start());
            last = m.end();
            if (name != null && kept == 0) {
                out.append('[').append(name).append(']');
                kept++;
            }
        }
        out.append(text.substring(last));
        bare.append(text.substring(last));
        String plain = bare.toString().replaceAll("\\s{2,}", " ").strip();
        if (plain.isEmpty()) {
            return out.toString().strip();
        }
        return cooldown ? plain : out.toString().replaceAll("\\s{2,}", " ").strip();
    }

    static JsonArray segments(String text) {
        JsonArray out = new JsonArray();
        StringBuilder buffer = new StringBuilder();
        Matcher m = TOKEN.matcher(text);
        int last = 0;
        while (m.find()) {
            String name = resolve(m.group(1));
            String id = name == null ? null : IDS.get(name);
            if (id == null) {
                continue;
            }
            buffer.append(text, last, m.start());
            flush(out, buffer);
            JsonObject data = new JsonObject();
            data.addProperty("id", id);
            JsonObject face = new JsonObject();
            face.addProperty("type", "face");
            face.add("data", data);
            out.add(face);
            last = m.end();
        }
        buffer.append(text.substring(last));
        flush(out, buffer);
        return out;
    }

    private static void flush(JsonArray out, StringBuilder buffer) {
        String text = buffer.toString();
        buffer.setLength(0);
        if (text.isBlank()) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("text", text);
        JsonObject segment = new JsonObject();
        segment.addProperty("type", "text");
        segment.add("data", data);
        out.add(segment);
    }
}
