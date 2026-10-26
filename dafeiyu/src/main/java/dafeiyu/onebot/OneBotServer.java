package dafeiyu.onebot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dafeiyu.config.WsSettings;
import dafeiyu.log.LogBuffer;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

final class OneBotServer extends WebSocketServer {

    static final Duration ACTION_TIMEOUT = Duration.ofSeconds(10);

    private static final long PROFILE_REFRESH_MS = 60_000;

    private final String path;
    private final String token;
    private final LogBuffer log;
    private final PendingActions pending = new PendingActions();
    private final Consumer<JsonObject> events;
    private final Runnable onReady;

    private volatile WebSocket conn;
    private volatile String selfId;
    private volatile String nickname;
    private volatile String remote;
    private volatile Instant connectedAt;
    private volatile Instant lastHeartbeat;
    private volatile long profileAt;

    OneBotServer(WsSettings settings, LogBuffer log, Consumer<JsonObject> events, Runnable onReady) {
        super(settings.address());
        this.events = events;
        this.onReady = onReady;
        this.path = settings.path();
        this.token = settings.token();
        this.log = log;
        setReuseAddr(true);
        setConnectionLostTimeout(60);
    }

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request)
            throws InvalidDataException {
        ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        String resource = request.getResourceDescriptor();
        String requestPath = resource.contains("?") ? resource.substring(0, resource.indexOf('?')) : resource;
        if (!requestPath.equals(path)) {
            log.warn("rejected connection: wrong path " + requestPath);
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "bad path");
        }
        if (!token.isEmpty() && !token.equals(extractToken(request, resource))) {
            log.warn("rejected connection from " + conn.getRemoteSocketAddress() + ": bad token");
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "bad token");
        }
        return builder;
    }

    private static String extractToken(ClientHandshake request, String resource) {
        String auth = request.getFieldValue("Authorization");
        if (auth != null && !auth.isBlank()) {
            return auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : auth.trim();
        }
        int q = resource.indexOf('?');
        if (q >= 0) {
            for (String pair : resource.substring(q + 1).split("&")) {
                if (pair.startsWith("access_token=")) {
                    return pair.substring("access_token=".length());
                }
            }
        }
        return "";
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onOpen(WebSocket ws, ClientHandshake handshake) {
        WebSocket old = conn;
        if (old != null && old.isOpen()) {
            log.warn("new OneBot connection replaces the old one");
            old.close(CloseFrame.NORMAL, "replaced");
        }
        selfId = handshake.getFieldValue("X-Self-ID");
        nickname = null;
        remote = String.valueOf(ws.getRemoteSocketAddress());
        connectedAt = Instant.now();
        lastHeartbeat = null;
        conn = ws;
        log.info("OneBot connected: QQ " + selfId + " from " + remote);

        profileAt = System.currentTimeMillis();
        callAction("get_login_info", new JsonObject()).thenAccept(resp -> {
            JsonObject data = optObject(resp, "data");
            if (data != null && data.has("nickname")) {
                nickname = optString(data, "nickname");
                log.info("logged in as " + nickname + " (" + optString(data, "user_id") + ")");
            }
            onReady.run();
        }).exceptionally(e -> {
            log.warn("get_login_info failed: " + e.getMessage());
            onReady.run();
            return null;
        });
    }

    private void refreshProfile() {
        long now = System.currentTimeMillis();
        if (now - profileAt < PROFILE_REFRESH_MS) {
            return;
        }
        profileAt = now;
        callAction("get_login_info", new JsonObject()).thenAccept(resp -> {
            JsonObject data = optObject(resp, "data");
            String fresh = data == null ? "" : optString(data, "nickname");
            if (!fresh.isEmpty() && !fresh.equals(nickname)) {
                log.info("QQ nickname changed: " + nickname + " -> " + fresh);
                nickname = fresh;
            }
        }).exceptionally(e -> null);
    }

    @Override
    public void onClose(WebSocket ws, int code, String reason, boolean byRemote) {
        if (ws != conn) {
            return;
        }
        conn = null;
        pending.failAll("connection closed");
        log.warn("OneBot disconnected (code " + code + (reason == null || reason.isEmpty() ? "" : ", " + reason) + ")");
    }

    @Override
    public void onMessage(WebSocket ws, String message) {
        JsonObject json;
        try {
            json = JsonParser.parseString(message).getAsJsonObject();
        } catch (RuntimeException e) {
            log.warn("unparseable message: " + abbreviate(message, 200));
            return;
        }

        if (json.has("echo") && json.has("status")) {
            pending.complete(optString(json, "echo"), json);
            return;
        }

        if (optString(json, "post_type").equals("meta_event")) {
            String metaType = optString(json, "meta_event_type");
            if (metaType.equals("heartbeat")) {
                lastHeartbeat = Instant.now();
                refreshProfile();
            } else {
                log.info("meta event: " + metaType + " " + optString(json, "sub_type"));
            }
            return;
        }
        log.info(EventFormatter.describe(json));
        events.accept(json);
    }

    @Override
    public void onError(WebSocket ws, Exception ex) {
        log.error("websocket error: " + ex);
    }

    CompletableFuture<JsonObject> callAction(String action, JsonObject params) {
        return callAction(action, params, ACTION_TIMEOUT);
    }

    CompletableFuture<JsonObject> callAction(String action, JsonObject params, Duration timeout) {
        WebSocket ws = conn;
        if (ws == null || !ws.isOpen()) {
            return CompletableFuture.failedFuture(new IllegalStateException("QQ is not connected"));
        }
        PendingActions.Pending call = pending.register();
        JsonObject req = new JsonObject();
        req.addProperty("action", action);
        req.add("params", params);
        req.addProperty("echo", call.echo());
        try {
            ws.send(req.toString());
        } catch (RuntimeException e) {
            call.future().completeExceptionally(new IllegalStateException("QQ is not connected"));
        }
        return call.future().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    OneBotStatus status() {
        WebSocket ws = conn;
        if (ws == null || !ws.isOpen()) {
            return new OneBotStatus(false, listenUrl(), null, null, null, null, null);
        }
        Instant heartbeat = lastHeartbeat;
        return new OneBotStatus(true, listenUrl(), selfId, nickname, remote,
                connectedAt.toString(), heartbeat == null ? null : heartbeat.toString());
    }

    private String listenUrl() {
        return "ws://" + getAddress().getHostString() + ":" + getPort() + path;
    }
}
