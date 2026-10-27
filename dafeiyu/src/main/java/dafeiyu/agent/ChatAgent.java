package dafeiyu.agent;

import dafeiyu.agent.context.Compaction;
import dafeiyu.agent.context.Compactor;
import dafeiyu.agent.context.ConversationMemory;
import dafeiyu.agent.context.HeldLines;
import dafeiyu.agent.image.ImageGen;
import dafeiyu.agent.image.VideoFrames;
import dafeiyu.agent.intercom.Intercom;
import dafeiyu.agent.memory.MemoryStore;
import dafeiyu.agent.memory.Mood;
import dafeiyu.agent.maintenance.Housekeeping;
import dafeiyu.agent.memory.Reflection;
import dafeiyu.agent.memory.SkillStore;
import dafeiyu.agent.model.AgentStore;
import dafeiyu.agent.model.Capabilities;
import dafeiyu.agent.model.ModelLimits;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.persona.PersonaStore;
import dafeiyu.agent.protocol.Protocols;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.agent.sticker.StickerKeeper;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.tool.Tool;
import dafeiyu.agent.tool.WorkspaceStore;
import dafeiyu.db.Database;
import dafeiyu.util.Clock;

import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class ChatAgent {

    private final AgentStore store;
    private final PersonaStore personas;
    private final WorkspaceStore workspaces;
    private final ConversationMemory memory;
    private final Compactor compactor;
    private final Capabilities capabilities;
    private final Mood mood;
    private final ContentGuard guard;
    private final MemoryStore memories;
    private final Reflection reflection;
    private final SkillStore skills;
    private volatile Extensions extensions = Extensions.NONE;
    private final StickerKeeper stickers;
    private final Intercom intercom;
    private final HeldLines held;
    private final Housekeeping housekeeping;
    private final ImageGen imageGen;
    private final VideoFrames videoFrames;
    private final BackgroundTasks tasks;
    private final SessionMonitor monitor = new SessionMonitor();
    private final Map<String, ReentrantLock> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> consumed = new ConcurrentHashMap<>();
    private volatile java.util.function.BiConsumer<String, List<String>> interjections = (session, lines) -> {};
    static final String CONTINUE = "（上面是刚发来的消息，接着回应）";

    public ChatAgent(Database db, AgentStore store, PersonaStore personas, WorkspaceStore workspaces) {
        this.store = store;
        this.personas = personas;
        this.workspaces = workspaces;
        this.memory = new ConversationMemory(db);
        this.compactor = new Compactor(memory);
        this.capabilities = new Capabilities(store);
        this.mood = new Mood(db);
        this.guard = new ContentGuard(db.file().toAbsolutePath().getParent().resolve("content-guard.txt"));
        this.memories = new MemoryStore(db, guard);
        this.skills = new SkillStore(db);
        this.reflection = new Reflection(this, memories, skills);
        this.stickers = new StickerKeeper(this, new StickerStore(db, db.file().toAbsolutePath().getParent().resolve("stickers")), guard);
        this.intercom = new Intercom(db);
        this.held = new HeldLines(db);
        this.tasks = new BackgroundTasks(this, db);
        this.imageGen = new ImageGen(db);
        this.videoFrames = new VideoFrames(db);
        this.housekeeping = new Housekeeping(db, store);
    }

    public Housekeeping housekeeping() {
        return housekeeping;
    }

    public VideoFrames videoFrames() {
        return videoFrames;
    }

    public ImageGen imageGen() {
        return imageGen;
    }

    public BackgroundTasks tasks() {
        return tasks;
    }

    public HeldLines held() {
        return held;
    }

    public Intercom intercom() {
        return intercom;
    }

    public StickerKeeper stickers() {
        return stickers;
    }

    public boolean vision() {
        ModelProfile profile = store.active();
        if (profile == null) {
            return false;
        }
        try {
            return capabilities.vision(profile, Protocols.get(profile.protocol()));
        } catch (AgentException e) {
            return false;
        }
    }

    public boolean nativeVideo() {
        ModelProfile profile = store.active();
        if (profile == null) {
            return false;
        }
        try {
            return capabilities.nativeVideo(profile, Protocols.get(profile.protocol()), videoFrames);
        } catch (AgentException e) {
            return false;
        }
    }

    public Boolean videoNative() {
        ModelProfile profile = store.active();
        return profile == null ? null : store.video(profile.id());
    }

    public ContentGuard guard() {
        return guard;
    }

    public Mood mood() {
        return mood;
    }

    public MemoryStore memories() {
        return memories;
    }

    public Reflection reflection() {
        return reflection;
    }

    public SkillStore skills() {
        return skills;
    }

    public Extensions extensions() {
        return extensions;
    }

    public void setExtensions(Extensions extensions) {
        this.extensions = extensions == null ? Extensions.NONE : extensions;
    }

    public AgentStore store() {
        return store;
    }

    public PersonaStore personas() {
        return personas;
    }

    public WorkspaceStore workspaces() {
        return workspaces;
    }

    public SessionMonitor monitor() {
        return monitor;
    }

    public record Compacted(long before, long after) {}

    public record Errand(String session, String who, String userId, String task) {}

    private volatile java.util.function.Consumer<Errand> errands = e -> {};

    public void setErrands(java.util.function.Consumer<Errand> errands) {
        this.errands = errands;
    }

    public interface ForwardReader {

        String read(String id) throws dafeiyu.agent.tool.ToolFailure;

        default dafeiyu.agent.tool.ToolResult readWithImages(String id, int maxImages)
                throws dafeiyu.agent.tool.ToolFailure {
            return dafeiyu.agent.tool.ToolResult.text(read(id));
        }
    }

    private volatile ForwardReader forwards = id -> {
        throw new dafeiyu.agent.tool.ToolFailure("QQ 没连上，读不了合并转发");
    };

    public void setForwardReader(ForwardReader forwards) {
        this.forwards = forwards;
    }

    String readForward(String id) throws dafeiyu.agent.tool.ToolFailure {
        return forwards.read(id);
    }

    dafeiyu.agent.tool.ToolResult readForwardWithImages(String id) throws dafeiyu.agent.tool.ToolFailure {
        return vision() ? forwards.readWithImages(id, 6) : dafeiyu.agent.tool.ToolResult.text(forwards.read(id));
    }

    private volatile java.util.function.Consumer<String> subagentResults = text -> intercom().report(BackgroundTasks.MIND, text);

    public void setSubagentResults(java.util.function.Consumer<String> handler) {
        this.subagentResults = handler;
    }

    void subagentReturned(String text) {
        try {
            subagentResults.accept(text);
        } catch (RuntimeException ignored) {
        }
    }

    public interface ImageSource {

        byte[] load(long id) throws dafeiyu.agent.tool.ToolFailure;

        default String sessionOf(long id) {
            return "";
        }
    }

    private volatile ImageSource images = id -> {
        throw new dafeiyu.agent.tool.ToolFailure("QQ 没连上，取不到图片#" + id);
    };

    public void setImageSource(ImageSource images) {
        this.images = images;
    }

    byte[] image(long id) throws dafeiyu.agent.tool.ToolFailure {
        return images.load(id);
    }

    String imageSession(long id) {
        try {
            return images.sessionOf(id);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private volatile java.util.function.BiFunction<String, Boolean, String> elsewhere = (session, details) -> "";

    public void setElsewhere(java.util.function.BiFunction<String, Boolean, String> elsewhere) {
        this.elsewhere = elsewhere;
    }

    String elsewhere(String session, boolean details) {
        try {
            return elsewhere.apply(session, details);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private volatile java.util.function.Supplier<List<Tool>> ownerTools = List::of;

    public void setOwnerTools(java.util.function.Supplier<List<Tool>> ownerTools) {
        this.ownerTools = ownerTools;
    }

    List<Tool> ownerTools() {
        try {
            return ownerTools.get();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private volatile java.util.function.Function<String, List<Tool>> sessionTools = session -> List.of();

    public void setSessionTools(java.util.function.Function<String, List<Tool>> sessionTools) {
        this.sessionTools = sessionTools;
    }

    public List<Tool> sessionTools(String session) {
        try {
            return sessionTools.apply(session);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private volatile java.util.function.BinaryOperator<String> poker = (session, userId) -> null;

    public void setPoker(java.util.function.BinaryOperator<String> poker) {
        this.poker = poker;
    }

    String poke(String session, String userId) {
        try {
            return poker.apply(session, userId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    void errand(Errand errand) {
        try {
            errands.accept(errand);
        } catch (RuntimeException ignored) {
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(TurnRunner runner) throws AgentException;
    }

    public ChatResult chat(ChatTurn turn, StreamListener listener) throws AgentException {
        ChatTurn actual = turn;
        long heardId = -1;
        if (!turn.heard() && turn.user().images().isEmpty() && interjectable(turn.session()) && busy(turn.session())) {
            heardId = hear(turn.session(), turn.user().content(), turn.qqUser() == null ? "" : turn.qqUser(), turn.attachments());
            actual = turn.asHeard(ChatMessage.user(CONTINUE));
        }
        long id = heardId;
        ChatResult result = locked(actual, listener, runner -> id >= 0 && takeConsumed(turn.session(), id)
                ? new ChatResult("", "", List.of(), "", 0, 0, 0, 0, "merged") : runner.run(), r -> r);
        reflection.touched(turn.session());
        return result;
    }

    static boolean interjectable(String session) {
        return session.startsWith("group:") || session.startsWith("private:") || session.startsWith("web:") || session.startsWith("task:");
    }

    public boolean busy(String session) {
        ReentrantLock lock = sessions.get(session);
        return lock != null && lock.isLocked() && !lock.isHeldByCurrentThread();
    }

    public void setInterjections(java.util.function.BiConsumer<String, List<String>> handler) {
        this.interjections = handler;
    }

    void interjected(String session, List<ConversationMemory.Heard> heard) {
        consumed.computeIfAbsent(session, k -> ConcurrentHashMap.newKeySet()).addAll(heard.stream().map(ConversationMemory.Heard::id).toList());
        try {
            interjections.accept(session, heard.stream().map(ConversationMemory.Heard::content).toList());
        } catch (RuntimeException ignored) {
        }
    }

    private boolean takeConsumed(String session, long id) {
        Set<Long> ids = consumed.get(session);
        return ids != null && ids.remove(id);
    }

    public Compacted compact(String session) throws AgentException {
        ChatTurn turn = new ChatTurn(session, ChatMessage.user("/compact"), null, null, "command");
        return locked(turn, StreamListener.NONE, runner -> {
            long[] sizes = runner.compactNow();
            return new Compacted(sizes[0], sizes[1]);
        }, c -> null);
    }

    public void hear(String session, String line) {
        hear(session, line, "");
    }

    public void hear(String session, String line, String userId) {
        hear(session, line, userId, "");
    }

    public long hear(String session, String line, String userId, String attachments) {
        long now = System.currentTimeMillis();
        if (!session.startsWith("mind:") && Clock.gap(memory.lastAt(session), now)) {
            String marker = Clock.marker(now);
            memory.hear(session, ChatMessage.user(marker), Compaction.estimate(marker), "");
            monitor.heard(session, marker, "", "");
        }
        long id = memory.hear(session, ChatMessage.user(line), Compaction.estimate(line), attachments);
        monitor.heard(session, line, userId, attachments);
        return id;
    }

    public void record(String session, ChatMessage message) {
        ReentrantLock lock = sessions.computeIfAbsent(session, k -> new ReentrantLock());
        lock.lock();
        try {
            memory.appendKeepingMeasure(session, List.of(message));
        } finally {
            lock.unlock();
        }
        if (message.isAssistant()) {
            monitor.said(session, message.content());
        }
    }

    private <T> T locked(ChatTurn turn, StreamListener listener, Work<T> work, java.util.function.Function<T, ChatResult> asResult)
            throws AgentException {
        SessionMonitor.Turn watch = monitor.begin(turn);
        T result = null;
        AgentException failure = null;
        try {
            ReentrantLock lock = sessions.computeIfAbsent(turn.session(), k -> new ReentrantLock());
            try {
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                throw AgentException.cancelled();
            }
            try {
                monitor.running(watch);
                result = work.run(new TurnRunner(this, memory, compactor, capabilities, turn, monitor.listener(watch, listener), watch::cancelled));
                return result;
            } finally {
                lock.unlock();
            }
        } catch (AgentException e) {
            failure = watch.cancelled() ? AgentException.cancelled() : e;
            throw failure;
        } catch (RuntimeException e) {
            if (watch.cancelled() && !(e instanceof UncheckedIOException)) {
                failure = AgentException.cancelled();
                throw failure;
            }
            failure = new AgentException(AgentException.Kind.LOCAL, String.valueOf(e));
            throw e;
        } finally {
            monitor.end(watch, result == null ? null : asResult.apply(result), failure);
            if (watch.cancelled()) {
                Thread.interrupted();
            }
        }
    }

    public int stop(String session) {
        return monitor.stop(session);
    }

    public List<SessionSummary> sessions() {
        return memory.sessions();
    }

    public SessionDetail session(String session, int limit) {
        return memory.detail(session, limit);
    }

    public SessionDetail session(String session, int limit, long before) {
        return memory.detail(session, limit, before);
    }

    public long contextTokens(String session) {
        ConversationMemory.Conversation conversation = memory.load(session);
        return conversation.measuredTokens() > 0 ? conversation.measuredTokens() : Compaction.estimate(Prompt.pruned(Prompt.history(conversation, true)));
    }

    public int contextWindow() {
        return limits().context();
    }

    private ModelLimits.Limits limits() {
        ModelProfile profile = store.active();
        return profile == null ? new ModelLimits.Limits(0, 0) : ModelLimits.resolve(profile);
    }

    public long compactAt(String session) {
        long at = Compaction.threshold(limits());
        return at == Long.MAX_VALUE ? 0 : at;
    }

    public void reset(String session) {
        monitor.stop(session);
        ReentrantLock lock = sessions.computeIfAbsent(session, k -> new ReentrantLock());
        lock.lock();
        try {
            memory.clear(session);
            reflection.forget(session);
        } finally {
            lock.unlock();
        }
    }

    public boolean probeVision(String profileId) throws AgentException {
        ModelProfile profile = store.get(profileId);
        if (profile == null) {
            throw new AgentException("model profile not found");
        }
        return capabilities.probeVision(profile, Protocols.get(profile.protocol()));
    }

    public List<String> listModels(String protocol, String baseUrl, String apiKey, String profileId) throws AgentException {
        String url = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty() && profileId != null) {
            ModelProfile saved = store.get(profileId);
            if (saved != null && sameServer(saved.baseUrl(), url)) {
                key = saved.apiKey();
            }
        }
        return Protocols.get(protocol).listModels(url, key);
    }

    private static boolean sameServer(String a, String b) {
        try {
            URI x = URI.create(a);
            URI y = URI.create(b);
            return x.getHost() != null && x.getHost().equalsIgnoreCase(y.getHost())
                    && String.valueOf(x.getScheme()).equalsIgnoreCase(String.valueOf(y.getScheme()))
                    && x.getPort() == y.getPort();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
