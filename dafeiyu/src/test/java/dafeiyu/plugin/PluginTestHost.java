package dafeiyu.plugin;

import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.model.AgentStore;
import dafeiyu.agent.persona.PersonaStore;
import dafeiyu.agent.qq.MessageGate;
import dafeiyu.agent.tool.WorkspaceStore;
import dafeiyu.agent.usage.UsageStore;
import dafeiyu.db.Database;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.plugin.install.PluginInstaller;
import dafeiyu.plugin.store.PluginStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class PluginTestHost implements AutoCloseable {

    public static final String RED_LINE = "红线测试词";

    public final Path root;
    public final Path pluginsDir;
    public final Path dataDir;
    public final Database db;
    public final LogBuffer log = new LogBuffer(2000);
    public final ChatAgent agent;
    public final FakeQq qq = new FakeQq();
    public final UsageStore usage;
    public final PluginStore store;
    public final PluginHost host;
    public final PluginInstaller installer;
    public final MessageArchive archive;

    public PluginTestHost(String name) throws IOException {
        root = Path.of(System.getProperty("java.io.tmpdir"), "feiyu-plugin-test", name + "-" + System.currentTimeMillis());
        pluginsDir = root.resolve("plugins");
        dataDir = root.resolve("data");
        Files.createDirectories(pluginsDir.resolve("sdk"));
        Files.createDirectories(dataDir);
        Path sdk = Path.of("plugins", "sdk", "feiyu_plugin.py").toAbsolutePath();
        Files.copy(sdk, pluginsDir.resolve("sdk").resolve("feiyu_plugin.py"));
        Files.writeString(dataDir.resolve("content-guard.txt"), "[illegal]\n" + RED_LINE + "\n");
        db = Database.open(dataDir.resolve("test.db"));
        archive = new MessageArchive(db);
        WorkspaceStore workspaces = new WorkspaceStore(db, List.of(root));
        agent = new ChatAgent(db, new AgentStore(db), new PersonaStore(db), workspaces);
        store = new PluginStore(db);
        usage = new UsageStore(db);
        host = new PluginHost(agent, qq, archive, usage, store, pluginsDir, dataDir.resolve("plugins"), List.of(), log);
        agent.setExtensions(host.extensions());
        workspaces.setExtraTools(host::toolNames);
        installer = new PluginInstaller(host, dataDir.resolve("staging"), log);
    }

    public PluginTestHost install(String... ids) throws IOException {
        for (String id : ids) {
            Path from = Path.of("src", "test", "plugins", id).toAbsolutePath();
            copy(from, pluginsDir.resolve(id));
        }
        return this;
    }

    public void start() {
        host.start();
    }

    public void install2(String id) {
        try {
            if (!Files.isDirectory(pluginsDir.resolve(id))) {
                install(id);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public MessageGate.Message group(String text) {
        return new MessageGate.Message("group", "900001", "10001", "测试员", false, text, "", false, "m" + System.nanoTime(),
                System.currentTimeMillis(), true);
    }

    public MessageGate.Message priv(String text) {
        return new MessageGate.Message("private", "", "10002", "测试员", true, text, "", true, "m" + System.nanoTime(),
                System.currentTimeMillis(), true);
    }

    public String logs(String id) {
        StringBuilder out = new StringBuilder();
        store.logs(id, 0, 500).forEach(e -> out.append(e.level()).append(' ').append(e.text()).append('\n'));
        return out.toString();
    }

    public String appLog() {
        StringBuilder out = new StringBuilder();
        log.since(0).forEach(e -> out.append(e.text()).append('\n'));
        return out.toString();
    }

    public static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

    @Override
    public void close() {
        host.shutdown();
        db.close();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
