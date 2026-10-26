package dafeiyu.onebot;

import com.google.gson.JsonObject;
import dafeiyu.config.WsSettings;
import dafeiyu.log.LogBuffer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class OneBotService {

    private final LogBuffer log;
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> connectListeners = new CopyOnWriteArrayList<>();
    private OneBotServer server;
    private WsSettings settings;

    public OneBotService(WsSettings settings, LogBuffer log) {
        this.settings = settings;
        this.log = log;
    }

    public void addEventListener(Consumer<JsonObject> listener) {
        listeners.add(listener);
    }

    public void addConnectListener(Runnable listener) {
        connectListeners.add(listener);
    }

    private void connected() {
        for (Runnable listener : connectListeners) {
            Thread.ofVirtual().start(() -> {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    log.error("connect listener failed: " + e);
                }
            });
        }
    }

    private void dispatch(JsonObject event) {
        for (Consumer<JsonObject> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.error("event listener failed: " + e);
            }
        }
    }

    public synchronized void start() {
        server = new OneBotServer(settings, log, this::dispatch, this::connected);
        server.start();
    }

    public synchronized void stop() {
        stopServer();
    }

    public synchronized WsSettings settings() {
        return settings;
    }

    public synchronized void reconfigure(WsSettings next) {
        String error = next.validate();
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        WsSettings previous = settings;
        stopServer();
        try {
            checkBindable(next);
        } catch (IllegalArgumentException e) {
            server = new OneBotServer(previous, log, this::dispatch, this::connected);
            server.start();
            throw e;
        }
        settings = next;
        server = new OneBotServer(next, log, this::dispatch, this::connected);
        server.start();
        log.info("ws listening on " + next.url() + (next.token().isEmpty() ? " (no token check)" : ""));
    }

    public OneBotStatus status() {
        OneBotServer current;
        synchronized (this) {
            current = server;
        }
        return current == null
                ? new OneBotStatus(false, settings().url(), null, null, null, null, null)
                : current.status();
    }

    public CompletableFuture<JsonObject> callAction(String action, JsonObject params) {
        return callAction(action, params, OneBotServer.ACTION_TIMEOUT);
    }

    public CompletableFuture<JsonObject> callAction(String action, JsonObject params, Duration timeout) {
        OneBotServer current;
        synchronized (this) {
            current = server;
        }
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("OneBot server is not running"));
        }
        return current.callAction(action, params, timeout);
    }

    private void stopServer() {
        if (server == null) {
            return;
        }
        try {
            server.stop(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        server = null;
    }

    private static void checkBindable(WsSettings ws) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(ws.address());
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            throw new IllegalArgumentException("无法监听 " + ws.host() + ":" + ws.port() + "（" + e.getMessage() + "），已恢复原设置");
        }
    }
}
