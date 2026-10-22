package dafeiyu.agent.mind;

import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.memory.MemoryStore;
import dafeiyu.agent.memory.SkillStore;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.util.Texts;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static dafeiyu.util.Texts.abbreviate;

final class MindCues {

    private static final int MAX_CUE = 90;

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
            return pick("刚醒没多久，脑袋还有点懵，肚子在想早饭", "早上的光照进池子里，暖洋洋的");
        }
        if (minutes >= 11 * 60 + 30 && minutes < 13 * 60) {
            return pick("快到饭点了，肚子咕噜了一声", "中午了，有点想吃东西");
        }
        if (minutes >= 14 * 60 && minutes < 16 * 60) {
            return pick("午后有点犯困，尾巴懒懒地摆着", "下午的时间过得好慢");
        }
        if (minutes >= 17 * 60 + 30 && minutes < 19 * 60 + 30) {
            return pick("天快黑了，该吃晚饭了吧", "傍晚了，肚子有点空");
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
