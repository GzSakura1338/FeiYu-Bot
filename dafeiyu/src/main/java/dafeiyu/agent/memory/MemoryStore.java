package dafeiyu.agent.memory;

import dafeiyu.agent.qq.QqMessages;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;
import dafeiyu.util.Texts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dafeiyu.util.Texts.abbreviate;

public final class MemoryStore {

    public record Memory(long id, String scope, String label, String content, String source, long createdAt, long updatedAt) {}

    public record Scope(String scope, String label, int count, long updatedAt) {}

    public record Turn(long id, String role, String content, long createdAt, String toolCalls, String toolName, boolean toolError) {}

    public record Line(long id, boolean mine, String userId, String name, String text) {}

    private record Voice(long at, int[] lengths) {}

    public static final String SELF = "self";
    public static final String DIARY = "diary";
    public static final String KNOWLEDGE = "knowledge";
    public static final String STYLE = "style";
    private static final int MAX_STYLE = 80;
    private static final int STYLE_SHOWN = 12;
    private static final int STYLE_HERE = 5;
    private static final long VOICE_TTL_MS = 10 * 60_000L;
    private static final Pattern STYLE_TAG = Pattern.compile("[「【]?([^」】：:]{1,12})[」】]?\\s*[：:]\\s*(.+)");
    private static final int MAX_KNOWLEDGE = 500;
    private static final int MAX_RELEVANT_KNOWLEDGE = 5;
    private static final int SELF_BUDGET = 600;
    private static final int GROUP_BUDGET = 900;
    private static final int PERSON_BUDGET = 700;
    private static final int LOOKUP_BUDGET = 4_000;
    private static final Pattern TERM = Pattern.compile("^\\s*[「“\"']?([^」”\"'：:\\n]{1,30})[」”\"']?\\s*[：:]\\s*(.+)$", Pattern.DOTALL);
    public static final List<String> SELF_TOPICS = List.of("性格", "喜好", "习惯", "经历", "心愿和约定");
    public static final int MAX_CONTENT = 200;
    public static final int MAX_TOPIC = 800;
    public static final int MAX_DIARY = 2000;
    private static final Pattern SCOPE = Pattern.compile("self|diary|knowledge|style|(user|group):\\d{5,12}");
    private static final Pattern DAY_LABEL = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TAIL = Pattern.compile("#(\\d{4})(?!\\d)");
    private static final String PEER = "群友";
    private static final String AUTO_KEY = "agent.memory.auto";
    private static final String STUDIED_KEY = "agent.memory.styleStudied.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);
    private static final DateTimeFormatter FULL_DAY = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA);
    private static final String COLUMNS = "id, scope, label, content, source, created_at, updated_at";

    private static final long LATELY_MS = 6 * 3_600_000L;
    private static final long THREAD_MS = 30 * 60_000L;

    private final Database db;
    private final Settings settings;
    private final ContentGuard guard;
    private final Map<String, Voice> voices = new ConcurrentHashMap<>();

    public MemoryStore(Database db, ContentGuard guard) {
        this.db = db;
        this.settings = new Settings(db);
        this.guard = guard;
    }

    public static boolean validScope(String scope) {
        return scope != null && SCOPE.matcher(scope).matches();
    }

    public boolean auto() {
        return !"false".equals(settings.get(AUTO_KEY));
    }

    public void setAuto(boolean enabled) {
        settings.put(AUTO_KEY, String.valueOf(enabled));
    }

    public List<Scope> scopes(String personaId) {
        return db.list("""
                SELECT scope, COUNT(*) AS n, MAX(updated_at) AS latest,
                       (SELECT label FROM memories l WHERE l.persona_id = m.persona_id AND l.scope = m.scope AND l.label <> ''
                        ORDER BY l.updated_at DESC LIMIT 1) AS label
                FROM memories m WHERE persona_id = ? GROUP BY scope ORDER BY latest DESC
                """, rs -> new Scope(rs.getString("scope"), nonNull(rs.getString("label")), rs.getInt("n"), rs.getLong("latest")), personaId);
    }

    public List<Memory> list(String personaId, String scope) {
        String order = scope.equals(DIARY) ? "label DESC, id DESC" : "updated_at DESC, id DESC";
        return db.list("SELECT " + COLUMNS + " FROM memories WHERE persona_id = ? AND scope = ? ORDER BY " + order,
                MemoryStore::map, personaId, scope);
    }

    public List<Memory> selfTopics(String personaId) {
        return list(personaId, SELF).stream().filter(m -> SELF_TOPICS.contains(m.label()))
                .sorted(Comparator.comparingInt(m -> SELF_TOPICS.indexOf(m.label()))).toList();
    }

    public List<Memory> looseSelf(String personaId) {
        return list(personaId, SELF).stream().filter(m -> !SELF_TOPICS.contains(m.label())).toList();
    }

    public void writeSelf(String personaId, String topic, String content, String source) {
        if (!SELF_TOPICS.contains(topic)) {
            throw new IllegalArgumentException("没有这个主题：" + topic);
        }
        upsert(personaId, SELF, topic, clean(content, MAX_TOPIC, true), source);
    }

    public Memory diary(String personaId, String day) {
        return db.one("SELECT " + COLUMNS + " FROM memories WHERE persona_id = ? AND scope = ? AND label = ? ORDER BY id LIMIT 1",
                MemoryStore::map, personaId, DIARY, day);
    }

    public void writeDiary(String personaId, String day, String content, String source) {
        if (!DAY_LABEL.matcher(day).matches()) {
            throw new IllegalArgumentException("日期不对：" + day);
        }
        upsert(personaId, DIARY, day, clean(content, MAX_DIARY, true), source);
    }

    public void appendDiary(String personaId, long at, String content, String source) {
        String day = dayLabel(at);
        Memory old = diary(personaId, day);
        String text = clean(content, MAX_DIARY, true);
        writeDiary(personaId, day, old == null ? text : old.content() + "\n" + text, source);
    }

    public List<String> scatteredDays(String personaId) {
        return db.list("""
                SELECT label FROM memories WHERE persona_id = ? AND scope = ? GROUP BY label HAVING COUNT(*) > 1 OR label NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                """, rs -> rs.getString(1), personaId, DIARY);
    }

    public void relabelDiaries(String personaId) {
        for (Memory m : list(personaId, DIARY)) {
            if (!DAY_LABEL.matcher(m.label()).matches()) {
                db.update("UPDATE memories SET label = ? WHERE id = ?", dayLabel(m.createdAt()), m.id());
            }
        }
    }

    public List<Memory> diaryPieces(String personaId, String day) {
        return db.list("SELECT " + COLUMNS + " FROM memories WHERE persona_id = ? AND scope = ? AND label = ? ORDER BY created_at, id",
                MemoryStore::map, personaId, DIARY, day);
    }

    public void replaceDiary(String personaId, String day, String content, List<Memory> pieces) {
        long created = pieces.isEmpty() ? System.currentTimeMillis() : pieces.getFirst().createdAt();
        String text = clean(untail(content), MAX_DIARY, true);
        db.transaction(c -> {
            for (Memory m : pieces) {
                Database.update(c, "DELETE FROM memories WHERE persona_id = ? AND id = ?", personaId, m.id());
            }
            Database.update(c, "INSERT INTO memories (persona_id, scope, label, content, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    personaId, DIARY, day, text, "", created, System.currentTimeMillis());
            return null;
        });
    }

    private void upsert(String personaId, String scope, String label, String content, String source) {
        String text = untail(content);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        long now = System.currentTimeMillis();
        db.transaction(c -> {
            int changed = Database.update(c, "UPDATE memories SET content = ?, source = ?, updated_at = ? WHERE persona_id = ? AND scope = ? AND label = ?",
                    text, nonNull(source), now, personaId, scope, label);
            if (changed == 0) {
                Database.update(c, "INSERT INTO memories (persona_id, scope, label, content, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        personaId, scope, label, text, nonNull(source), now, now);
            }
            return null;
        });
        prune(personaId, scope);
    }

    List<Memory> recent(String personaId, String scope, int limit) {
        return db.list("SELECT " + COLUMNS + " FROM memories WHERE persona_id = ? AND scope = ? ORDER BY updated_at DESC, id DESC LIMIT ?",
                MemoryStore::map, personaId, scope, limit);
    }

    public Memory get(String personaId, long id) {
        return db.one("SELECT " + COLUMNS + " FROM memories WHERE persona_id = ? AND id = ?", MemoryStore::map, personaId, id);
    }

    public Memory add(String personaId, String scope, String label, String content, String source) {
        if (!validScope(scope)) {
            throw new IllegalArgumentException("记忆分区不对：" + scope);
        }
        String text = clean(untail(content));
        if (text.isEmpty()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        long now = System.currentTimeMillis();
        if (scope.equals(DIARY)) {
            appendDiary(personaId, now, content, source);
            return diary(personaId, dayLabel(now));
        }
        if (scope.equals(KNOWLEDGE)) {
            return learn(personaId, content, source);
        }
        Matcher tagged = scope.equals(STYLE) && nonNull(label).isBlank() ? STYLE_TAG.matcher(text) : null;
        boolean split = tagged != null && tagged.matches();
        String body = split ? tagged.group(2).strip() : text;
        String name = abbreviate(QqMessages.unforgeable(split ? tagged.group(1) : nonNull(label)).replaceAll("\\s+", " ").strip(), 60);
        long id = db.transaction(c -> {
            Database.update(c, "INSERT INTO memories (persona_id, scope, label, content, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    personaId, scope, name, body, nonNull(source), now, now);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        });
        prune(personaId, scope);
        return get(personaId, id);
    }

    public Memory learn(String personaId, String note, String source) {
        Matcher m = TERM.matcher(nonNull(note));
        if (!m.matches()) {
            throw new IllegalArgumentException("知识要写成「词」：意思");
        }
        String term = abbreviate(QqMessages.unforgeable(m.group(1)).replaceAll("\\s+", " ").strip(), 30);
        String meaning = clean(untail(m.group(2)));
        if (term.isEmpty() || meaning.isEmpty()) {
            throw new IllegalArgumentException("知识要写成「词」：意思");
        }
        long now = System.currentTimeMillis();
        Memory same = db.one("SELECT " + COLUMNS + " FROM memories WHERE persona_id = ? AND scope = ? AND label = ? COLLATE NOCASE",
                MemoryStore::map, personaId, KNOWLEDGE, term);
        if (same != null) {
            db.update("UPDATE memories SET content = ?, source = ?, updated_at = ? WHERE id = ?", meaning, nonNull(source), now, same.id());
            return get(personaId, same.id());
        }
        long id = db.transaction(c -> {
            Database.update(c, "INSERT INTO memories (persona_id, scope, label, content, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    personaId, KNOWLEDGE, term, meaning, nonNull(source), now, now);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        });
        prune(personaId, KNOWLEDGE);
        return get(personaId, id);
    }

    public String knowledge(String personaId, String context) {
        if (context == null || context.isBlank()) {
            return "";
        }
        String text = context.toLowerCase(Locale.ROOT);
        List<Memory> hits = list(personaId, KNOWLEDGE).stream()
                .filter(m -> m.label().length() >= 2 && text.contains(m.label().toLowerCase(Locale.ROOT)))
                .limit(MAX_RELEVANT_KNOWLEDGE).toList();
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<你查过的>\n");
        hits.forEach(m -> out.append("- 「").append(m.label()).append("」：").append(m.content()).append("\n"));
        return out.append("</你查过的>\n\n").toString();
    }

    public String style(String personaId, String session, String context) {
        List<Memory> all = list(personaId, STYLE);
        StringBuilder out = new StringBuilder();
        if (!all.isEmpty()) {
            Set<String> words = context == null || context.isBlank() ? Set.of() : Texts.bigrams(context);
            List<Memory> shown = new ArrayList<>(all.stream().filter(m -> m.source().equals(session)).limit(STYLE_HERE).toList());
            all.stream().filter(m -> !shown.contains(m))
                    .sorted(Comparator.comparingDouble(m -> -Texts.overlap(Texts.bigrams(m.label() + m.content()), words)))
                    .limit(STYLE_SHOWN - shown.size()).forEach(shown::add);
            shown.forEach(m -> out.append("- ").append(m.label().isBlank() ? "" : "【" + m.label() + "】").append(m.content()).append('\n'));
        }
        int[] voice = session.startsWith("group:") ? voice(session.substring(6)) : null;
        if (voice != null) {
            out.append("这个群大家一句话多半在 ").append(voice[0]).append(" 个字左右")
                    .append(voice[1] > 0 ? "，你最近一句平均 " + voice[1] + " 个字" : "").append("。\n");
        }
        return out.isEmpty() ? "" : "<你学到的说话方式>\n" + out + "</你学到的说话方式>\n\n";
    }

    private int[] voice(String groupId) {
        long now = System.currentTimeMillis();
        Voice cached = voices.get(groupId);
        if (cached != null && now - cached.at() < VOICE_TTL_MS) {
            return cached.lengths();
        }
        List<Integer> human = new ArrayList<>();
        List<Integer> mine = new ArrayList<>();
        for (String[] row : db.list("SELECT direction, content FROM messages WHERE message_type = 'group' AND group_id = ? ORDER BY id DESC LIMIT 400",
                rs -> new String[] {rs.getString(1), rs.getString(2)}, groupId)) {
            int n = spoken(row[1]);
            if (n > 0) {
                ("out".equals(row[0]) ? mine : human).add(n);
            }
        }
        int[] lengths = human.size() < 30 ? null : new int[] {median(human), mine.size() < 10 ? 0 : median(mine)};
        voices.put(groupId, new Voice(now, lengths));
        return lengths;
    }

    static int spoken(String raw) {
        String text = QqMessages.plain(raw).replaceAll("\\[[^\\]]{0,40}]", "").replaceAll("@\\S+\\s?", "").strip();
        return text.codePointCount(0, text.length());
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    public List<Line> chatter(String groupId, int limit) {
        List<Line> lines = new ArrayList<>(db.list("""
                SELECT id, direction, user_id, sender_name, content FROM messages
                WHERE message_type = 'group' AND group_id = ? ORDER BY id DESC LIMIT ?
                """, rs -> new Line(rs.getLong(1), "out".equals(rs.getString(2)), nonNull(rs.getString(3)),
                QqMessages.unforgeable(nonNull(rs.getString(4))), QqMessages.plain(rs.getString(5))), groupId, limit));
        return lines.reversed();
    }

    public long studiedUpTo(String groupId) {
        try {
            return Long.parseLong(nonNull(settings.get(STUDIED_KEY + groupId)));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void studied(String groupId, long messageId) {
        settings.put(STUDIED_KEY + groupId, String.valueOf(messageId));
    }

    public int heardSince(String groupId, long afterId) {
        Integer n = db.one("SELECT COUNT(*) FROM messages WHERE message_type = 'group' AND group_id = ? AND direction = 'in' AND id > ?",
                rs -> rs.getInt(1), groupId, afterId);
        return n == null ? 0 : n;
    }

    public boolean knows(String personaId, String scope, String content) {
        String text = ContentGuard.normalize(content);
        return recent(personaId, scope, 40).stream().anyMatch(m -> ContentGuard.normalize(m.content()).equals(text));
    }

    public boolean update(String personaId, long id, String content) {
        Memory old = get(personaId, id);
        if (old == null) {
            return false;
        }
        String plain = untail(content);
        String text = old.scope().equals(DIARY) ? clean(plain, MAX_DIARY, true)
                : SELF_TOPICS.contains(old.label()) && old.scope().equals(SELF) ? clean(plain, MAX_TOPIC, true) : clean(plain);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        return db.update("UPDATE memories SET content = ?, updated_at = ? WHERE persona_id = ? AND id = ?",
                text, System.currentTimeMillis(), personaId, id) > 0;
    }

    public boolean delete(String personaId, long id) {
        return db.update("DELETE FROM memories WHERE persona_id = ? AND id = ?", personaId, id) > 0;
    }

    public static boolean visible(Memory m, String session, Set<String> owners) {
        String source = m.source();
        return m.scope().equals(KNOWLEDGE) || source.isEmpty() || source.equals("manual") || source.startsWith("group:") || source.startsWith("mind:") || source.equals(session)
                || owners.stream().anyMatch(o -> session.equals("private:" + o));
    }

    List<Memory> recall(String personaId, String scope, String session, Set<String> owners, int limit) {
        return recent(personaId, scope, Math.max(limit, 40)).stream().filter(m -> visible(m, session, owners)).limit(limit).toList();
    }

    public String lookup(String personaId, String scope, String session, Set<String> owners, String query, int offset) {
        List<Memory> all = visibleIn(personaId, scope, session, owners);
        if (query != null && !query.isBlank()) {
            String q = query.strip().toLowerCase(Locale.ROOT);
            Set<String> words = dafeiyu.util.Texts.bigrams(q);
            all = all.stream()
                    .filter(m -> (m.label() + m.content()).toLowerCase(Locale.ROOT).contains(q)
                            || dafeiyu.util.Texts.overlap(words, dafeiyu.util.Texts.bigrams(m.label() + m.content())) >= 0.5)
                    .toList();
        }
        if (all.isEmpty()) {
            return query == null || query.isBlank() ? "（关于这个你还什么都没记）" : "（没有记着跟「" + query.strip() + "」有关的）";
        }
        int from = Math.max(0, Math.min(offset, all.size()));
        List<String> lines = new ArrayList<>();
        int used = 0;
        int i = from;
        for (; i < all.size(); i++) {
            String line = "- " + line(all.get(i)) + "（" + day(all.get(i).updatedAt()) + "）";
            if (!lines.isEmpty() && used + line.length() > LOOKUP_BUDGET) {
                break;
            }
            lines.add(line);
            used += line.length();
        }
        String head = "一共 " + all.size() + " 条" + (from > 0 ? "，从第 " + (from + 1) + " 条开始" : "") + "：\n";
        return head + String.join("\n", lines) + (i < all.size() ? "\n（还有 " + (all.size() - i) + " 条，offset 填 " + i + " 接着看）" : "");
    }

    public List<String> scopesNamed(String personaId, String name) {
        String n = name.strip();
        List<String> exact = db.list("SELECT DISTINCT scope FROM memories WHERE persona_id = ? AND scope LIKE 'user:%' AND label = ?",
                rs -> rs.getString(1), personaId, n);
        return !exact.isEmpty() ? exact : db.list(
                "SELECT DISTINCT scope FROM memories WHERE persona_id = ? AND scope LIKE 'user:%' AND label LIKE ? ORDER BY updated_at DESC LIMIT 5",
                rs -> rs.getString(1), personaId, "%" + n + "%");
    }

    public List<String> aliases(String groupId, String qq, String current) {
        return db.list("""
                SELECT sender_name FROM messages WHERE direction = 'in' AND message_type = 'group' AND group_id = ? AND user_id = ? AND sender_name <> ''
                GROUP BY sender_name ORDER BY MAX(id) DESC LIMIT 4
                """, rs -> QqMessages.unforgeable(nonNull(rs.getString(1))), groupId, qq).stream()
                .filter(n -> !n.equals(current) && !QqMessages.displayName(n).equals(QqMessages.displayName(current))).limit(3).toList();
    }

    private List<Memory> visibleIn(String personaId, String scope, String session, Set<String> owners) {
        return list(personaId, scope).stream().filter(m -> visible(m, session, owners)).toList();
    }

    private static List<Memory> pick(List<Memory> all, Set<String> words, int budget) {
        List<Memory> ranked = new ArrayList<>(all);
        java.util.Map<Long, Double> score = new java.util.HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            Memory m = all.get(i);
            double relevance = words.isEmpty() ? 0 : dafeiyu.util.Texts.overlap(dafeiyu.util.Texts.bigrams(m.label() + m.content()), words);
            score.put(m.id(), relevance * 3 + 1.0 / (1 + i * 0.3));
        }
        ranked.sort(Comparator.comparingDouble((Memory m) -> score.get(m.id())).reversed());
        List<Memory> out = new ArrayList<>();
        int used = 0;
        for (Memory m : ranked) {
            if (!out.isEmpty() && used + m.content().length() > budget) {
                continue;
            }
            out.add(m);
            used += m.content().length();
        }
        out.sort(Comparator.comparingLong(Memory::updatedAt).reversed());
        return out;
    }

    private static String counted(String title, int shown, int total) {
        return total > shown ? title + "（你一共记了 " + total + " 条，这里是跟现在相关的 " + shown + " 条，想知道更多用 recall 查）" : title;
    }

    public String recall(String personaId, String session, List<String> people, Set<String> owners) {
        return recall(personaId, session, people, owners, "");
    }

    public String recall(String personaId, String session, List<String> people, Set<String> owners, String context) {
        Set<String> words = context == null || context.isBlank() ? Set.of() : dafeiyu.util.Texts.bigrams(context);
        StringBuilder out = new StringBuilder();
        List<Memory> topics = selfTopics(personaId);
        if (!topics.isEmpty()) {
            out.append("关于你自己：\n");
            topics.forEach(m -> out.append("- ").append(m.label()).append("：").append(m.content().replace("\n", " ")).append('\n'));
        }
        List<Memory> loose = visibleIn(personaId, SELF, session, owners).stream().filter(m -> !SELF_TOPICS.contains(m.label())).toList();
        List<Memory> self = pick(loose, words, SELF_BUDGET);
        section(out, counted(topics.isEmpty() ? "关于你自己" : "关于你自己（最近新想到的）", self.size(), loose.size()), self);
        if (session.startsWith("group:")) {
            List<Memory> all = visibleIn(personaId, session, session, owners);
            List<Memory> group = pick(all, words, GROUP_BUDGET);
            section(out, counted("关于这个群", group.size(), all.size()), group);
        }
        for (String qq : people.stream().distinct().limit(8).toList()) {
            List<Memory> all = visibleIn(personaId, "user:" + qq, session, owners);
            if (!all.isEmpty()) {
                List<Memory> about = pick(all, words, PERSON_BUDGET);
                String name = all.stream().map(Memory::label).filter(l -> !l.isBlank()).findFirst().orElse("QQ " + qq);
                section(out, counted("关于" + name + "（QQ " + qq + (owners.contains(qq) ? "，你的主人" : "") + "）", about.size(), all.size()), about);
            }
        }
        List<Memory> diary = list(personaId, DIARY).stream().filter(m -> visible(m, session, owners)).limit(2).toList();
        if (!diary.isEmpty()) {
            out.append("你最近的日记：\n");
            for (Memory m : diary.reversed()) {
                out.append("【").append(fullDay(m.label())).append("】\n").append(m.content()).append('\n');
            }
        }
        lately(out, session, people);
        return out.isEmpty() ? "" : "<你记得>\n" + out + "</你记得>\n\n";
    }

    private record Seen(long time, String who, String text) {}

    private void lately(StringBuilder out, String session, List<String> people) {
        boolean group = session.startsWith("group:");
        String here = group ? session.substring(6) : "";
        long now = System.currentTimeMillis();
        List<Seen> seen = new ArrayList<>();
        for (String qq : people.stream().distinct().limit(4).toList()) {
            seen.addAll(db.list("""
                    SELECT time, sender_name, content FROM messages
                    WHERE direction = 'in' AND message_type = 'group' AND user_id = ? AND group_id <> ? AND time >= ?
                    ORDER BY id DESC LIMIT ?
                    """, rs -> new Seen(rs.getLong(1), QqMessages.unforgeable(nonNull(rs.getString(2))), rs.getString(3)),
                    qq, here, now - LATELY_MS, group ? 2 : 3));
        }
        if (!group && !people.isEmpty()) {
            String qq = people.getFirst();
            String groupId = db.one("""
                    SELECT group_id FROM messages WHERE direction = 'in' AND message_type = 'group' AND user_id = ? AND time >= ? ORDER BY id DESC LIMIT 1
                    """, rs -> rs.getString(1), qq, now - THREAD_MS);
            if (groupId != null) {
                seen.removeIf(s -> !s.who().isEmpty());
                seen.addAll(db.list("""
                        SELECT time, CASE WHEN direction = 'out' THEN '' ELSE sender_name END, content FROM messages
                        WHERE message_type = 'group' AND group_id = ? AND time >= ? AND (direction = 'out' OR user_id = ?)
                        ORDER BY id DESC LIMIT 10
                        """, rs -> new Seen(rs.getLong(1), QqMessages.unforgeable(nonNull(rs.getString(2))), rs.getString(3)), groupId, now - THREAD_MS, qq));
            }
        }
        List<String> lines = seen.stream().sorted(Comparator.comparingLong(Seen::time))
                .map(s -> new Seen(s.time(), s.who(), abbreviate(QqMessages.plain(s.text()), 80)))
                .filter(s -> !s.text().isBlank() && guard.scan(s.text()) == null)
                .map(s -> "- " + minutes(now - s.time()) + "，在" + (group ? "另一个群" : "群") + "里，"
                        + (s.who().isEmpty() ? "你说" : s.who() + "说") + "：" + s.text())
                .toList();
        if (!lines.isEmpty()) {
            out.append(group ? "最近在别处（只是背景，别主动拿到这里说）：\n"
                    : "你们最近在群里的来回（这件事可能群里的你正在办，别在这里重复做一遍，接着那边的进度说）：\n")
                    .append(String.join("\n", lines)).append('\n');
        }
    }

    private static String minutes(long ms) {
        long m = ms / 60_000;
        return m < 1 ? "刚刚" : m < 60 ? m + " 分钟前" : m / 60 + " 小时前";
    }

    long checkpoint(String session) {
        Long id = db.one("SELECT last_turn_id FROM memory_checkpoints WHERE session = ?", rs -> rs.getLong(1), session);
        return id == null ? 0 : id;
    }

    void setCheckpoint(String session, long lastTurnId) {
        db.update("""
                INSERT INTO memory_checkpoints (session, last_turn_id, reflected_at) VALUES (?, ?, ?)
                ON CONFLICT (session) DO UPDATE SET last_turn_id = MAX(last_turn_id, excluded.last_turn_id), reflected_at = excluded.reflected_at
                """, session, lastTurnId, System.currentTimeMillis());
    }

    void forget(String session) {
        db.update("DELETE FROM memory_checkpoints WHERE session = ?", session);
    }

    List<Turn> unreflected(String session) {
        return db.list("""
                SELECT id, role, content, created_at, tool_calls, tool_name, tool_error FROM conversation_turns
                WHERE session = ? AND id > ? AND (content <> '' OR tool_calls <> '') ORDER BY id
                """, rs -> new Turn(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getInt(7) != 0), session, checkpoint(session));
    }

    public int pending(String session) {
        Integer n = db.one("SELECT COUNT(*) FROM conversation_turns WHERE session = ? AND id > ? AND role <> 'tool' AND content <> ''",
                rs -> rs.getInt(1), session, checkpoint(session));
        return n == null ? 0 : n;
    }

    long lastTurnId(String session) {
        Long id = db.one("SELECT MAX(id) FROM conversation_turns WHERE session = ?", rs -> rs.getLong(1), session);
        return id == null ? 0 : id;
    }

    public List<String> pendingSessions() {
        return db.list("""
                SELECT t.session FROM conversation_turns t LEFT JOIN memory_checkpoints m ON m.session = t.session
                WHERE (t.session LIKE 'group:%' OR t.session LIKE 'private:%') AND t.role <> 'tool' AND t.content <> ''
                  AND t.id > COALESCE(m.last_turn_id, 0)
                GROUP BY t.session ORDER BY MAX(t.id) DESC
                """, rs -> rs.getString(1));
    }

    List<String[]> roster(String session, long since) {
        if (session.startsWith("group:")) {
            return db.list("""
                    SELECT user_id, sender_name FROM messages WHERE id IN (
                        SELECT MAX(id) FROM messages WHERE direction = 'in' AND message_type = 'group' AND group_id = ? AND time >= ?
                        GROUP BY user_id) ORDER BY id DESC LIMIT 40
                    """, rs -> new String[] {rs.getString(1), QqMessages.unforgeable(nonNull(rs.getString(2)))}, session.substring(6), since);
        }
        String qq = session.substring(session.indexOf(':') + 1);
        String name = db.one("SELECT sender_name FROM messages WHERE direction = 'in' AND message_type = 'private' AND user_id = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.getString(1), qq);
        return List.<String[]>of(new String[] {qq, QqMessages.unforgeable(nonNull(name))});
    }

    static String day(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DAY);
    }

    public static String dayLabel(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    public static String fullDay(String label) {
        try {
            return LocalDate.parse(label).format(FULL_DAY);
        } catch (DateTimeParseException e) {
            return label;
        }
    }

    public static String line(Memory m) {
        if (m.scope().equals(DIARY)) {
            return "【" + fullDay(m.label()) + "】" + m.content().replace("\n", " ");
        }
        if (m.scope().equals(KNOWLEDGE)) {
            return "「" + m.label() + "」：" + m.content().replace("\n", " ");
        }
        return (m.scope().equals(SELF) && SELF_TOPICS.contains(m.label()) ? m.label() + "：" : "") + m.content().replace("\n", " ");
    }

    private void prune(String personaId, String scope) {
        int keep = scope.equals(DIARY) ? 400 : scope.equals(SELF) ? 60 : scope.equals(KNOWLEDGE) ? MAX_KNOWLEDGE : scope.equals(STYLE) ? MAX_STYLE : 200;
        db.update("""
                DELETE FROM memories WHERE persona_id = ? AND scope = ? AND id NOT IN (
                    SELECT id FROM memories WHERE persona_id = ? AND scope = ? ORDER BY updated_at DESC, id DESC LIMIT ?)
                """, personaId, scope, personaId, scope, keep);
    }

    private static void section(StringBuilder out, String title, List<Memory> memories) {
        if (memories.isEmpty()) {
            return;
        }
        out.append(title).append("：\n");
        for (Memory m : memories) {
            out.append("- ").append(m.content()).append("（").append(day(m.updatedAt())).append("）\n");
        }
    }

    public String untail(String text) {
        Matcher m = TAIL.matcher(nonNull(text));
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            last = m.end();
            String name = tailName(m.group(1));
            if (name == null || name.isBlank()) {
                continue;
            }
            if (out.toString().endsWith(name)) {
                out.setLength(out.length() - name.length());
            }
            out.append(out.toString().endsWith(PEER) ? "" : PEER).append(name);
        }
        return out.append(text, last, text.length()).toString();
    }

    public int untailAll(String personaId) {
        int changed = 0;
        for (Memory m : db.list("SELECT " + COLUMNS + " FROM memories WHERE persona_id = ? AND content GLOB '*#[0-9][0-9][0-9][0-9]*'",
                MemoryStore::map, personaId)) {
            String text = untail(m.content());
            if (!text.equals(m.content())) {
                changed += db.update("UPDATE memories SET content = ? WHERE id = ?", text, m.id());
            }
        }
        return changed;
    }

    private String tailName(String digits) {
        List<String[]> people = db.list("""
                SELECT user_id, sender_name FROM messages WHERE id IN (
                    SELECT MAX(id) FROM messages WHERE direction = 'in' AND user_id LIKE ? GROUP BY user_id) LIMIT 2
                """, rs -> new String[] {rs.getString(1), rs.getString(2)}, "%" + digits);
        return people.size() == 1 ? QqMessages.unforgeable(nonNull(people.getFirst()[1])).strip() : null;
    }

    private static String clean(String content) {
        return clean(content, MAX_CONTENT, false);
    }

    private static String clean(String content, int max, boolean lines) {
        String text = nonNull(content).replace("\r\n", "\n");
        text = lines ? text.replaceAll("[ \\t\\x0B\\f]+", " ").replaceAll(" *\n[ \n]*", "\n") : text.replaceAll("\\s+", " ");
        return abbreviate(text.strip(), max);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static Memory map(ResultSet rs) throws SQLException {
        return new Memory(rs.getLong("id"), rs.getString("scope"), rs.getString("label"), rs.getString("content"),
                rs.getString("source"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
