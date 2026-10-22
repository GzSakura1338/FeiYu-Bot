package dafeiyu;

import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.mind.Mind;
import dafeiyu.agent.model.AgentStore;
import dafeiyu.agent.persona.PersonaStore;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.agent.protocol.Protocols;
import dafeiyu.agent.tool.WorkspaceStore;
import dafeiyu.agent.usage.UsageStore;
import dafeiyu.config.AppConfig;
import dafeiyu.config.WsProfileStore;
import dafeiyu.db.Database;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.NapCatService;
import com.google.gson.JsonArray;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.plugin.PluginHost;
import dafeiyu.plugin.api.QqPort;
import dafeiyu.plugin.install.PluginInstaller;
import dafeiyu.plugin.mcp.McpManager;
import dafeiyu.plugin.store.PluginStore;
import dafeiyu.web.WebConsole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Main {

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of(args.length > 0 ? args[0] : "config.properties").toAbsolutePath();
        Path baseDir = configFile.getParent();
        AppConfig config = AppConfig.load(configFile);
        LogBuffer log = new LogBuffer(500);
        if (!Files.exists(configFile)) {
            log.warn("config file not found, using defaults: " + configFile);
        }

        Database db = Database.open(baseDir.resolve(config.dbPath()));

        Path legacyProfiles = baseDir.resolve("ws-profiles.json");
        boolean hadLegacy = Files.exists(legacyProfiles);
        WsProfileStore profiles = WsProfileStore.open(db, legacyProfiles, config.ws());
        if (hadLegacy && !Files.exists(legacyProfiles)) {
            log.info("imported ws-profiles.json into the database (renamed to ws-profiles.json.imported)");
        }

        NapCatService napcat = new NapCatService(profiles.active().settings(), log);
        MessageArchive archive = new MessageArchive(db);
        WorkspaceStore workspaces = new WorkspaceStore(db, List.of(baseDir, db.file().getParent()));
        UsageStore usage = new UsageStore(db);
        Protocols.setMeter(usage::record);
        ChatAgent agent = new ChatAgent(db, new AgentStore(db), new PersonaStore(db), workspaces);
        agent.reflection().setLog(log);
        agent.reflection().start();
        agent.stickers().setLog(log);
        QqChatBridge bridge = new QqChatBridge(agent, napcat, archive, log);
        bridge.startLife();
        Path dataDir = db.file().toAbsolutePath().getParent();
        PluginStore pluginStore = new PluginStore(db);
        QqPort qqPort = new QqPort() {
            @Override
            public String send(String session, String text, String image, String quote, String label) throws ToolFailure {
                return bridge.pluginSend(session, text, image, quote, label);
            }

            @Override
            public Map<String, String> groupNames() {
                return bridge.groupNames();
            }

            @Override
            public JsonArray members(String groupId) throws ToolFailure {
                return bridge.groupMembers(groupId);
            }

            @Override
            public boolean connected() {
                return bridge.connected();
            }
        };
        PluginHost plugins = new PluginHost(agent, qqPort, archive, usage, pluginStore, baseDir.resolve("plugins"), dataDir.resolve("plugins"),
                List.of(), log);
        McpManager mcp = new McpManager(pluginStore, dataDir.resolve("mcp"), log, plugins::refreshCatalog);
        plugins.setMcp(mcp);
        agent.setExtensions(plugins.extensions());
        bridge.setGate(plugins.gate());
        workspaces.setExtraTools(plugins::toolNames);
        Mind mind = new Mind(agent, bridge, log);
        mind.start();
        napcat.addEventListener(archive::onEvent);
        napcat.addEventListener(bridge::onEvent);
        napcat.addEventListener(plugins::onNapcatEvent);
        napcat.addConnectListener(bridge::onConnected);
        napcat.addConnectListener(plugins::onConnected);
        plugins.start();
        mcp.start();
        napcat.start();

        PluginInstaller installer = new PluginInstaller(plugins, dataDir.resolve("plugin-staging"), log);
        WebConsole web = new WebConsole(config.webAddress(), config.webDir(), napcat, profiles, agent, bridge, mind, archive, usage, plugins,
                installer, log);
        web.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.stop();
            plugins.shutdown();
            napcat.stop();
            db.close();
        }));

        log.info("dafeiyu started: console http://" + config.webHost() + ":" + config.webPort()
                + "/, NapCat ws " + profiles.active().settings().url()
                + (agent.store().qqReply() && agent.store().active() != null
                        ? ", QQ auto-reply ON (" + agent.store().active().model() + ")" : ""));
        Thread.currentThread().join();
    }
}
