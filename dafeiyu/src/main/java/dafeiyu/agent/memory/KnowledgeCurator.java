package dafeiyu.agent.memory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.persona.Persona;
import dafeiyu.db.Settings;
import dafeiyu.util.Texts;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class KnowledgeCurator {

    public record Removed(String kind, String title, String reason) {}

    public record Section(String kind, String label, int before, int after, int merged, int dropped, List<String> top) {}

    public record Report(long at, boolean manual, List<Section> sections, List<Removed> removed, String error) {}

    private record Item(long id, String title, String description, String content, String source, long createdAt, MemoryStore.Usage usage) {}

    public static final String SKILL = "skill";
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final String ENABLED_KEY = "agent.memory.curate";
    private static final String REPORT_KEY = "agent.memory.curated";
    private static final long FRESH_MS = 24 * 3_600_000L;
    private static final long RECENT_USE_MS = 3 * 86_400_000L;
    private static final long TRASH_KEEP_MS = 30 * 86_400_000L;
    private static final long CATCH_UP_MS = 3 * 60_000L;
    private static final double MAX_DROPPED = 0.25;
    private static final double MAX_MERGED = 0.25;
    private static final int MIN_ITEMS = 5;
    private static final double RELATED = 0.1;
    private static final int MAX_SKILL_TEXT = 1_500;
    private static final int MAX_SKILL_MERGES = 6;
    private static final int MAX_REMOVED_SHOWN = 40;
    private static final Gson GSON = new Gson();

    static {
        LABELS.put(MemoryStore.KNOWLEDGE, "知识");
        LABELS.put(SKILL, "技能");
        LABELS.put(MemoryStore.STYLE, "说话方式");
    }

    private final ChatAgent agent;
    private final MemoryStore memories;
    private final SkillStore skills;
    private final Settings settings;
    private final ReentrantLock writing;
    private final ScheduledExecutorService scheduler;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final AtomicBoolean running = new AtomicBoolean();

    KnowledgeCurator(ChatAgent agent, MemoryStore memories, SkillStore skills, ReentrantLock writing, ScheduledExecutorService scheduler,
                     Consumer<String> info, Consumer<String> warn) {
        this.agent = agent;
        this.memories = memories;
        this.skills = skills;
        this.settings = memories.settings();
        this.writing = writing;
        this.scheduler = scheduler;
        this.info = info;
        this.warn = warn;
    }

    void start() {
        Report last = last();
        LocalDate lastDay = last == null ? null : LocalDate.ofInstant(java.time.Instant.ofEpochMilli(last.at()), ZoneId.systemDefault());
        if (lastDay != null && lastDay.isBefore(LocalDate.now())) {
            scheduler.schedule(() -> Thread.ofVirtual().start(this::nightly), CATCH_UP_MS, TimeUnit.MILLISECONDS);
        }
        scheduleMidnight();
    }

    private void scheduleMidnight() {
        LocalDateTime now = LocalDateTime.now();
        long delay = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis() + 30_000;
        scheduler.schedule(() -> Thread.ofVirtual().start(() -> {
            try {
                nightly();
            } finally {
                scheduleMidnight();
            }
        }), delay, TimeUnit.MILLISECONDS);
    }

    private void nightly() {
        if (!enabled() || agent.personas().active() == null || agent.store().active() == null) {
            return;
        }
        try {
            run(false);
        } catch (AgentException | RuntimeException e) {
            warn.accept("agent: tidying up knowledge failed: " + e.getMessage());
        }
    }

    public boolean enabled() {
        return !"false".equals(settings.get(ENABLED_KEY));
    }

    public void setEnabled(boolean enabled) {
        settings.put(ENABLED_KEY, String.valueOf(enabled));
    }

    public boolean running() {
        return running.get();
    }

    public Report last() {
        try {
            return GSON.fromJson(settings.get(REPORT_KEY), Report.class);
        } catch (JsonParseException e) {
            return null;
        }
    }

    public Report run(boolean manual) throws AgentException {
        Persona persona = agent.personas().active();
        ModelProfile profile = agent.store().active();
        if (persona == null || profile == null) {
            throw new AgentException(Kind.LOCAL, "还没选人格或模型");
        }
        if (!running.compareAndSet(false, true)) {
            throw new AgentException(Kind.LOCAL, "正在整理，等这次做完");
        }
        try {
            List<Section> sections = new ArrayList<>();
            List<Removed> removed = new ArrayList<>();
            String error = null;
            for (String kind : LABELS.keySet()) {
                try {
                    Section section = curate(persona, profile, kind, removed);
                    if (section != null) {
                        sections.add(section);
                    }
                } catch (AgentException | RuntimeException e) {
                    error = (error == null ? "" : error + "；") + LABELS.get(kind) + "没整理成：" + e.getMessage();
                    warn.accept("agent: tidying up " + kind + " failed: " + e.getMessage());
                }
            }
            int purged = memories.purgeTrash(persona.id(), System.currentTimeMillis() - TRASH_KEEP_MS);
            Report report = new Report(System.currentTimeMillis(), manual, sections,
                    removed.subList(0, Math.min(removed.size(), MAX_REMOVED_SHOWN)), error);
            settings.put(REPORT_KEY, GSON.toJson(report));
            info.accept("agent: tidied up knowledge: " + String.join(", ", sections.stream()
                    .map(s -> s.kind() + " " + s.before() + "->" + s.after() + " (merged " + s.merged() + ", dropped " + s.dropped() + ")").toList())
                    + (purged > 0 ? ", emptied " + purged + " old item(s) from the trash" : ""));
            return report;
        } finally {
            running.set(false);
        }
    }

    private List<Item> load(String personaId, String kind) {
        if (kind.equals(SKILL)) {
            Map<Long, MemoryStore.Usage> usage = skills.usage(personaId);
            return skills.list(personaId).stream().map(s -> new Item(s.id(), s.name(), s.description(), s.content(), s.source(), s.createdAt(),
                    usage.getOrDefault(s.id(), new MemoryStore.Usage(0, 0, 0)))).toList();
        }
        Map<Long, MemoryStore.Usage> usage = memories.usage(personaId, kind);
        return memories.list(personaId, kind).stream().map(m -> new Item(m.id(), m.label(), "", m.content(), m.source(), m.createdAt(),
                usage.getOrDefault(m.id(), new MemoryStore.Usage(0, 0, 0)))).toList();
    }

    private Section curate(Persona persona, ModelProfile profile, String kind, List<Removed> removed) throws AgentException {
        List<Item> items = load(persona.id(), kind);
        if (items.size() < MIN_ITEMS) {
            return null;
        }
        long now = System.currentTimeMillis();
        String prompt = prompt(persona, kind, items, now);
        JsonObject json = readable(Reflection.ask(profile, "curate:" + kind, prompt), kind);
        if (json == null) {
            json = readable(Reflection.ask(profile, "curate:" + kind, prompt), kind);
        }
        if (json == null) {
            throw new AgentException(Kind.LOCAL, "两次回的都不是能读的 JSON");
        }
        writing.lock();
        try {
            return apply(persona.id(), kind, items, json, now, removed);
        } finally {
            writing.unlock();
        }
    }

    private static String prompt(Persona persona, String kind, List<Item> items, long now) {
        StringBuilder list = new StringBuilder();
        for (Item item : items) {
            list.append("[").append(item.id()).append("] ");
            if (kind.equals(SKILL)) {
                list.append(item.title()).append("（什么时候用：").append(item.description()).append("）\n")
                        .append(abbreviate(item.content().strip(), MAX_SKILL_TEXT)).append('\n');
            } else {
                list.append(kind.equals(MemoryStore.KNOWLEDGE) ? "「" + item.title() + "」：" : item.title().isBlank() ? "" : "【" + item.title() + "】")
                        .append(item.content().replace('\n', ' '));
            }
            list.append("（记下 ").append(days(now - item.createdAt())).append("，")
                    .append(item.usage().usedCount() == 0 ? "没用到过" : "用到过 " + item.usage().usedCount() + " 次，最近一次在 " + days(now - item.usage().usedAt()) + "前")
                    .append("）\n");
        }
        String what = switch (kind) {
            case SKILL -> "这些是你在聊天里学会的本事和做法，碰到对应的事会照着做。";
            case MemoryStore.STYLE -> "这些是你跟群友学来的说话方式。";
            default -> "这些是你上网查明白后记下的词和知识，聊天里有人提到这个词时你会想起来。";
        };
        String title = switch (kind) {
            case SKILL -> "技能名";
            case MemoryStore.STYLE -> "什么场合，几个字";
            default -> "词或东西的名字";
        };
        return String.join("\n", List.of(
                "你是「" + persona.name() + "」。现在是半夜 0 点，你在整理自己的" + LABELS.get(kind) + "。" + what,
                "每条前面方括号里是编号，最后括号里是记下多久了、用到过几次（“用到”是聊天时真的想起来、用上了）。",
                "",
                list.toString().strip(),
                "",
                "只输出一个 JSON 对象，不要别的文字：",
                "{\"scores\": {\"编号\": 1到5的整数}, \"merge\": [{\"ids\": [编号, 编号], \"title\": \"" + title + "\", "
                        + (kind.equals(SKILL) ? "\"description\": \"什么时候用\", " : "") + "\"content\": \"合并后的完整内容\"}], "
                        + "\"drop\": [{\"id\": 编号, \"reason\": \"一句话为什么不要了\"}]}",
                "",
                "- scores：每条都打分，看它对你以后聊天、做事有多大用。5 是常常用得上、通用、准确的；3 是偶尔有用；1 是一次性的、过时的、琐碎的、可能是错的。"
                        + "用到过的次数多、最近还在用的要往高打，记下很久一次都没用到的往低打。",
                "- merge：讲的是同一件事、重复或者高度相近的几条，合成一条。content 要把每条里有用的东西都留下，不要丢细节"
                        + (kind.equals(SKILL) ? "，可以分行写步骤，2000 字以内；这次最多合 " + MAX_SKILL_MERGES + " 组，挑重复最明显的，别的下次再合" : "，100 字以内")
                        + "。没有能合的就给空数组。",
                "- drop：真的没用了才丢：一次性的小事、过时的、琐碎到不值得记的、明显错的。拿不准就留着；打 1 分、最多 2 分的才考虑丢。合并掉的不用再写进 drop。",
                "- 你还是你：跟人设里的性格和底线冲突的内容可以丢。"));
    }

    private Section apply(String personaId, String kind, List<Item> items, JsonObject json, long now, List<Removed> removed) {
        Map<Long, Item> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.id(), i));
        Map<Long, Integer> scores = scores(json, byId);
        Set<Long> gone = new HashSet<>();
        int mergeBudget = (int) Math.floor(items.size() * MAX_MERGED);
        int dropBudget = (int) Math.floor(items.size() * MAX_DROPPED);
        int merged = 0;
        int dropped = 0;
        int groups = 0;
        for (JsonElement element : array(json, "merge")) {
            if (!element.isJsonObject() || kind.equals(SKILL) && groups >= MAX_SKILL_MERGES) {
                continue;
            }
            JsonObject op = element.getAsJsonObject();
            List<Item> group = new ArrayList<>();
            for (JsonElement id : array(op, "ids")) {
                Item item = byId.get(parseId(id));
                if (item != null && !gone.contains(item.id()) && !group.contains(item)) {
                    group.add(item);
                }
            }
            String title = optString(op, "title").strip().replaceAll("^[「【]|[」】]$", "");
            String content = optString(op, "content").strip();
            if (content.startsWith(title)) {
                content = content.substring(title.length()).replaceFirst("^[」】]?\\s*[：:]\\s*", "").strip();
            }
            Set<String> mergedWords = Texts.bigrams(title + content);
            String whole = title + content;
            group.removeIf(i -> !whole.contains(i.title()) && Texts.overlap(Texts.bigrams(i.title() + i.content()), mergedWords) < RELATED);
            if (group.size() < 2 || merged + group.size() - 1 > mergeBudget || title.isEmpty() || content.isEmpty()
                    || agent.guard().scan(title + "\n" + content) != null) {
                continue;
            }
            Item keep = group.stream().max(Comparator.comparingInt((Item i) -> i.usage().usedCount()).thenComparingLong(i -> -i.createdAt())).orElseThrow();
            for (Item other : group) {
                if (other != keep) {
                    remove(personaId, kind, other, "并进了「" + title + "」");
                    removed.add(new Removed(kind, other.title(), "并进了「" + title + "」"));
                    gone.add(other.id());
                    merged++;
                }
            }
            rewrite(personaId, kind, keep, title, optString(op, "description").strip(), content);
            groups++;
            scores.merge(keep.id(), group.stream().mapToInt(i -> scores.getOrDefault(i.id(), 0)).max().orElse(0), Math::max);
        }
        for (JsonElement element : array(json, "drop")) {
            if (!element.isJsonObject() || dropped >= dropBudget) {
                continue;
            }
            JsonObject op = element.getAsJsonObject();
            Item item = byId.get(op.has("id") ? parseId(op.get("id")) : -1);
            if (item == null || gone.contains(item.id()) || now - item.createdAt() < FRESH_MS || now - item.usage().usedAt() < RECENT_USE_MS
                    || scores.getOrDefault(item.id(), 1) > 2) {
                continue;
            }
            String reason = abbreviate(optString(op, "reason").strip(), 60);
            remove(personaId, kind, item, reason.isEmpty() ? "不重要" : reason);
            removed.add(new Removed(kind, item.title(), reason));
            gone.add(item.id());
            dropped++;
        }
        scores.keySet().removeAll(gone);
        if (kind.equals(SKILL)) {
            skills.rate(personaId, scores);
        } else {
            memories.rate(personaId, scores);
        }
        List<String> top = items.stream().filter(i -> !gone.contains(i.id()))
                .sorted(Comparator.comparingInt((Item i) -> -scores.getOrDefault(i.id(), 0)))
                .limit(5).map(Item::title).toList();
        return new Section(kind, LABELS.get(kind), items.size(), items.size() - gone.size(), merged, dropped, top);
    }

    private Map<Long, Integer> scores(JsonObject json, Map<Long, Item> byId) {
        Map<Long, Integer> out = new HashMap<>();
        JsonElement raw = json.get("scores");
        if (raw != null && raw.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : raw.getAsJsonObject().entrySet()) {
                put(out, byId, parseId(e.getKey()), e.getValue());
            }
        } else if (raw != null && raw.isJsonArray()) {
            for (JsonElement e : raw.getAsJsonArray()) {
                if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                    put(out, byId, parseId(e.getAsJsonObject().get("id")), e.getAsJsonObject().get("score"));
                }
            }
        }
        return out;
    }

    private static void put(Map<Long, Integer> out, Map<Long, Item> byId, long id, JsonElement score) {
        if (byId.containsKey(id) && score != null && score.isJsonPrimitive()) {
            try {
                out.put(id, Math.max(1, Math.min(5, (int) Math.round(score.getAsDouble()))));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void remove(String personaId, String kind, Item item, String reason) {
        memories.trash(personaId, kind, item.title(), item.description(), item.content(), item.source(), item.createdAt(), reason);
        if (kind.equals(SKILL)) {
            skills.delete(personaId, item.id());
        } else {
            memories.delete(personaId, item.id());
        }
    }

    private void rewrite(String personaId, String kind, Item keep, String title, String description, String content) {
        try {
            if (kind.equals(SKILL)) {
                SkillStore.Skill same = skills.named(personaId, title);
                skills.update(personaId, keep.id(), same == null || same.id() == keep.id() ? title : keep.title(),
                        description.isEmpty() ? keep.description() : description, content);
            } else {
                memories.rewrite(personaId, keep.id(), title, content);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    public String restore(String personaId, long trashId) {
        MemoryStore.Trashed t = memories.trashed(personaId).stream().filter(x -> x.id() == trashId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("回收站里没有这一条"));
        switch (t.kind()) {
            case SKILL -> skills.add(personaId, t.title(), t.description(), t.content(), t.source());
            case MemoryStore.KNOWLEDGE -> memories.add(personaId, MemoryStore.KNOWLEDGE, "", "「" + t.title() + "」：" + t.content(), t.source());
            default -> memories.add(personaId, t.kind(), t.title(), t.content(), t.source());
        }
        memories.forgetTrashed(personaId, trashId);
        return t.kind();
    }

    public static String label(String kind) {
        return LABELS.getOrDefault(kind, kind);
    }

    private static String days(long ms) {
        long hours = Math.max(0, ms) / 3_600_000L;
        return hours < 24 ? hours + " 小时" : hours / 24 + " 天";
    }

    private static long parseId(JsonElement element) {
        try {
            return parseId(element.getAsString());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw.strip().replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private JsonObject readable(String reply, String kind) {
        try {
            return Reflection.parse(reply);
        } catch (AgentException e) {
            JsonObject partial = salvage(reply);
            if (partial == null) {
                warn.accept("agent: tidying up " + kind + " got an unreadable reply (" + e.getMessage() + ")");
                return null;
            }
            warn.accept("agent: tidying up " + kind + " got a broken reply (" + e.getMessage() + "), using the complete parts: "
                    + String.join(", ", partial.entrySet().stream().map(en -> en.getKey()
                            + (en.getValue().isJsonArray() ? " " + en.getValue().getAsJsonArray().size() : "")).toList()));
            return partial;
        }
    }

    static JsonObject salvage(String reply) {
        int start = reply == null ? -1 : reply.indexOf('{');
        if (start < 0) {
            return null;
        }
        JsonObject out = new JsonObject();
        try (JsonReader reader = new JsonReader(new StringReader(reply.substring(start)))) {
            reader.setStrictness(Strictness.LENIENT);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    out.add(name, JsonParser.parseReader(reader));
                    continue;
                }
                JsonArray items = new JsonArray();
                out.add(name, items);
                reader.beginArray();
                while (reader.hasNext()) {
                    items.add(JsonParser.parseReader(reader));
                }
                reader.endArray();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return out.has("scores") || !array(out, "merge").isEmpty() || !array(out, "drop").isEmpty() ? out : null;
    }

    private static com.google.gson.JsonArray array(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new com.google.gson.JsonArray();
    }
}
