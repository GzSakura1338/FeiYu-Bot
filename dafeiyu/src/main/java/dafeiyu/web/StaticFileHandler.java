package dafeiyu.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class StaticFileHandler implements HttpHandler {

    private final Path dir;
    private final String classpathRoot;

    StaticFileHandler(Path dir, String classpathRoot) {
        this.dir = dir == null ? null : dir.toAbsolutePath().normalize();
        this.classpathRoot = classpathRoot;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String name = path.equals("/") ? "index.html" : path.substring(1);
        byte[] body = name.contains("..") || name.contains("//") || name.contains("\\") ? null : read(name);
        if (body == null) {
            HttpResponses.notFound(ex);
            return;
        }
        HttpResponses.send(ex, 200, contentType(name), body);
    }

    private byte[] read(String name) throws IOException {
        if (dir != null) {
            Path file = dir.resolve(name).normalize();
            return file.startsWith(dir) && Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
        try (InputStream in = StaticFileHandler.class.getResourceAsStream(classpathRoot + "/" + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static String contentType(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
    }
}
