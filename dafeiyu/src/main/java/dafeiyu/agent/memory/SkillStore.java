package dafeiyu.agent.memory;

import dafeiyu.db.Database;
import dafeiyu.util.Texts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static dafeiyu.util.Texts.abbreviate;

public final class SkillStore {

    public record Skill(long id, String name, String description, String content, String source, long createdAt, long updatedAt) {

        public String markdown() {
            return "---\nname: " + name + "\ndescription: " + description.replace("\n", " ") + "\n---\n\n" + content.strip() + "\n";
        }
    }

    public static final int MAX_NAME = 40;
    public static final int MAX_DESCRIPTION = 200;
    public static final int MAX_CONTENT = 3000;
    private static final int MAX_SKILLS = 200;
    private static final int PROMPT_BUDGET = 24_000;
    private static final double RELEVANT = 0.3;
    private static final int MAX_RELEVANT = 3;
    private static final String COLUMNS = "id, name, description, content, source, created_at, updated_at";

    private final Database db;

    public SkillStore(Database db) {
        this.db = db;
    }

    public List<Skill> list(String personaId) {
        return db.list("SELECT " + COLUMNS + " FROM skills WHERE persona_id = ? ORDER BY updated_at DESC, id DESC", SkillStore::map, personaId);
    }

    public int count(String personaId) {
        Integer n = db.one("SELECT COUNT(*) FROM skills WHERE persona_id = ?", rs -> rs.getInt(1), personaId);
        return n == null ? 0 : n;
    }

    public long latest(String personaId) {
        Long at = db.one("SELECT MAX(updated_at) FROM skills WHERE persona_id = ?", rs -> rs.getLong(1), personaId);
        return at == null ? 0 : at;
    }

    public Skill get(String personaId, long id) {
        return db.one("SELECT " + COLUMNS + " FROM skills WHERE persona_id = ? AND id = ?", SkillStore::map, personaId, id);
    }

    public Skill named(String personaId, String name) {
        return db.one("SELECT " + COLUMNS + " FROM skills WHERE persona_id = ? AND name = ? COLLATE NOCASE", SkillStore::map, personaId, name(name));
    }

    public Skill add(String personaId, String name, String description, String content, String source) {
        String title = name(name);
        String body = content(content);
        if (title.isEmpty() || body.isEmpty()) {
            throw new IllegalArgumentException("技能要有名字和内容");
        }
        Skill same = named(personaId, title);
        if (same != null) {
            update(personaId, same.id(), title, description, body);
            return get(personaId, same.id());
        }
        long now = System.currentTimeMillis();
        long id = db.transaction(c -> {
            Database.update(c, "INSERT INTO skills (persona_id, name, description, content, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    personaId, title, description(description), body, source == null ? "" : source, now, now);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        });
        db.update("""
                DELETE FROM skills WHERE persona_id = ? AND id NOT IN (
                    SELECT id FROM skills WHERE persona_id = ? ORDER BY updated_at DESC, id DESC LIMIT ?)
                """, personaId, personaId, MAX_SKILLS);
        return get(personaId, id);
    }

    public boolean update(String personaId, long id, String name, String description, String content) {
        String title = name(name);
        String body = content(content);
        if (title.isEmpty() || body.isEmpty()) {
            throw new IllegalArgumentException("技能要有名字和内容");
        }
        Skill same = named(personaId, title);
        if (same != null && same.id() != id) {
            throw new IllegalArgumentException("已经有一个叫「" + title + "」的技能了");
        }
        return db.update("UPDATE skills SET name = ?, description = ?, content = ?, updated_at = ? WHERE persona_id = ? AND id = ?",
                title, description(description), body, System.currentTimeMillis(), personaId, id) > 0;
    }

    public boolean delete(String personaId, long id) {
        return db.update("DELETE FROM skills WHERE persona_id = ? AND id = ?", personaId, id) > 0;
    }

    private record Rendered(String text, List<Skill> brief) {}

    public String prompt(String personaId) {
        return render(list(personaId)).text();
    }

    public String relevant(String personaId, String context) {
        List<Skill> brief = render(list(personaId)).brief();
        if (brief.isEmpty() || context.isBlank()) {
            return "";
        }
        Set<String> words = Texts.bigrams(context);
        List<Skill> picked = brief.stream()
                .filter(s -> score(s, words) >= RELEVANT)
                .sorted(Comparator.comparingDouble((Skill s) -> score(s, words)).reversed())
                .limit(MAX_RELEVANT).toList();
        if (picked.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<用得上的技能>\n");
        picked.forEach(s -> out.append(full(s).strip()).append("\n\n"));
        return out.toString().strip() + "\n</用得上的技能>\n\n";
    }

    private static Rendered render(List<Skill> skills) {
        if (skills.isEmpty()) {
            return new Rendered("", List.of());
        }
        StringBuilder out = new StringBuilder("【你学会的技能】\n这些是你以前在聊天里学会、总结下来的本事。碰到对应的事就照着做，别再从头摸索；做法过时了也没关系，回顾时会更新。"
                + "只写了用途的技能，碰到对应的事时详细做法会放在“<用得上的技能>”里给你。\n");
        List<Skill> brief = new ArrayList<>();
        int used = out.length();
        for (Skill s : skills) {
            String full = full(s);
            boolean fits = used + full.length() <= PROMPT_BUDGET;
            String piece = fits ? full
                    : "\n## " + s.name() + "\n" + (s.description().isEmpty() ? abbreviate(s.content().strip(), 80) : "什么时候用：" + s.description()) + "\n";
            if (used + piece.length() > PROMPT_BUDGET + 4_000) {
                break;
            }
            if (!fits) {
                brief.add(s);
            }
            out.append(piece);
            used += piece.length();
        }
        return new Rendered(out.toString().strip(), brief);
    }

    private static double score(Skill s, Set<String> words) {
        return Math.max(Texts.overlap(Texts.bigrams(s.name()), words), Texts.overlap(Texts.bigrams(s.description()), words));
    }

    private static String full(Skill s) {
        return "\n## " + s.name() + "\n" + (s.description().isEmpty() ? "" : "什么时候用：" + s.description() + "\n") + s.content().strip() + "\n";
    }

    public byte[] zip(String personaId) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Set<String> used = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Skill s : list(personaId)) {
                String dir = fileName(s.name());
                for (int i = 2; !used.add(dir); i++) {
                    dir = fileName(s.name()) + "-" + i;
                }
                zip.putNextEntry(new ZipEntry("skills/" + dir + "/SKILL.md"));
                zip.write(s.markdown().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static String fileName(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        return safe.isEmpty() ? "skill" : safe;
    }

    private static String name(String name) {
        return abbreviate(name == null ? "" : name.replaceAll("\\s+", " ").strip(), MAX_NAME);
    }

    private static String description(String description) {
        return abbreviate(description == null ? "" : description.replaceAll("\\s+", " ").strip(), MAX_DESCRIPTION);
    }

    private static String content(String content) {
        return abbreviate(content == null ? "" : content.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").strip(), MAX_CONTENT);
    }

    private static Skill map(ResultSet rs) throws SQLException {
        return new Skill(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getString("content"),
                rs.getString("source"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
