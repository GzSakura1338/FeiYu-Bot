package dafeiyu.web;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.mind.Mind;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.agent.usage.UsageStore;
import dafeiyu.config.WsProfileStore;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.OneBotService;
import dafeiyu.plugin.PluginHost;
import dafeiyu.plugin.install.PluginInstaller;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public final class WebConsole {

    private final HttpServer server;

    public WebConsole(InetSocketAddress address, Path webDir, OneBotService onebot, WsProfileStore profiles,
                      ChatAgent agent, QqChatBridge bridge, Mind mind, MessageArchive archive, UsageStore usage, PluginHost plugins,
                      PluginInstaller installer, LogBuffer log) throws IOException {
        ApiHandler api = new ApiHandler(onebot, archive, log);
        server = HttpServer.create(address, 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        java.util.function.UnaryOperator<HttpHandler> guarded =
                h -> new OriginGuard(h, address.getHostString(), address.getPort());
        if (Files.isDirectory(webDir)) {
            server.createContext("/", guarded.apply(new StaticFileHandler(webDir, null)));
        } else {
            server.createContext("/", guarded.apply(new StaticFileHandler(null, "/web")));
        }
        server.createContext("/api/status", guarded.apply(api::status));
        server.createContext("/api/logs", guarded.apply(api::logs));
        server.createContext("/api/messages", guarded.apply(api::messages));
        server.createContext("/api/ws-profiles", guarded.apply(new WsProfileHandler(profiles, onebot, log)::handle));
        QqDirectory directory = new QqDirectory(onebot, archive);
        server.createContext("/api/agent", guarded.apply(new AgentHandler(agent, log)::handle));
        server.createContext("/api/agent/stickers", guarded.apply(new StickersHandler(agent, bridge, log)::handle));
        server.createContext("/api/agent/sessions", guarded.apply(new SessionHandler(agent, bridge, directory, log)::handle));
        server.createContext("/api/agent/memory", guarded.apply(new MemoryHandler(agent, directory, log)::handle));
        server.createContext("/api/agent/mind", guarded.apply(new MindHandler(agent, mind, log)::handle));
        server.createContext("/api/agent/files", guarded.apply(new FilesHandler(agent, log)::handle));
        server.createContext("/api/agent/qqapi", guarded.apply(new QqApiHandler(bridge, log)::handle));
        server.createContext("/api/agent/image-gen", guarded.apply(new ImageGenHandler(agent, log)::handle));
        server.createContext("/api/agent/video", guarded.apply(new VideoHandler(agent, log)::handle));
        server.createContext("/api/usage", guarded.apply(new UsageHandler(agent, usage, directory, onebot)::handle));
        server.createContext("/api/personas", guarded.apply(new PersonaHandler(agent.personas(), agent.store(), log)::handle));
        server.createContext("/api/plugins", guarded.apply(new PluginHandler(plugins, installer, bridge, log)::handle));
        server.createContext("/api/mcp", guarded.apply(new McpHandler(plugins.mcp(), log)::handle));
        server.createContext("/api/media", guarded.apply(new MediaHandler(bridge)::handle));
        server.createContext("/plugins/", guarded.apply(new PluginPageHandler(plugins)::handle));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }
}
