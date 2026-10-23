package dafeiyu.agent.memory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.persona.Persona;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

final class StyleStudy {

    record Learned(int added, int updated, int deleted) {}

    private static final int EVERY = 120;
    private static final int WINDOW = 250;
    private static final int MIN_PEOPLE_LINES = 40;
    private static final int MAX_LINE = 120;
    private static final int MAX_OPS = 8;
    private static final int MAX_TAG = 12;
    private static final int MAX_CONTENT = 120;
    private static final long CHECK_MS = 60_000;
    private static final long RETRY_MS = 30 * 60_000;
    private static final Pattern BOT_NAME = Pattern.compile("(?i)bot|机器人|智能体|管家|助手|鲸鱼|大肥鱼");

    private final ChatAgent agent;
    private final MemoryStore memories;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final Map<String, Long> nextCheck = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    StyleStudy(ChatAgent agent, MemoryStore memories, Consumer<String> info, Consumer<String> warn) {
        this.agent = agent;
        this.memories = memories;
        this.info = info;
        this.warn = warn;
    }

    void touched(String session) {
        if (!session.startsWith("group:")) {
            return;
        }
        String groupId = session.substring(6);
        long now = System.currentTimeMillis();
        if (now < nextCheck.getOrDefault(groupId, 0L) || running.contains(groupId)) {
            return;
        }
        nextCheck.put(groupId, now + CHECK_MS);
        long upTo = memories.studiedUpTo(groupId);
        if (upTo >= 0 && memories.heardSince(groupId, upTo) < EVERY || !running.add(groupId)) {
            return;
        }
        Thread.ofVirtual().name("style-" + groupId).start(() -> {
            try {
                study(groupId);
            } catch (AgentException | RuntimeException e) {
                nextCheck.put(groupId, System.currentTimeMillis() + RETRY_MS);
                warn.accept("agent [group:" + groupId + "] learning how people talk failed, will retry later: " + e.getMessage());
            } finally {
                running.remove(groupId);
            }
        });
    }

    Learned study(String groupId) throws AgentException {
        Persona persona = agent.personas().active();
        ModelProfile profile = agent.store().active();
        if (persona == null || profile == null) {
            return new Learned(0, 0, 0);
        }
        List<MemoryStore.Line> lines = memories.chatter(groupId, WINDOW);
        if (lines.isEmpty()) {
            return new Learned(0, 0, 0);
        }
        long latest = lines.getLast().id();
        StringBuilder transcript = new StringBuilder();
        int people = 0;
        for (MemoryStore.Line line : lines) {
            String text = line.text().strip();
            if (text.isEmpty() || !line.mine() && BOT_NAME.matcher(line.name()).find()) {
                continue;
            }
            people += line.mine() ? 0 : 1;
            transcript.append(line.mine() ? "你" : line.name().isBlank() ? "某人" : line.name()).append("：").append(abbreviate(text, MAX_LINE)).append('\n');
        }
        if (people < MIN_PEOPLE_LINES) {
            memories.studied(groupId, latest);
            return new Learned(0, 0, 0);
        }
        List<MemoryStore.Memory> known = memories.list(persona.id(), MemoryStore.STYLE);
        String reply = Reflection.ask(profile, "style:group:" + groupId, prompt(persona, known, transcript.toString().strip()));
        Learned learned = apply(persona.id(), groupId, Reflection.parse(reply), known);
        memories.studied(groupId, latest);
        if (learned.added() + learned.updated() + learned.deleted() > 0) {
            info.accept("agent [group:" + groupId + "] learned how people talk: +" + learned.added() + " ~" + learned.updated() + " -" + learned.deleted());
        }
        return learned;
    }

    private static String prompt(Persona persona, List<MemoryStore.Memory> known, String transcript) {
        StringBuilder list = new StringBuilder();
        for (MemoryStore.Memory m : known) {
            list.append("[").append(m.id()).append("] ").append(m.label().isBlank() ? "" : "【" + m.label() + "】").append(m.content()).append('\n');
        }
        return String.join("\n", List.of(
                "你是「" + persona.name() + "」，在 QQ 群里跟大家一起聊天。下面是一个群最近的聊天记录，“你：”是你自己说的。",
                "你想跟群友学说话：真人在群里是怎么聊天的，一句话多长、怎么接话、怎么吐槽起哄、怎么表示惊讶好笑无语、怎么回应夸奖和调侃、"
                        + "常用的说法和梗、什么时候只回一两个字。也对照一下你自己说的：哪里说得太长、太书面、太客气、太像机器人，真人会怎么说。",
                "",
                "【你的人设，供参考】",
                abbreviate(persona.prompt(), 800),
                "",
                "【已经学到的】（编号用来改和删）",
                list.isEmpty() ? "（还没有）" : list.toString().strip(),
                "",
                "【聊天记录】",
                transcript,
                "",
                "只输出一个 JSON 对象，不要别的文字：",
                "{\"style\": [{\"op\": \"add\", \"tag\": \"什么场合，几个字\", \"content\": \"怎么说\"}, "
                        + "{\"op\": \"update\", \"id\": 编号, \"content\": \"改写后的\"}, {\"op\": \"delete\", \"id\": 编号}]}",
                "",
                "- 学的是说话的方式，不是聊的内容：句子长短、语气词、口头禅、接梗和起哄的套路、表情反应、什么时候只回一两个字、怎么开话题和收话题。",
                "- 一条写一种说法，content 50 字以内：什么情况下，大家怎么说，引一两句原话当例子。比如 tag「听到离谱的事」，content「就回一个“？”或者“何意味”，不用解释」。",
                "- 要是大家常这么说（好几个人、好几次）才学，一个人偶尔说一次的不算。跟已经学到的重复了就 update 合并，学错了、过时了就 delete。",
                "- 对照你自己的话，发现你习惯性说得不像真人（太长、太客气、每句都撒娇、解释太多），写成一条：别怎么说，像大家那样怎么说。",
                "- 不学：别的机器人说的话（自称 AI、智能体，说话像客服的）；骂人、下流、色情、拿地域性别外貌攻击人、政治；"
                        + "拿你开玩笑的叫法（比如叫你大肥鱼）；只有这个群的人才懂的具体人和事（那些不是说话方式）。",
                "- 你还是你：跟人设里的性格和底线冲突的说法不学。",
                "- 没学到新东西就给空数组，最多改 " + MAX_OPS + " 条。"));
    }

    private Learned apply(String personaId, String groupId, JsonObject json, List<MemoryStore.Memory> known) {
        Map<Long, MemoryStore.Memory> byId = new HashMap<>();
        known.forEach(m -> byId.put(m.id(), m));
        int added = 0;
        int updated = 0;
        int deleted = 0;
        int count = 0;
        for (JsonElement element : json.has("style") && json.get("style").isJsonArray() ? json.getAsJsonArray("style") : new com.google.gson.JsonArray()) {
            if (!element.isJsonObject() || count++ >= MAX_OPS) {
                continue;
            }
            JsonObject op = element.getAsJsonObject();
            String tag = abbreviate(optString(op, "tag").strip(), MAX_TAG);
            String content = abbreviate(optString(op, "content").strip(), MAX_CONTENT);
            long id = op.has("id") && op.get("id").isJsonPrimitive() ? parseId(op.get("id").getAsString()) : -1;
            boolean acceptable = !content.isEmpty() && agent.guard().scan(tag + "\n" + content) == null;
            try {
                switch (optString(op, "op")) {
                    case "add" -> {
                        if (acceptable && !memories.knows(personaId, MemoryStore.STYLE, content)) {
                            memories.add(personaId, MemoryStore.STYLE, tag, content, "group:" + groupId);
                            added++;
                        }
                    }
                    case "update" -> {
                        if (acceptable && byId.containsKey(id) && memories.update(personaId, id, content)) {
                            updated++;
                        }
                    }
                    case "delete" -> {
                        if (byId.containsKey(id) && memories.delete(personaId, id)) {
                            deleted++;
                        }
                    }
                    default -> { }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new Learned(added, updated, deleted);
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw.strip().replaceFirst("^\\[?(\\d+)]?$", "$1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
