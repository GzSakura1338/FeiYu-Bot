package dafeiyu.agent.tool;

import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class WorkspaceStore {

    public enum QqAccess { OFF, LIST, ALL }

    public record Config(String dir, List<String> enabled, QqAccess qqAccess, List<String> qqUsers, boolean shellNetwork) {}

    private static final String DIR_KEY = "agent.workdir";
    private static final String NETWORK_KEY = "agent.tools.shellNetwork";
    private static final String DISABLED_KEY = "agent.tools.disabled";
    private static final String QQ_ACCESS_KEY = "agent.tools.qqAccess";
    private static final String QQ_USERS_KEY = "agent.tools.qqUsers";
    private static final String OPT_IN_KEY = "agent.tools.optIn";
    private static final Set<String> OPT_IN = Set.of("shell");
    private static final int MAX_QQ_USERS = 200;

    private final Settings settings;
    private final List<Path> protectedDirs;
    private volatile Supplier<List<String>> extraTools = List::of;

    public WorkspaceStore(Database db, List<Path> protectedDirs) {
        this.settings = new Settings(db);
        this.protectedDirs = List.copyOf(protectedDirs);
    }

    public void setExtraTools(Supplier<List<String>> extraTools) {
        this.extraTools = extraTools == null ? List::of : extraTools;
    }

    private List<String> allTools() {
        List<String> all = new ArrayList<>(Toolbox.names());
        all.addAll(extraTools.get());
        return all;
    }

    public synchronized Config config() {
        Set<String> disabled = split(settings.get(DISABLED_KEY));
        Set<String> optedIn = split(settings.get(OPT_IN_KEY));
        List<String> enabled = allTools().stream().filter(n -> !disabled.contains(n) && (!OPT_IN.contains(n) || optedIn.contains(n))).toList();
        QqAccess access;
        try {
            access = QqAccess.valueOf(value(QQ_ACCESS_KEY, "OFF"));
        } catch (IllegalArgumentException e) {
            access = QqAccess.OFF;
        }
        return new Config(value(DIR_KEY, ""), enabled, access, List.copyOf(split(settings.get(QQ_USERS_KEY))),
                "true".equals(settings.get(NETWORK_KEY)));
    }

    public synchronized Config update(Config next) {
        String dir = next.dir() == null ? "" : next.dir().strip();
        if (!dir.isEmpty()) {
            dir = open(dir).root().toString();
        }
        Set<String> enabled = next.enabled() == null ? Set.of() : Set.copyOf(next.enabled());
        List<String> known = allTools();
        for (String name : enabled) {
            if (!known.contains(name)) {
                throw new IllegalArgumentException("没有这个工具：" + name);
            }
        }
        Set<String> disabled = new LinkedHashSet<>(known.stream().filter(n -> !enabled.contains(n)).toList());
        split(settings.get(DISABLED_KEY)).stream().filter(n -> !known.contains(n)).forEach(disabled::add);
        List<String> users = next.qqUsers() == null ? List.of() : next.qqUsers().stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        for (String user : users) {
            if (!user.matches("\\d{5,12}")) {
                throw new IllegalArgumentException("QQ 号格式不对：" + user);
            }
        }
        if (users.size() > MAX_QQ_USERS) {
            throw new IllegalArgumentException("QQ 号最多 " + MAX_QQ_USERS + " 个");
        }
        QqAccess access = next.qqAccess() == null ? QqAccess.OFF : next.qqAccess();
        settings.put(DIR_KEY, dir);
        settings.put(DISABLED_KEY, String.join(",", disabled));
        settings.put(OPT_IN_KEY, OPT_IN.stream().filter(enabled::contains).sorted().collect(Collectors.joining(",")));
        settings.put(QQ_ACCESS_KEY, access.name());
        settings.put(QQ_USERS_KEY, String.join(",", users));
        settings.put(NETWORK_KEY, String.valueOf(next.shellNetwork()));
        return config();
    }

    public Workspace workspace() {
        String dir = config().dir();
        if (dir.isEmpty()) {
            return null;
        }
        try {
            return open(dir);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String problem() {
        String dir = config().dir();
        if (dir.isEmpty()) {
            return null;
        }
        try {
            open(dir);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public boolean allows(String qqUser) {
        if (qqUser == null) {
            return true;
        }
        Config config = config();
        return switch (config.qqAccess()) {
            case ALL -> true;
            case LIST -> config.qqUsers().contains(qqUser);
            case OFF -> false;
        };
    }

    private Workspace open(String dir) {
        return Workspace.open(dir, protectedDirs);
    }

    private String value(String key, String fallback) {
        String value = settings.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static Set<String> split(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
