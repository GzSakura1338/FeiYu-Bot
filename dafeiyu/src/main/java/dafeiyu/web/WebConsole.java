package dafeiyu.web;

import com.sun.net.httpserver.HttpServer;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.mind.Mind;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.agent.usage.UsageStore;
import dafeiyu.config.WsProfileStore;
import dafeiyu.log.LogBuffer;
import dafeiyu.message.MessageArchive;
import dafeiyu.onebot.NapCatService;
import dafeiyu.plugin.PluginHost;
import dafeiyu.plugin.install.PluginInstaller;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public final class WebConsole {

    private final HttpServer server;

    public WebConsole(InetSocketAddress address, Path webDir, NapCatService napcat, WsProfileStore profiles,
                      ChatAgent agent, QqChatBridge bridge, Mind mind, MessageArchive archive, UsageStore usage, PluginHost plugins,
                      PluginInstaller installer, LogBuffer log) throws IOException {
        ApiHandler api = new ApiHandler(napcat, archive, log);
        server = HttpServer.create(address, 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        if (Files.isDirectory(webDir)) {
            server.createContext("/", new StaticFileHandler(webDir, null));
        } else {
            server.createContext("/", new StaticFileHandler(null, "/web"));
        }
        server.createContext("/api/status", api::status);
        server.createContext("/api/logs", api::logs);
        server.createContext("/api/messages", api::messages);
        server.createContext("/api/ws-profiles", new WsProfileHandler(profiles, napcat, log)::handle);
        QqDirectory directory = new QqDirectory(napcat, archive);
        server.createContext("/api/agent", new AgentHandler(agent, log)::handle);
        server.createContext("/api/agent/stickers", new StickersHandler(agent, bridge, log)::handle);
        server.createContext("/api/agent/sessions", new SessionHandler(agent, bridge, directory, log)::handle);
        server.createContext("/api/agent/memory", new MemoryHandler(agent, directory, log)::handle);
        server.createContext("/api/agent/mind", new MindHandler(agent, mind, log)::handle);
        server.createContext("/api/agent/files", new FilesHandler(agent, log)::handle);
        server.createContext("/api/usage", new UsageHandler(agent, usage, directory, napcat)::handle);
        server.createContext("/api/personas",new PersonaHandler(agent.personas(), log)::handle);
        server.createContext("/api/plugins", new PluginHandler(plugins, installer, bridge, log)::handle);
        server.createContext("/api/mcp", new McpHandler(plugins.mcp(), log)::handle);
        server.createContext("/plugins/", new PluginPageHandler(plugins)::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }
}
