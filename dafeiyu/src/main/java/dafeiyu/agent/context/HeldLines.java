package dafeiyu.agent.context;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HeldLines {

    public record Line(String line, String userId, String attachments) {}

    private static final String KEY = "agent.held";
    private static final Gson GSON = new Gson();

    private final Settings settings;
    private final Map<String, List<Line>> held = new LinkedHashMap<>();

    public HeldLines(Database db) {
        this.settings = new Settings(db);
        load();
    }

    public synchronized void add(String session, Line line) {
        held.computeIfAbsent(session, k -> new ArrayList<>()).add(line);
        save();
    }

    public synchronized List<Line> take(String session) {
        List<Line> lines = held.remove(session);
        if (lines == null) {
            return List.of();
        }
        save();
        return lines;
    }

    public synchronized Map<String, List<Line>> takeAll() {
        Map<String, List<Line>> all = new LinkedHashMap<>(held);
        if (!all.isEmpty()) {
            held.clear();
            save();
        }
        return all;
    }

    private void load() {
        String raw = settings.get(KEY);
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Map<String, List<Line>> saved = GSON.fromJson(raw, new TypeToken<Map<String, List<Line>>>() {}.getType());
            if (saved != null) {
                saved.forEach((session, lines) -> held.put(session, new ArrayList<>(lines)));
            }
        } catch (JsonParseException ignored) {
        }
    }

    private void save() {
        settings.put(KEY, GSON.toJson(held));
    }
}
