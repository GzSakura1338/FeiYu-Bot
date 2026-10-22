package dafeiyu.agent.memory;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;
import dafeiyu.util.Clock;

import static dafeiyu.util.Texts.abbreviate;

public final class Mood {

    public record State(String text, long at, String session) {}

    private static final String KEY = "agent.mood.";
    private static final int MAX_CHARS = 120;
    private static final long FORGET_AFTER_MS = 24 * 3_600_000L;
    private static final long ELSEWHERE_MS = 30 * 60_000L;
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
        boolean here = state != null && session.equals(state.session());
        String feeling;
        if (state == null || (!here && System.currentTimeMillis() - state.at() > ELSEWHERE_MS)) {
            feeling = "平常";
        } else if (here) {
            feeling = state.text() + "（" + Clock.ago(state.at()) + "，在这个聊天里）";
        } else {
            feeling = "你刚才在想别的事：" + state.text() + "（" + Clock.ago(state.at()) + "，那是别处的事，只影响你此刻的情绪，别把它当成这里的话题说出来）";
        }
        String spoke = lastSpoke > 0 ? "\n你上次在这个聊天里说话：" + Clock.ago(lastSpoke) : "";
        return "<你此刻>\n现在是 " + Clock.now() + spoke + "\n你的心情和想法：" + feeling + "\n</你此刻>\n\n";
    }
}
