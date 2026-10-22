package dafeiyu.agent.sticker;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.model.ImageLoader;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.model.ModelRetry;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.agent.sticker.StickerStore.Sticker;
import dafeiyu.agent.protocol.ChatRequest;
import dafeiyu.agent.protocol.ModelProtocol;
import dafeiyu.agent.protocol.Protocols;
import dafeiyu.log.LogBuffer;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class StickerKeeper {

    private static final int DESCRIBE_PER_HOUR = 120;
    private static final String UNSAFE_REPLY = "不合适";
    private static final String LIKE = "收藏";
    private static final String DESCRIBE_PROMPT = String.join("\n", List.of(
            "这是一张 QQ 表情包。",
            "第一行：用不超过 8 个字概括它表达的意思或情绪，像“猫猫震惊”“狗头嘲讽”“委屈求抱抱”“好耶”“无语凝噎”。",
            "第二行：你是一只爱收藏表情包的可爱小鲸鱼娘。如果这张又可爱、又好笑、或者聊天时很好用，让你想存下来，就写“" + LIKE + "”；"
                    + "普通、看不懂、太丑或者只是截图照片，就写“不要”。挑剔一点，只收真正喜欢的。",
            "如果图里有色情暴露、血腥暴力、政治人物或政治标语、违法内容，只回复：" + UNSAFE_REPLY,
            "只输出这两行，不要加标点和解释。"));

    private final ChatAgent agent;
    private final StickerStore store;
    private final ContentGuard guard;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stickers");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private final Deque<Long> described = new ArrayDeque<>();
    private volatile LogBuffer log;

    public StickerKeeper(ChatAgent agent, StickerStore store, ContentGuard guard) {
        this.agent = agent;
        this.store = store;
        this.guard = guard;
    }

    public void setLog(LogBuffer log) {
        this.log = log;
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stickers-retry");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(() -> store.pending(50).forEach(this::queue), 0, 10, TimeUnit.MINUTES);
    }

    public StickerStore store() {
        return store;
    }

    public Sticker noticed(String file, String url) {
        String hash = hashOf(file);
        if (hash == null) {
            return null;
        }
        Sticker sticker = store.noticed(hash, "seen", url == null ? "" : url);
        queue(sticker);
        return sticker;
    }

    public int importFavorites(List<String> urls) {
        int added = 0;
        for (String url : urls) {
            try {
                byte[] bytes = ImageLoader.fetch(url);
                String mime = ImageLoader.mime(bytes);
                if (mime == null) {
                    continue;
                }
                Sticker before = store.byHash(md5(bytes));
                Sticker sticker = store.noticed(md5(bytes), "favorite", url);
                if (sticker.file().isEmpty()) {
                    save(sticker, bytes, mime);
                }
                if (before == null || !before.source().equals("favorite")) {
                    added++;
                }
                queue(store.get(sticker.id()));
            } catch (AgentException | RuntimeException e) {
                warn("stickers: a favorite sticker could not be downloaded: " + e.getMessage());
            }
        }
        return added;
    }

    private void queue(Sticker sticker) {
        if (sticker == null || sticker.hidden() || !(sticker.status().equals(StickerStore.NEW) || sticker.status().equals(StickerStore.SAVED))) {
            return;
        }
        if (queued.add(sticker.id())) {
            worker.submit(() -> {
                try {
                    process(sticker.id());
                } catch (RuntimeException e) {
                    warn("stickers: sticker " + sticker.id() + " failed: " + e);
                } finally {
                    queued.remove(sticker.id());
                }
            });
        }
    }

    private void process(long id) {
        Sticker sticker = store.get(id);
        if (sticker == null) {
            return;
        }
        if (sticker.file().isEmpty()) {
            if (sticker.url().isEmpty()) {
                store.failed(id);
                return;
            }
            try {
                byte[] bytes = ImageLoader.fetch(sticker.url());
                String mime = ImageLoader.mime(bytes);
                if (mime == null) {
                    store.failed(id);
                    return;
                }
                save(sticker, bytes, mime);
            } catch (AgentException e) {
                store.failed(id);
                return;
            }
            sticker = store.get(id);
        }
        if (sticker.status().equals(StickerStore.SAVED) && allowDescribe()) {
            describe(sticker);
        }
    }

    private void save(Sticker sticker, byte[] bytes, String mime) {
        String name = sticker.hash().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "") + "." + mime.substring(mime.indexOf('/') + 1).replace("jpeg", "jpg");
        try {
            Files.createDirectories(store.dir());
            Files.write(store.dir().resolve(name), bytes);
            store.saved(sticker.id(), name, mime);
        } catch (IOException e) {
            warn("stickers: saving sticker " + sticker.id() + " failed: " + e.getMessage());
        }
    }

    private void describe(Sticker sticker) {
        ModelProfile profile = agent.store().active();
        if (profile == null || !agent.vision()) {
            return;
        }
        try {
            ImagePart image = ImageLoader.fromBytes(Files.readAllBytes(store.path(sticker)));
            ModelProtocol protocol = Protocols.get(profile.protocol());
            ChatRequest request = new ChatRequest(List.of(ChatMessage.user(DESCRIBE_PROMPT, List.of(image))), List.of(), false, "sticker", profile.maxTokens());
            String reply = new ModelRetry(StreamListener.NONE, () -> false).run(() -> protocol.stream(profile, request, StreamListener.NONE)).content();
            List<String> lines = reply.strip().lines().map(l -> l.replaceAll("[\\s\"“”'。.,，!！:：]|^第[一二]行", "")).filter(l -> !l.isEmpty()).toList();
            String text = lines.isEmpty() ? "" : lines.getFirst();
            boolean liked = lines.size() > 1 && lines.get(1).equals(LIKE);
            boolean unsafe = text.contains(UNSAFE_REPLY) || guard.scan(text) != null;
            store.described(sticker.id(), unsafe ? "" : text, unsafe ? StickerStore.UNSAFE : text.isEmpty() ? StickerStore.SAVED : StickerStore.READY);
            if (liked && !unsafe && !text.isEmpty() && store.collect(sticker.id()) && log != null) {
                log.info("stickers: collected sticker #" + sticker.id());
            }
        } catch (AgentException e) {
            if (e.kind() == AgentException.Kind.CONTENT_POLICY) {
                store.described(sticker.id(), "", StickerStore.UNSAFE);
            }
        } catch (IOException e) {
            store.failed(sticker.id());
        }
    }

    private synchronized boolean allowDescribe() {
        long now = System.currentTimeMillis();
        while (!described.isEmpty() && now - described.peekFirst() > 3_600_000) {
            described.pollFirst();
        }
        if (described.size() >= DESCRIBE_PER_HOUR) {
            return false;
        }
        described.addLast(now);
        return true;
    }

    private static String hashOf(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        String name = file.strip();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).toUpperCase(Locale.ROOT);
        return base.matches("[0-9A-F]{32}") ? base : "F" + md5(name.getBytes());
    }

    static String md5(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void warn(String message) {
        LogBuffer out = log;
        if (out != null) {
            out.warn(message);
        }
    }
}
