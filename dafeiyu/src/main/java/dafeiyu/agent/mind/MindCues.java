package dafeiyu.agent.mind;

import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.memory.MemoryStore;
import dafeiyu.agent.memory.SkillStore;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.util.Texts;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static dafeiyu.util.Texts.abbreviate;

final class MindCues {

    private static final int MAX_CUE = 90;
    private static final int STUCK_WINDOW = 6;
    private static final int STUCK_HITS = 5;
    private static final int STUCK_WORDS = 2;
    private static final String FILLER = "零一二三四五六七八九十百千两几半的了着我你他她它在是就也都那这里上下到把被给和跟又还再很太呢吧啊呀嘛啦不没有个们得地会要想说去来过点句些么吗哪谁";
    private static final Set<String> COMMON = Set.of("鲸鱼", "小鲸", "主人", "尾巴", "今天", "时候", "现在", "知道", "觉得", "可以", "别人", "东西",
            "事情", "心里", "刚才", "应该", "其实", "好像", "一直", "已经", "自己", "什么", "没有", "然后", "因为", "所以", "但是", "可是", "而且",
            "如果", "不过", "只是", "怎么", "回来", "之后", "以后", "再说", "分钟", "小时", "最后", "面前", "外头", "下午", "上午", "晚上", "早上",
            "中午", "半夜", "群里", "这会", "会儿", "那边", "这边", "说话", "一句", "等会", "待会");
    static List<String> stuckOn(Collection<String> thoughts) {
        if (thoughts.size() < STUCK_WINDOW) {
            return List.of();
        }
        List<String> window = new ArrayList<>(thoughts).subList(thoughts.size() - STUCK_WINDOW, thoughts.size());
        Map<String, Integer> seen = new HashMap<>();
        for (String thought : window) {
            String han = thought.replaceAll("[^\\p{IsHan}]", "");
            Set<String> words = new HashSet<>();
            for (int i = 0; i + 1 < han.length(); i++) {
                String w = han.substring(i, i + 2);
                if (FILLER.indexOf(w.charAt(0)) < 0 && FILLER.indexOf(w.charAt(1)) < 0 && !COMMON.contains(w)) {
                    words.add(w);
                }
            }
            words.forEach(w -> seen.merge(w, 1, Integer::sum));
        }
        List<String> stuck = new ArrayList<>();
        seen.entrySet().stream().filter(e -> e.getValue() >= STUCK_HITS)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    if (stuck.size() < 4 && stuck.stream().noneMatch(s -> s.indexOf(e.getKey().charAt(0)) >= 0 || s.indexOf(e.getKey().charAt(1)) >= 0)) {
                        stuck.add(e.getKey());
                    }
                });
        return stuck.size() >= STUCK_WORDS ? stuck : List.of();
    }

    private final ChatAgent agent;
    private final QqChatBridge bridge;

    MindCues(ChatAgent agent, QqChatBridge bridge) {
        this.agent = agent;
        this.bridge = bridge;
    }

    String next(String personaId, String recent) {
        List<Supplier<String>> sources = new ArrayList<>(List.of(
                () -> body(LocalTime.now()),
                () -> person(personaId),
                () -> person(personaId),
                () -> self(personaId, "心愿和约定", "想起"),
                () -> self(personaId, null, "忽然想到自己"),
                () -> diary(personaId),
                () -> skill(personaId),
                () -> learned(personaId),
                () -> group()));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (!sources.isEmpty()) {
            String cue = sources.remove(random.nextInt(sources.size())).get();
            if (cue != null && !cue.isBlank() && !recent.contains(cue)) {
                return abbreviate(cue, MAX_CUE);
            }
        }
        return null;
    }

    static double similarity(String a, String b) {
        Set<String> x = Texts.bigrams(a);
        Set<String> y = Texts.bigrams(b);
        return x.size() <= y.size() ? Texts.overlap(x, y) : Texts.overlap(y, x);
    }

    private static String body(LocalTime now) {
        int minutes = now.getHour() * 60 + now.getMinute();
        if (minutes >= 7 * 60 && minutes < 9 * 60) {
            return pick("刚醒没多久，脑袋还有点懵", "早上的光照进池子里，暖洋洋的", "新的一天，想想今天干点什么好玩的");
        }
        if (minutes >= 11 * 60 + 30 && minutes < 13 * 60) {
            return pick("中午了，群里的人大概都去吃饭了", "中午有点犯困", "快到饭点了，肚子咕噜了一声");
        }
        if (minutes >= 14 * 60 && minutes < 16 * 60) {
            return pick("午后有点犯困，尾巴懒懒地摆着", "下午的时间过得好慢");
        }
        if (minutes >= 17 * 60 + 30 && minutes < 19 * 60 + 30) {
            return pick("天快黑了，一天又快过去了", "傍晚了，外面的颜色一点点变暗", "傍晚了，大家差不多该下班放学了");
        }
        if (minutes >= 22 * 60 || minutes < 60) {
            return pick("夜深了，眼皮有点沉", "这么晚了，群里的人都睡了吗");
        }
        return null;
    }

    private String person(String personaId) {
        Set<String> owners = Set.copyOf(agent.personas().owners());
        List<MemoryStore.Scope> people = agent.memories().scopes(personaId).stream()
                .filter(s -> s.scope().startsWith("user:") || s.scope().startsWith("group:")).toList();
        if (people.isEmpty()) {
            return null;
        }
        MemoryStore.Scope scope = people.get(ThreadLocalRandom.current().nextInt(people.size()));
        List<MemoryStore.Memory> about = agent.memories().list(personaId, scope.scope()).stream()
                .filter(m -> MemoryStore.visible(m, Mind.SESSION, owners)).toList();
        if (about.isEmpty()) {
            return null;
        }
        MemoryStore.Memory m = about.get(ThreadLocalRandom.current().nextInt(about.size()));
        String who = scope.scope().startsWith("group:") ? "「" + bridge.groupName(scope.scope().substring(6)) + "」"
                : m.label().isBlank() ? "一个认识的人" : m.label();
        return "想起" + who + "：" + m.content();
    }

    private String self(String personaId, String topic, String lead) {
        List<MemoryStore.Memory> topics = agent.memories().selfTopics(personaId).stream()
                .filter(m -> topic == null ? !m.label().equals("心愿和约定") : m.label().equals(topic)).toList();
        if (topics.isEmpty()) {
            return null;
        }
        String sentence = sentence(topics.get(ThreadLocalRandom.current().nextInt(topics.size())).content());
        return sentence == null ? null : lead + "：" + sentence;
    }

    private String diary(String personaId) {
        List<MemoryStore.Memory> days = agent.memories().list(personaId, MemoryStore.DIARY).stream().limit(3).toList();
        if (days.isEmpty()) {
            return null;
        }
        MemoryStore.Memory day = days.get(ThreadLocalRandom.current().nextInt(days.size()));
        String sentence = sentence(day.content());
        return sentence == null ? null : "回味起" + (day.label().equals(MemoryStore.dayLabel(System.currentTimeMillis())) ? "今天" : "那天")
                + "的事：" + sentence;
    }

    private String skill(String personaId) {
        List<SkillStore.Skill> skills = agent.skills().list(personaId);
        if (skills.isEmpty()) {
            return null;
        }
        SkillStore.Skill s = skills.get(ThreadLocalRandom.current().nextInt(skills.size()));
        return "想起自己学会的「" + s.name() + "」，下次碰到能派上用场";
    }

    private String learned(String personaId) {
        List<MemoryStore.Memory> known = agent.memories().list(personaId, MemoryStore.KNOWLEDGE);
        if (known.isEmpty()) {
            return null;
        }
        MemoryStore.Memory m = known.get(ThreadLocalRandom.current().nextInt(known.size()));
        return m.label().isBlank() ? null : "想起之前查过的「" + m.label() + "」，还想多知道一点";
    }

    private String group() {
        Map<String, String> groups = bridge.groupNames();
        if (groups.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>(groups.values());
        return "好一阵没去「" + names.get(ThreadLocalRandom.current().nextInt(names.size())) + "」看看了，不知道那边在聊什么";
    }

    private static String sentence(String text) {
        List<String> parts = new ArrayList<>();
        for (String s : text.split("[。！？!?\\n；;]")) {
            if (s.strip().length() >= 6) {
                parts.add(s.strip());
            }
        }
        return parts.isEmpty() ? null : parts.get(ThreadLocalRandom.current().nextInt(parts.size()));
    }

    private static String pick(String... options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }
}
