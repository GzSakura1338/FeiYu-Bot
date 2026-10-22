package dafeiyu.agent.memory;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;
import dafeiyu.util.Clock;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dafeiyu.util.Texts.abbreviate;

public final class Mood {

    public record State(String text, long at, String session) {}

    public record Note(String about, String text) {}

    public record Split(String thought, String said, List<Note> notes) {

        public Split(String thought, String said) {
            this(thought, said, List.of());
        }
    }

    public static String stored(String thought, String said, List<Note> notes) {
        StringBuilder out = new StringBuilder(thought.isEmpty() ? "" : OPEN + thought + CLOSE).append(said);
        for (Note note : notes) {
            out.append("<记住").append(note.about().isEmpty() ? "" : " 关于=\"" + note.about() + "\"").append('>')
                    .append(note.text()).append("</记住>");
        }
        return out.toString();
    }

    public static final String OPEN = "<内心>";
    public static final String CLOSE = "</内心>";
    private static final Pattern NOTE = Pattern.compile("<记住(?:\\s+关于\\s*=\\s*[\"'“”]?([^\"'“”>]*)[\"'“”]?)?\\s*>(.*?)(?:</记住>|$)", Pattern.DOTALL);
    private static final Pattern ERRAND = Pattern.compile("<去做>.*?(?:</去做>|$)|<拍一拍\\s*/>|<拍一拍>.*?(?:</拍一拍>|$)", Pattern.DOTALL);
    private static final String KEY = "agent.mood.";
    private static final int MAX_CHARS = 120;
    private static final long FORGET_AFTER_MS = 24 * 3_600_000L;
    private static final Gson GSON = new Gson();

    private final Settings settings;

    public Mood(Database db) {
        this.settings = new Settings(db);
    }

    public State current(String personaId) {
        String raw = personaId == null ? null : settings.get(KEY + personaId);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            State state = GSON.fromJson(raw, State.class);
            return state == null || state.text() == null || System.currentTimeMillis() - state.at() > FORGET_AFTER_MS ? null : state;
        } catch (JsonParseException e) {
            return null;
        }
    }

    public void remember(String personaId, String thought, String session) {
        settings.put(KEY + personaId, GSON.toJson(new State(abbreviate(thought.replace('\n', ' '), MAX_CHARS), System.currentTimeMillis(), session)));
    }

    public String note(String personaId, String session, long lastSpoke) {
        State state = current(personaId);
        String feeling = state == null ? "平常"
                : state.text() + "（" + Clock.ago(state.at()) + "，" + (session.equals(state.session()) ? "在这个聊天里" : "在别的聊天里，那边的具体内容别在这里提") + "）";
        String spoke = lastSpoke > 0 ? "\n你上次在这个聊天里说话：" + Clock.ago(lastSpoke) : "";
        return "<你此刻>\n现在是 " + Clock.now() + spoke + "\n你的心情和想法：" + feeling + "\n</你此刻>\n\n";
    }

    public static Split split(String content) {
        List<Note> notes = new ArrayList<>();
        Matcher m = NOTE.matcher(ERRAND.matcher(content).replaceAll(""));
        StringBuilder rest = new StringBuilder();
        while (m.find()) {
            String text = m.group(2).replace("</记住>", "").strip();
            if (!text.isEmpty()) {
                notes.add(new Note(m.group(1) == null ? "" : m.group(1).strip(), text));
            }
            m.appendReplacement(rest, "");
        }
        m.appendTail(rest);
        Split inner = thought(rest.toString().replace("</记住>", ""));
        return new Split(inner.thought(), inner.said(), List.copyOf(notes));
    }

    private static Split thought(String content) {
        int open = content.indexOf(OPEN);
        if (open < 0) {
            return new Split("", content.replace(CLOSE, "").strip());
        }
        int close = content.indexOf(CLOSE, open);
        int end;
        int resume;
        if (close >= 0) {
            end = close;
            resume = close + CLOSE.length();
        } else {
            int newline = content.indexOf('\n', open);
            end = newline < 0 ? content.length() : newline;
            resume = end;
        }
        String thought = content.substring(open + OPEN.length(), end).strip();
        String said = (content.substring(0, open) + content.substring(resume)).replace(OPEN, "").replace(CLOSE, "").strip();
        return new Split(thought, said);
    }
}
