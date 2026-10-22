package dafeiyu.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class SseWriter implements AutoCloseable {

    private final OutputStream out;

    SseWriter(OutputStream out) {
        this.out = out;
    }

    void send(String event, String data) {
        send(event, new JsonPrimitive(data));
    }

    synchronized void send(String event, JsonElement data) {
        try {
            out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    synchronized void comment(String text) {
        try {
            out.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
