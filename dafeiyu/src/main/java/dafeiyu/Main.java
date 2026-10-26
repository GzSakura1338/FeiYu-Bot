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
import dafeiyu.onebot.OneBotService;
import com.google.gson.JsonArray;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.plugin.PluginHost;
import dafeiyu.plugin.api.QqPort;
import dafeiyu.plugin.install.PluginInstaller;
import dafeiyu.plugin.mcp.McpManager;
import dafeiyu.plugin.store.PluginStore;
import dafeiyu.plugin.store.Secrets;
import dafeiyu.web.WebConsole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public final class Main {

    private static final java.util.logging.Logger HTTP_LOG = java.util.logging.Logger.getLogger("jdk.httpclient.HttpClient");

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.httpclient.HttpClient.log", "errors");
        Path configFile = Path.of(args.length > 0 ? args[0] : "config.properties").toAbsolutePath();
        Path baseDir = configFile.getParent();
        AppConfig config = AppConfig.load(configFile);
        String zoneProblem = null;
        if (!config.timeZone().isEmpty()) {
            try {
                TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(config.timeZone())));
            } catch (DateTimeException e) {
                zoneProblem = "time.zone is not a valid zone id (" + config.timeZone() + "), keeping " + ZoneId.systemDefault();
            }
        }
        LogBuffer log = new LogBuffer(500);
        if (!Files.exists(configFile)) {
            log.warn("config file not found, using defaults: " + configFile);
        }
        if (zoneProblem != null) {
            log.warn(zoneProblem);
        }
        log.info("time zone " + ZoneId.systemDefault());
        HTTP_LOG.setUseParentHandlers(false);
        HTTP_LOG.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                String message = record.getMessage();
                if (message != null && message.contains("fatal error")) {
                    log.error("http client: " + message.replaceAll("\\s*\\R\\s*", " | "));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        Database db = Database.open(baseDir.resolve(config.dbPath()));
        log.writeTo(db.file().toAbsolutePath().getParent().resolve("logs"));
        db.warnings().forEach(log::warn);

        Path legacyProfiles = baseDir.resolve("ws-profiles.json");
        boolean hadLegacy = Files.exists(legacyProfiles);
        WsProfileStore profiles = WsProfileStore.open(db, legacyProfiles, config.ws());
        if (hadLegacy && !Files.exists(legacyProfiles)) {
            log.info("imported ws-profiles.json into the database (renamed to ws-profiles.json.imported)");
        }

        OneBotService onebot = new OneBotService(profiles.active().settings(), log);
        MessageArchive archive = new MessageArchive(db);
        WorkspaceStore workspaces = new WorkspaceStore(db, List.of(baseDir, db.file().getParent()));
        UsageStore usage = new UsageStore(db);
        Protocols.setMeter(usage::record);
        ChatAgent agent = new ChatAgent(db, new AgentStore(db), new PersonaStore(db), workspaces);
        agent.reflection().setLog(log);
        agent.reflection().start();
        agent.stickers().setLog(log);
        QqChatBridge bridge = new QqChatBridge(agent, onebot, archive, db, log);
        bridge.startLife();
        Path dataDir = db.file().toAbsolutePath().getParent();
        Secrets.init(dataDir);
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
        PluginHost plugins = new PluginHost(agent, qqPort, archive, pluginStore, baseDir.resolve("plugins"), dataDir.resolve("plugins"),
                List.of(), log);
        McpManager mcp = new McpManager(pluginStore, dataDir.resolve("mcp"), log, plugins::refreshCatalog);
        plugins.setMcp(mcp);
        agent.setExtensions(plugins.extensions());
        bridge.setGate(plugins.gate());
        workspaces.setExtraTools(plugins::toolNames);
        Mind mind = new Mind(agent, bridge, log);
        mind.start();
        onebot.addEventListener(archive::onEvent);
        onebot.addEventListener(bridge::onEvent);
        onebot.addEventListener(plugins::onOneBotEvent);
        onebot.addConnectListener(bridge::onConnected);
        onebot.addConnectListener(plugins::onConnected);
        onebot.addConnectListener(mind::resume);
        plugins.start();
        mcp.start();
        onebot.start();

        PluginInstaller installer = new PluginInstaller(plugins, dataDir.resolve("plugin-staging"), log);
        WebConsole web = new WebConsole(config.webAddress(), config.webDir(), onebot, profiles, agent, bridge, mind, archive, usage, plugins,
                installer, log);
        web.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.stop();
            plugins.shutdown();
            onebot.stop();
            db.close();
        }));

        log.info("dafeiyu started: console http://" + config.webHost() + ":" + config.webPort()
                + "/, OneBot ws " + profiles.active().settings().url()
                + (agent.store().qqReply() && agent.store().active() != null
                        ? ", QQ auto-reply ON (" + agent.store().active().model() + ")" : ""));
        Thread.currentThread().join();
    }
}
