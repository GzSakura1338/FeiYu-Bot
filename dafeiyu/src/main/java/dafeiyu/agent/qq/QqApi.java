package dafeiyu.agent.qq;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class QqApi {

    public record Param(String name, String type, boolean required, String description) {}

    public record Action(String name, List<String> aliases, String summary, String description, String category, String origin, List<Param> params) {

        public Action {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            summary = summary == null || summary.isBlank() ? name : summary;
            description = description == null ? "" : description;
            category = category == null ? "" : category;
            origin = origin == null ? "" : origin;
            params = params == null ? List.of() : List.copyOf(params);
        }
    }

    private record Catalog(String version, List<String> categories, List<Action> actions) {}

    private record Patch(String summary, String description, List<Param> params) {}

    private static final String RESOURCE = "/snowluma-api.json";
    private static final Map<String, Patch> PATCHES = Map.of(
            "get_recent_contact", new Patch("最近私聊过的人和未读私信",
                    "按最近来消息排序，列出每个人的 QQ、名字、最后一条私聊和时间，以及你最后一次回 TA 之后 TA 又发了几条（未读）。"
                            + "数据来自你自己记下的私聊记录（最近 30 天）；SnowLuma 原版这个接口是占位、恒返回空，这里换成了本地记录",
                    List.of(new Param("count", "number", false, "最多列几个人，默认 10，最多 50"),
                            new Param("only_unread", "boolean", false, "填 true 只列有未读的人"))),
            "set_group_leave", new Patch("退出群（真的会退，自己是群主时直接解散群）",
                    "不是查询接口：调用就立刻退出 group_id 这个群，自己是群主时会解散群，撤销不了。"
                            + "想退群请用 leave_group 工具，它会先把话说完、告诉主人，还有次数限制", null),
            "get_friend_msg_history", new Patch("获取和某个好友的私聊记录（一次只能查一个人）",
                    "返回：{ messages }：好友消息事件对象数组（每项为 OneBot 消息事件，内部字段不固定）。"
                            + "想知道谁私聊过你、谁有未读，先用 get_recent_contact，再用这个看具体内容", null));

    private final String version;
    private final List<String> categories;
    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();

    private QqApi(Catalog catalog) {
        this.version = catalog.version() == null ? "" : catalog.version();
        this.categories = catalog.categories() == null ? List.of() : List.copyOf(catalog.categories());
        for (Action a : catalog.actions() == null ? List.<Action>of() : catalog.actions()) {
            if (a != null && a.name() != null && !a.name().isBlank()) {
                Patch patch = PATCHES.get(a.name());
                Action action = patch == null ? new Action(a.name(), a.aliases(), a.summary(), a.description(), a.category(), a.origin(), a.params())
                        : new Action(a.name(), a.aliases(), patch.summary(), patch.description(), a.category(), a.origin(),
                                patch.params() == null ? a.params() : patch.params());
                actions.put(action.name(), action);
                action.aliases().forEach(alias -> aliases.put(alias, action.name()));
            }
        }
    }

    public static QqApi load() {
        try (InputStream in = QqApi.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return new QqApi(new Catalog("", List.of(), List.of()));
            }
            return new QqApi(new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Catalog.class));
        } catch (IOException | RuntimeException e) {
            return new QqApi(new Catalog("", List.of(), List.of()));
        }
    }

    public String version() {
        return version;
    }

    public List<String> categories() {
        return categories;
    }

    public List<Action> all() {
        return List.copyOf(actions.values());
    }

    public Action get(String name) {
        if (name == null) {
            return null;
        }
        String key = name.strip();
        return actions.get(aliases.getOrDefault(key, key));
    }

    public List<Action> inCategory(String category) {
        return actions.values().stream().filter(a -> a.category().equals(category)).toList();
    }

    public List<Action> search(String query, int limit) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return List.of();
        }
        List<String> words = List.of(q.split("[\\s,，、;；/]+"));
        record Hit(Action action, int score) {}
        List<Hit> hits = new ArrayList<>();
        for (Action a : actions.values()) {
            int score = a.name().equalsIgnoreCase(q) ? 1000 : 0;
            for (String w : words) {
                score += score(a, w);
            }
            if (score > 0) {
                hits.add(new Hit(a, score));
            }
        }
        return hits.stream().sorted(Comparator.comparingInt(Hit::score).reversed()).limit(limit).map(Hit::action).toList();
    }

    private static int score(Action a, String word) {
        if (word.isEmpty()) {
            return 0;
        }
        String name = (a.name() + " " + String.join(" ", a.aliases())).toLowerCase(Locale.ROOT);
        String params = String.join(" ", a.params().stream().map(p -> p.name() + " " + p.description()).toList()).toLowerCase(Locale.ROOT);
        int score = (name.contains(word) ? 30 : 0) + (a.summary().toLowerCase(Locale.ROOT).contains(word) ? 24 : 0)
                + (a.description().toLowerCase(Locale.ROOT).contains(word) ? 10 : 0) + (a.category().contains(word) ? 6 : 0)
                + (params.contains(word) ? 3 : 0);
        if (score == 0 && word.length() > 2 && !word.matches("[\\x00-\\x7F]+")) {
            for (int i = 0; i + 2 <= word.length(); i++) {
                String pair = word.substring(i, i + 2);
                score += (a.summary().contains(pair) ? 6 : 0) + (a.description().contains(pair) ? 2 : 0);
            }
        }
        return score;
    }
}
