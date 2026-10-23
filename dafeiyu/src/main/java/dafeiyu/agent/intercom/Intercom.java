package dafeiyu.agent.intercom;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static dafeiyu.util.Texts.abbreviate;

public final class Intercom {

    public record Note(long id, String session, String intent, long at) {}

    public record Commitment(long id, String session, String who, String what, String status, String result, long createdAt, long updatedAt) {

        public boolean open() {
            return OPEN.equals(status);
        }
    }

    public record Event(long at, String kind, String session, String text) {}

    public record Told(String session, String intent, long at) {}

    public static final String OPEN = "open";
    public static final String DONE = "done";
    public static final String DROPPED = "dropped";
    private static final String KEY = "agent.commitments";
    private static final String NOTES_KEY = "agent.intercom.notes";
    private static final String INBOX_KEY = "agent.intercom.inbox";
    private static final int MAX_NOTES = 4;
    private static final int MAX_TOLD = 60;
    private static final long TOLD_MS = 30 * 60_000;
    private static final double SAME_INTENT = 0.55;
    private static final int MAX_INBOX = 60;
    private static final int MAX_EVENTS = 80;
    private static final int MAX_COMMITMENTS = 40;
    private static final long KEEP_CLOSED_MS = 3 * 86_400_000L;
    private static final Gson GSON = new Gson();

    private final Settings settings;
    private final Map<String, Deque<Note>> notes = new ConcurrentHashMap<>();
    private final Deque<String> inbox = new ArrayDeque<>();
    private final Deque<Told> told = new ArrayDeque<>();
    private int crowdedOut;
    private final Deque<Event> events = new ArrayDeque<>();
    private final List<Commitment> commitments = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private volatile Consumer<String> deliver = s -> {};
    private volatile Runnable stir = () -> {};

    public Intercom(Database db) {
        this.settings = new Settings(db);
        load();
    }

    public void onNotes(Consumer<String> deliver) {
        this.deliver = deliver;
    }

    public void onNews(Runnable stir) {
        this.stir = stir;
    }

    public Note tell(String session, String intent) {
        Note note = new Note(ids.incrementAndGet(), session, abbreviate(intent.strip(), 300), System.currentTimeMillis());
        Deque<Note> queue = notes.computeIfAbsent(session, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(note);
            while (queue.size() > MAX_NOTES) {
                Note old = queue.pollFirst();
                post("receipt", session, "（「" + old.intent() + "」没来得及说，后来的念头把它挤掉了）", true);
            }
        }
        saveNotes();
        synchronized (told) {
            told.addLast(new Told(session, note.intent(), note.at()));
            while (told.size() > MAX_TOLD) {
                told.pollFirst();
            }
        }
        event("note", session, note.intent());
        deliver.accept(session);
        return note;
    }

    public Told similar(String intent) {
        long now = System.currentTimeMillis();
        synchronized (told) {
            told.removeIf(t -> now - t.at() > TOLD_MS);
            Told best = null;
            double score = SAME_INTENT;
            for (Told t : told) {
                double s = overlap(t.intent(), intent);
                if (s >= score) {
                    best = t;
                    score = s;
                }
            }
            return best;
        }
    }

    public boolean hasNotes(String session) {
        Deque<Note> queue = notes.get(session);
        if (queue == null) {
            return false;
        }
        synchronized (queue) {
            return !queue.isEmpty();
        }
    }

    public List<Note> takeNotes(String session) {
        Deque<Note> queue = notes.get(session);
        if (queue == null) {
            return List.of();
        }
        List<Note> taken;
        synchronized (queue) {
            taken = List.copyOf(queue);
            queue.clear();
        }
        if (!taken.isEmpty()) {
            saveNotes();
        }
        return taken;
    }

    public List<String> sessionsWithNotes() {
        return notes.entrySet().stream().filter(e -> {
            synchronized (e.getValue()) {
                return !e.getValue().isEmpty();
            }
        }).map(Map.Entry::getKey).toList();
    }

    public List<Note> pending() {
        List<Note> all = new ArrayList<>();
        notes.values().forEach(q -> {
            synchronized (q) {
                all.addAll(q);
            }
        });
        all.sort(Comparator.comparingLong(Note::at));
        return all;
    }

    public void receipt(List<Note> delivered, String place, String said, String reason) {
        for (Note note : delivered) {
            String text = said != null
                    ? place + "那边：你想说的「" + abbreviate(note.intent(), 120) + "」已经说了：" + abbreviate(said.replace('\n', ' '), 400)
                    : place + "那边：你想说的「" + abbreviate(note.intent(), 120) + "」没说出口（" + reason + "）";
            post("receipt", note.session(), text, true);
        }
    }

    public void report(String session, String text) {
        post("report", session, text, false);
    }

    public List<String> drain() {
        synchronized (inbox) {
            List<String> out = new ArrayList<>(inbox);
            if (crowdedOut > 0) {
                out.addFirst("（还有 " + crowdedOut + " 条更早的回执和汇报，来得太多没记住）");
            }
            inbox.clear();
            crowdedOut = 0;
            settings.put(INBOX_KEY, "[]");
            return out;
        }
    }

    public boolean hasNews() {
        synchronized (inbox) {
            return !inbox.isEmpty();
        }
    }

    public List<Event> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public Commitment commit(String session, String who, String what) {
        synchronized (commitments) {
            Commitment same = best(session, what, 0.6);
            if (same != null) {
                return same;
            }
            long now = System.currentTimeMillis();
            Commitment c = new Commitment(ids.incrementAndGet(), session, who, abbreviate(what.strip(), 160), OPEN, "", now, now);
            commitments.add(c);
            save();
            event("commit", session, c.what());
            return c;
        }
    }

    public Commitment finish(String session, String what) {
        synchronized (commitments) {
            Commitment c = best(session, what, 0.0);
            return c == null ? null : settle(c.id(), DONE, what);
        }
    }

    public Commitment settle(long id, String status, String result) {
        synchronized (commitments) {
            for (int i = 0; i < commitments.size(); i++) {
                Commitment c = commitments.get(i);
                if (c.id() == id) {
                    Commitment next = new Commitment(c.id(), c.session(), c.who(), c.what(), status, abbreviate(result == null ? "" : result.strip(), 160),
                            c.createdAt(), System.currentTimeMillis());
                    commitments.set(i, next);
                    save();
                    event(status.equals(DONE) ? "done" : "drop", c.session(), c.what());
                    return next;
                }
            }
            return null;
        }
    }

    public List<Commitment> open() {
        synchronized (commitments) {
            return commitments.stream().filter(Commitment::open).toList();
        }
    }

    public List<Commitment> entrustedInPrivate(long withinMs) {
        long now = System.currentTimeMillis();
        synchronized (commitments) {
            return commitments.stream().filter(c -> c.session().startsWith("private:") && (c.open() || now - c.updatedAt() <= withinMs)).toList();
        }
    }

    public List<Commitment> commitments() {
        synchronized (commitments) {
            return List.copyOf(commitments);
        }
    }

    private Commitment best(String session, String what, double floor) {
        Commitment best = null;
        double score = floor;
        for (Commitment c : commitments) {
            if (!c.open() || !c.session().equals(session)) {
                continue;
            }
            double s = overlap(c.what(), what);
            if (best == null && floor == 0.0 || s > score) {
                best = c;
                score = Math.max(score, s);
            }
        }
        return best;
    }

    static double overlap(String a, String b) {
        Set<String> x = grams(a);
        Set<String> y = grams(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> both = new HashSet<>(x);
        both.retainAll(y);
        return (double) both.size() / Math.min(x.size(), y.size());
    }

    private static Set<String> grams(String s) {
        String t = s.replaceAll("[\\s\\p{Punct}，。！？、~…“”‘’（）]", "");
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    private void post(String kind, String session, String text, boolean urgent) {
        synchronized (inbox) {
            inbox.addLast(text);
            while (inbox.size() > MAX_INBOX) {
                inbox.pollFirst();
                crowdedOut++;
            }
            settings.put(INBOX_KEY, GSON.toJson(inbox));
        }
        event(kind, session, text);
        if (urgent) {
            stir.run();
        }
    }

    private void event(String kind, String session, String text) {
        synchronized (events) {
            events.addLast(new Event(System.currentTimeMillis(), kind, session, text));
            while (events.size() > MAX_EVENTS) {
                events.pollFirst();
            }
        }
    }

    private void load() {
        String raw = settings.get(KEY);
        try {
            List<Commitment> saved = raw == null || raw.isBlank() ? null : GSON.fromJson(raw, new TypeToken<List<Commitment>>() {}.getType());
            if (saved != null) {
                saved.removeIf(c -> c.id() > 1_000_000_000L);
                commitments.addAll(saved);
                ids.set(saved.stream().mapToLong(Commitment::id).max().orElse(0));
            }
        } catch (JsonParseException ignored) {
        }
        try {
            String savedNotes = settings.get(NOTES_KEY);
            List<Note> pending = savedNotes == null || savedNotes.isBlank() ? null : GSON.fromJson(savedNotes, new TypeToken<List<Note>>() {}.getType());
            if (pending != null) {
                for (Note note : pending) {
                    notes.computeIfAbsent(note.session(), k -> new ArrayDeque<>()).addLast(note);
                    ids.accumulateAndGet(note.id(), Math::max);
                }
            }
        } catch (JsonParseException ignored) {
        }
        try {
            String savedInbox = settings.get(INBOX_KEY);
            List<String> unread = savedInbox == null || savedInbox.isBlank() ? null : GSON.fromJson(savedInbox, new TypeToken<List<String>>() {}.getType());
            if (unread != null) {
                inbox.addAll(unread);
            }
        } catch (JsonParseException ignored) {
        }
    }

    private void saveNotes() {
        settings.put(NOTES_KEY, GSON.toJson(pending()));
    }

    private void save() {
        long now = System.currentTimeMillis();
        commitments.removeIf(c -> !c.open() && now - c.updatedAt() > KEEP_CLOSED_MS);
        while (commitments.size() > MAX_COMMITMENTS) {
            Commitment oldest = commitments.stream().filter(c -> !c.open()).findFirst().orElse(commitments.getFirst());
            commitments.remove(oldest);
        }
        settings.put(KEY, GSON.toJson(commitments));
    }
}
