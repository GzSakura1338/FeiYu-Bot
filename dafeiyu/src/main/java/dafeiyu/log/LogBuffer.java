package dafeiyu.log;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

public final class LogBuffer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int KEEP_DAYS = 14;
    private static final String FILE_PATTERN = "dafeiyu-\\d{4}-\\d{2}-\\d{2}\\.log";

    private final int capacity;
    private final Deque<LogEntry> entries = new ArrayDeque<>();
    private long nextId = 1;
    private Path folder;
    private LocalDate fileDay;
    private Writer file;

    public LogBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void writeTo(Path folder) {
        try {
            Files.createDirectories(folder);
            LocalDate oldest = LocalDate.now().minusDays(KEEP_DAYS);
            try (Stream<Path> files = Files.list(folder)) {
                for (Path p : files.toList()) {
                    String name = p.getFileName().toString();
                    if (name.matches(FILE_PATTERN) && LocalDate.parse(name.substring(8, 18)).isBefore(oldest)) {
                        Files.deleteIfExists(p);
                    }
                }
            }
            this.folder = folder;
        } catch (IOException | RuntimeException e) {
            System.out.println("[WARN] log files disabled, cannot write to " + folder + ": " + e.getMessage());
        }
    }

    private void persist(LogEntry entry) {
        if (folder == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        try {
            if (file == null || !today.equals(fileDay)) {
                if (file != null) {
                    file.close();
                }
                file = Files.newBufferedWriter(folder.resolve("dafeiyu-" + today + ".log"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                fileDay = today;
            }
            file.write("[" + entry.time() + "] [" + entry.level() + "] " + entry.text() + System.lineSeparator());
            file.flush();
        } catch (IOException e) {
            folder = null;
            System.out.println("[WARN] log files disabled: " + e.getMessage());
        }
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
        persist(entry);
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
