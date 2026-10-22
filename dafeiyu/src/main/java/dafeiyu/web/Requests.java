package dafeiyu.web;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class Requests {

    private static final Gson GSON = new Gson();

    private Requests() {}

    static boolean isJson(HttpExchange ex) {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        return type != null && type.toLowerCase().startsWith("application/json");
    }

    static boolean isUpload(HttpExchange ex) {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        String t = type == null ? "" : type.toLowerCase();
        return t.startsWith("application/zip") || t.startsWith("application/x-zip-compressed")
                || t.startsWith("application/octet-stream") || t.startsWith("text/markdown");
    }

    static <T> T readJson(HttpExchange ex, Class<T> type) throws IOException {
        T value;
        try {
            value = GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), type);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("请求体格式不对");
        }
        if (value == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        return value;
    }

    static int intParam(HttpExchange ex, String name, int fallback) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                try {
                    return Integer.parseInt(pair.substring(name.length() + 1));
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
