package dafeiyu.config;

import java.io.IOException;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record AppConfig(
        WsSettings ws,
        String webHost,
        int webPort,
        Path webDir,
        Path dbPath) {

    public static AppConfig load(Path file) throws IOException {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }
        return new AppConfig(
                new WsSettings(
                        props.getProperty("ws.host", "127.0.0.1"),
                        Integer.parseInt(props.getProperty("ws.port", "6199").trim()),
                        props.getProperty("ws.path", "/ws"),
                        props.getProperty("ws.token", "")),
                props.getProperty("web.host", "127.0.0.1"),
                Integer.parseInt(props.getProperty("web.port", "8090").trim()),
                Path.of(props.getProperty("web.dir", "src/main/resources/web")),
                Path.of(props.getProperty("db.path", "data/dafeiyu.db")));
    }

    public InetSocketAddress webAddress() {
        return new InetSocketAddress(webHost, webPort);
    }
}
