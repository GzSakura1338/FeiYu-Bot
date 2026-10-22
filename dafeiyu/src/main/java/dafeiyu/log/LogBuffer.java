package dafeiyu.log;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class LogBuffer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int capacity;
    private final Deque<LogEntry> entries = new ArrayDeque<>();
    private long nextId = 1;

    public LogBuffer(int capacity) {
        this.capacity = capacity;
    }

    public void info(String text) {
        add("INFO", text);
    }

    public void warn(String text) {
        add("WARN", text);
    }

    public void error(String text) {
        add("ERROR", text);
    }

    private synchronized void add(String level, String text) {
        LogEntry entry = new LogEntry(nextId++, LocalTime.now().format(TIME), level, text);
        entries.addLast(entry);
        while (entries.size() > capacity) {
            entries.removeFirst();
        }
        System.out.println("[" + entry.time() + "] [" + level + "] " + text);
    }

    public synchronized long latestId() {
        return nextId - 1;
    }

    public synchronized List<LogEntry> since(long afterId) {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : entries) {
            if (entry.id() > afterId) {
                result.add(entry);
            }
        }
        return result;
    }
}
