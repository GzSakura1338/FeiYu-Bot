package dafeiyu.agent;

import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.context.Compaction;
import dafeiyu.agent.context.Compactor;
import dafeiyu.agent.context.Compactor.Trigger;
import dafeiyu.agent.context.ConversationMemory;
import dafeiyu.agent.context.ConversationMemory.Conversation;
import dafeiyu.agent.memory.MemoryStore;
import dafeiyu.agent.memory.Mood;
import dafeiyu.agent.reply.Markup;
import dafeiyu.agent.memory.Reflection;
import dafeiyu.agent.model.Capabilities;
import dafeiyu.agent.model.ModelLimits;
import dafeiyu.agent.model.ModelLimits.Limits;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.model.ModelRetry;
import dafeiyu.agent.model.VisionProbe;
import dafeiyu.agent.persona.Persona;
import dafeiyu.agent.qq.QqFaces;
import dafeiyu.agent.qq.QqText;
import dafeiyu.agent.safety.ContentGuard;
import dafeiyu.agent.sticker.StickerKeeper;
import dafeiyu.agent.sticker.StickerStore;
import dafeiyu.agent.protocol.ChatRequest;
import dafeiyu.agent.protocol.ModelProtocol;
import dafeiyu.agent.protocol.Protocols;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.tool.ToolContext;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.agent.tool.Workspace;
import dafeiyu.agent.tool.WorkspaceStore;
import dafeiyu.util.Clock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class TurnRunner {

    static final int MAX_STEPS = 1000;
    private static final int DOOM_LOOP = 3;
    private static final int FACE_COOLDOWN = 2;
    private static final int MAX_NOTES = 2;
    private static final int STICKER_CATALOG = 30;
    private static final int STICKER_NUDGE = 4;
    private static final int MAX_ERRANDS = 2;
    public static final String SILENCE = "[沉默]";
    public static final String AMBIENT = "【旁听】";

    private final ChatAgent agent;
    private final ConversationMemory memory;
    private final Compactor compactor;
    private final Capabilities capabilities;
    private final ChatTurn turn;
    private final StreamListener listener;
    private final BooleanSupplier cancelled;
    private final ModelProfile profile;
    private final ModelProtocol protocol;
    private final Limits limits;
    private final String measuredBy;
    private final String effort;
    private final Persona persona;
    private final Mood mood;
    private final ContentGuard guard;
    private final StickerKeeper stickers;
    private final MemoryStore memories;
    private final Reflection reflection;
    private final List<String> owners;
    private final Workspace workspace;
    private List<String> enabled;
    private List<ToolSpec> tools;
    private final int maxSteps;
    private final Deque<String> recentCalls = new ArrayDeque<>();
    private String contributed;
    private String system;
    private boolean systemRole;
    private Conversation conversation;
    private final List<ChatMessage> live = new ArrayList<>();
    private boolean userSaved;
    private ChatMessage marker;
    private long startedAt;
    private long lastSpoke;
    private long measured;

    TurnRunner(ChatAgent agent, ConversationMemory memory, Compactor compactor, Capabilities capabilities,
               ChatTurn turn, StreamListener listener, BooleanSupplier cancelled) throws AgentException {
        this.agent = agent;
        this.memory = memory;
        this.compactor = compactor;
        this.capabilities = capabilities;
        this.turn = turn;
        this.listener = listener;
        this.cancelled = cancelled;
        this.profile = agent.store().active();
        if (profile == null) {
            throw new AgentException(Kind.LOCAL, "no model selected: add a model on the agent page first");
        }
        this.protocol = Protocols.get(profile.protocol());
        this.limits = ModelLimits.resolve(profile);
        this.measuredBy = profile.id() + "/" + profile.model();
        this.effort = agent.store().reasoningEffort();
        this.persona = agent.personas().active();
        this.mood = agent.mood();
        this.guard = agent.guard();
        this.stickers = agent.stickers();
        this.memories = agent.memories();
        this.reflection = agent.reflection();
        this.owners = agent.personas().owners();
        WorkspaceStore workspaces = agent.workspaces();
        this.workspace = workspaces.workspace();
        if (turn.tools() != null) {
            this.tools = capabilities.toolCall(profile) ? turn.tools().specs() : List.of();
            this.enabled = tools.stream().map(ToolSpec::name).toList();
        } else {
            boolean allowed = !ambient() && workspaces.allows(turn.qqUser()) && capabilities.toolCall(profile);
            Set<String> chosen = allowed ? Set.copyOf(workspaces.config().enabled()) : Set.of();
            List<String> builtin = workspace == null ? List.of()
                    : chosen.stream().filter(Toolbox::exists).filter(n -> turn.files() != null || !n.equals("send_file")).toList();
            List<ToolSpec> all = new ArrayList<>(Toolbox.specs(Set.copyOf(builtin)));
            if (allowed) {
                all.addAll(agent.extensions().tools(qqSession(), false, chosen));
            }
            this.tools = List.copyOf(all);
            this.enabled = tools.stream().map(ToolSpec::name).toList();
        }
        this.maxSteps = turn.tools() == null ? MAX_STEPS : Math.min(MAX_STEPS, turn.tools().maxSteps());
        buildSystem();
    }

    ChatResult run() throws AgentException {
        long started = System.nanoTime();
        conversation = memory.load(turn.session());
        measured = conversation.measuredTokens() > 0 && measuredBy.equals(conversation.measuredBy()) ? conversation.measuredTokens() : -1;
        live.add(prepareImages(turn.user()));
        userSaved = turn.heard();
        long now = System.currentTimeMillis();
        startedAt = now;
        marker = !turn.heard() && !turn.mind() && Clock.gap(memory.lastAt(turn.session()), now) ? ChatMessage.user(Clock.marker(now)) : null;
        lastSpoke = innerVoice() ? memory.lastSpoke(turn.session()) : 0;
        boolean recoverOverflow = true;
        int completion = 0;
        int step = 1;
        while (true) {
            checkCancelled();
            if (hasHistory() && estimateContext() >= Compaction.threshold(limits.context())) {
                compact(Trigger.AUTO);
            }
            boolean lastStep = step >= maxSteps;
            ChatRequest request = request(lastStep);
            ChatResult result;
            try {
                result = new ModelRetry(listener, cancelled).run(() -> protocol.stream(profile, request, listener));
            } catch (AgentException e) {
                checkCancelled();
                if (e.isContextOverflow() && recoverOverflow && hasHistory()) {
                    recoverOverflow = false;
                    compact(Trigger.OVERFLOW);
                    continue;
                }
                if (hasLiveImages() && capabilities.rejectsImages(profile, e)) {
                    live.set(0, live.getFirst().withoutImages(VisionProbe.UNSUPPORTED));
                    continue;
                }
                if (!tools.isEmpty() && capabilities.rejectsTools(profile, e)) {
                    enabled = List.of();
                    tools = List.of();
                    buildSystem();
                    continue;
                }
                throw e;
            }
            completion += Math.max(0, result.completionTokens());
            List<ToolCall> calls = lastStep ? List.of() : result.toolCalls();
            if (calls.isEmpty() && result.content().isEmpty()) {
                throw new AgentException(Kind.EMPTY_REPLY, "reached the maximum of " + maxSteps + " steps without a final reply");
            }
            Markup markup = innerVoice() ? Markup.parse(result.content()) : Markup.plain(result.content());
            String thought = feel(markup.thought());
            String content = outgoing(markup.said());
            List<String> tasks = markup.errands().stream().filter(t -> guard.scan(t) == null).limit(MAX_ERRANDS).toList();
            boolean acts = markup.poke() != null || !tasks.isEmpty() || !markup.promises().isEmpty() || !markup.done().isEmpty();
            if (calls.isEmpty() && (ambient() || innerVoice()) && silent(content) && !acts) {
                return silence(result, started);
            }
            boolean blocked = false;
            if (calls.isEmpty() && !turn.mind() && !content.isBlank()) {
                ContentGuard.Verdict verdict = guard.review(profile, reviewContext(), content);
                if (verdict != null) {
                    blocked = true;
                    content = ambient() || "note".equals(turn.source()) ? "" : ContentGuard.deflection();
                    listener.onBlocked(verdict.category().label(), verdict.byModel(), content);
                    if (content.isEmpty()) {
                        return silence(result, started);
                    }
                }
            }
            List<Markup.Note> notes = blocked ? List.of() : remember(markup.notes());
            String poked = blocked ? null : poke(markup.poke());
            List<String> errands = blocked ? List.of() : tasks;
            for (String task : errands) {
                agent.errand(new ChatAgent.Errand(turn.session(), requester(), turn.qqUser() == null ? "" : turn.qqUser(), task));
            }
            List<String> promises = blocked || turn.mind() ? List.of() : markup.promises().stream().filter(p -> guard.scan(p) == null).limit(MAX_ERRANDS).toList();
            List<String> finished = blocked || turn.mind() ? List.of() : markup.done().stream().limit(MAX_ERRANDS).toList();
            for (String promise : promises) {
                agent.intercom().commit(turn.session(), requester(), promise);
            }
            for (String done : finished) {
                agent.intercom().finish(turn.session(), done);
            }
            String stored = new Markup(thought, content, notes, errands, poked, promises, finished).render();
            List<ChatMessage> fresh = new ArrayList<>();
            fresh.add(ChatMessage.assistant(stored, result.reasoning(), calls));
            for (ToolCall call : calls) {
                fresh.add(runTool(call));
            }
            save(fresh, result);
            if (calls.isEmpty()) {
                long latencyMs = (System.nanoTime() - started) / 1_000_000;
                return new ChatResult(content, result.reasoning(), List.of(), result.model(), latencyMs,
                        result.promptTokens(), completion > 0 ? completion : result.completionTokens(), result.cachedTokens(),
                        result.finishReason());
            }
            step++;
        }
    }

    long[] compactNow() throws AgentException {
        conversation = memory.load(turn.session());
        if (conversation.isEmpty()) {
            throw new AgentException(Kind.LOCAL, "nothing to compact yet");
        }
        measured = conversation.measuredTokens() > 0 && measuredBy.equals(conversation.measuredBy()) ? conversation.measuredTokens() : -1;
        long before = estimateContext();
        compact(Trigger.MANUAL);
        return new long[] {before, estimateContext()};
    }

    private ChatRequest request(boolean lastStep) {
        List<ChatMessage> messages = new ArrayList<>(Prompt.head(system, systemRole));
        messages.addAll(Prompt.history(conversation, true));
        messages.addAll(live);
        if (lastStep && !tools.isEmpty()) {
            messages.add(ChatMessage.user(turn.mind() ? Prompt.MIND_STEPS : Prompt.MAX_STEPS));
        }
        List<ChatMessage> prepared = Prompt.mergeUsers(Prompt.forRequest(messages, !tools.isEmpty()));
        String skills = persona == null || turn.mind() ? "" : agent.skills().relevant(persona.id(), turn.user().content());
        if (contributed == null) {
            contributed = turn.mind() ? "" : agent.extensions().contribute(turn.session(), turn.people());
        }
        skills += contributed;
        if (!innerVoice() && !skills.isEmpty()) {
            prepared = Prompt.prefixLastUser(prepared, skills);
        }
        if (innerVoice()) {
            String catalog = stickers.store().note(STICKER_CATALOG);
            if (!catalog.isEmpty()) {
                int since = sinceSticker();
                if (since < FACE_COOLDOWN) {
                    catalog += "（你刚发过表情包，这次先不发）\n";
                } else if (since >= STICKER_NUDGE) {
                    catalog += "（你已经 " + since + " 条消息没发表情包了，这次情绪合适的话就配一张）\n";
                }
            }
            prepared = Prompt.prefixLastUser(prepared, mood.note(persona.id(), turn.session(), lastSpoke)
                    + memories.recall(persona.id(), turn.session(), turn.people(), Set.copyOf(owners))
                    + skills
                    + (catalog.isEmpty() ? "" : "<你的表情包>\n" + catalog + "</你的表情包>\n\n"));
        }
        if (!systemRole) {
            prepared = Prompt.withInstructions(prepared, system);
        }
        return new ChatRequest(prepared, tools, lastStep, turn.session(), profile.maxTokens(), effort);
    }

    private boolean ambient() {
        return "ambient".equals(turn.source());
    }

    private String feel(String thought) {
        if (thought.isEmpty() || guard.scan(thought) != null) {
            return "";
        }
        mood.remember(persona.id(), thought, turn.session());
        return thought;
    }

    private String poke(String who) {
        if (who == null) {
            return null;
        }
        String qq = pokeTarget(who);
        String name = qq == null ? null : agent.poke(turn.session(), qq);
        return name == null ? null : who.isEmpty() ? name : who;
    }

    private List<Markup.Note> remember(List<Markup.Note> notes) {
        if (notes.isEmpty() || !innerVoice()) {
            return List.of();
        }
        Set<String> ownerSet = Set.copyOf(owners);
        List<Markup.Note> kept = new ArrayList<>();
        for (Markup.Note note : notes) {
            String scope = noteScope(note.about());
            if (kept.size() >= MAX_NOTES || scope == null || guard.scan(note.text()) != null
                    || Reflection.claimsOwner(scope, note.text(), ownerSet) || memories.knows(persona.id(), scope, note.text())) {
                continue;
            }
            try {
                memories.add(persona.id(), scope, noteLabel(scope), note.text(), turn.session());
                kept.add(note);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return kept;
    }

    private String noteScope(String about) {
        String name = about.strip();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean group = turn.session().startsWith("group:");
        if (name.isEmpty()) {
            if (!group) {
                return "user:" + turn.session().substring(turn.session().indexOf(':') + 1);
            }
            return turn.qqUser() != null && present(turn.qqUser()) ? "user:" + turn.qqUser() : MemoryStore.SELF;
        }
        if (lower.equals("self") || name.equals("自己") || name.equals("我")) {
            return MemoryStore.SELF;
        }
        if (lower.equals("group") || name.equals("群") || name.equals("这个群")) {
            return group ? turn.session() : null;
        }
        if (name.equals("主人")) {
            return owners.stream().filter(this::present).findFirst().map(qq -> "user:" + qq).orElse(null);
        }
        String qq = lower.startsWith("user:") ? name.substring(5).strip() : name;
        if (!qq.matches("\\d{5,12}")) {
            qq = turn.roster().get(name);
            if (qq == null) {
                qq = turn.roster().entrySet().stream().filter(e -> e.getKey().replaceFirst("#\\d{4}$", "").equals(name))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }
        }
        return qq != null && present(qq) ? "user:" + qq : null;
    }

    private boolean present(String qq) {
        return turn.people().contains(qq) || turn.roster().containsValue(qq) || turn.session().equals("private:" + qq);
    }

    private String noteLabel(String scope) {
        if (!scope.startsWith("user:")) {
            return "";
        }
        String qq = scope.substring(5);
        return turn.roster().entrySet().stream().filter(e -> e.getValue().equals(qq))
                .map(e -> e.getKey().replaceFirst("#\\d{4}$", "")).findFirst().orElse("");
    }

    private String reviewContext() {
        if (!turn.heard()) {
            return turn.user().content();
        }
        List<String> recent = new ArrayList<>();
        List<ChatMessage> turns = conversation.turns();
        for (int i = turns.size() - 1; i >= 0 && recent.size() < 4 && turns.get(i).isUser(); i--) {
            recent.addFirst(turns.get(i).content());
        }
        return String.join("\n", recent);
    }

    private static ChatResult silence(ChatResult result, long started) {
        return new ChatResult("", "", List.of(), result.model(), (System.nanoTime() - started) / 1_000_000,
                result.promptTokens(), result.completionTokens(), result.cachedTokens(), "silent");
    }

    private static boolean silent(String content) {
        String text = content.strip();
        return text.isEmpty() || text.contains(SILENCE) || text.equals("沉默");
    }

    private boolean qqSession() {
        return turn.session().startsWith("group:") || turn.session().startsWith("private:");
    }

    private boolean innerVoice() {
        return qqSession() && persona != null;
    }


    private String outgoing(String content) {
        if (!qqSession()) {
            return content;
        }
        String text = QqFaces.limit(QqText.format(content), recent(QqFaces::contains));
        StickerStore store = stickers.store();
        return StickerStore.limit(text, recent(StickerStore::hasTag), id -> {
            StickerStore.Sticker sticker = store.get(id);
            return sticker != null && sticker.usable();
        });
    }

    private String pokeTarget(String who) {
        String name = Set.of("对方", "你", "他", "她", "TA", "ta").contains(who) ? "" : who;
        String scope = noteScope(name);
        return scope != null && scope.startsWith("user:") ? scope.substring(5) : null;
    }

    private String requester() {
        String qq = turn.qqUser();
        if (qq == null || qq.isBlank() || qq.equals("0")) {
            return turn.session().startsWith("private:") ? "私聊里的 QQ " + turn.session().substring(8) : "群里的人";
        }
        String name = turn.roster().entrySet().stream().filter(e -> e.getValue().equals(qq))
                .map(e -> e.getKey().replaceFirst("#\\d{4}$", "")).findFirst().orElse("");
        return (owners.contains(qq) ? "主人 " : "") + (name.isEmpty() ? "QQ " + qq : name + "（QQ " + qq + "）");
    }

    private int sinceSticker() {
        List<ChatMessage> all = new ArrayList<>(conversation.turns());
        all.addAll(live);
        int count = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            ChatMessage m = all.get(i);
            if (m.isAssistant() && !m.content().isBlank()) {
                if (StickerStore.hasTag(m.content())) {
                    return count;
                }
                count++;
            }
        }
        return count;
    }

    private boolean recent(Predicate<String> used) {
        List<ChatMessage> all = new ArrayList<>(conversation.turns());
        all.addAll(live);
        int seen = 0;
        for (int i = all.size() - 1; i >= 0 && seen < FACE_COOLDOWN; i--) {
            ChatMessage m = all.get(i);
            if (m.isAssistant() && !m.content().isBlank()) {
                if (used.test(m.content())) {
                    return true;
                }
                seen++;
            }
        }
        return false;
    }

    private void checkCancelled() throws AgentException {
        if (cancelled.getAsBoolean()) {
            throw AgentException.cancelled();
        }
    }

    private ChatMessage runTool(ToolCall call) {
        if (cancelled.getAsBoolean()) {
            return ChatMessage.tool(call, "Tool call cancelled: the turn was stopped from the console.", true);
        }
        listener.onToolCall(call);
        Toolbox.Outcome outcome = turn.tools() != null ? turn.tools().run(call)
                : agent.extensions().owns(call.name()) && enabled.contains(call.name()) ? agent.extensions().run(call, turn.session())
                : Toolbox.run(call, Set.copyOf(enabled), new ToolContext(workspace, turn.files()));
        listener.onToolResult(call, outcome.output(), outcome.error());
        String output = outcome.output();
        if (repeating(call)) {
            output += "\n\n[You have now made this exact same " + call.name() + " call " + DOOM_LOOP
                    + " times in a row. Repeating it will not change the result: try a different approach, or stop and tell the user where you are stuck.]";
        }
        return ChatMessage.tool(call, output, outcome.error());
    }

    private boolean repeating(ToolCall call) {
        String signature = call.name() + "\n" + call.arguments();
        recentCalls.addLast(signature);
        while (recentCalls.size() > DOOM_LOOP) {
            recentCalls.removeFirst();
        }
        return recentCalls.size() == DOOM_LOOP && recentCalls.stream().allMatch(signature::equals);
    }

    private void save(List<ChatMessage> fresh, ChatResult result) {
        List<ChatMessage> asked = new ArrayList<>();
        if (!userSaved) {
            if (marker != null) {
                asked.add(marker);
            }
            asked.add(ChatMessage.user(turn.user().content()));
        }
        measured = result.promptTokens() > 0 ? result.promptTokens() + Compaction.estimate(fresh) : -1;
        memory.append(turn.session(), asked, startedAt, turn.attachments(), fresh, (int) Math.max(0, measured), measured > 0 ? measuredBy : "");
        userSaved = true;
        live.addAll(fresh);
        listener.onSaved();
    }

    private void compact(Trigger trigger) throws AgentException {
        reflection.beforeCompaction(turn.session());
        Conversation current = userSaved ? memory.load(turn.session()) : conversation;
        long estimate = estimateContext();
        conversation = compactor.compact(trigger, new Compactor.Target(turn.session(), profile, protocol, limits, tools),
                Prompt.head(system, systemRole), current, estimate, listener, new ModelRetry(listener, cancelled));
        reflection.markReflected(turn.session());
        if (userSaved) {
            ChatMessage instructions = turn.heard() && !live.isEmpty() ? live.getFirst() : null;
            live.clear();
            if (instructions != null) {
                live.add(instructions);
            }
        }
        measured = -1;
    }

    private long estimateContext() {
        if (measured >= 0) {
            return measured + (userSaved || live.isEmpty() ? 0 : Compaction.estimate(live.getFirst()));
        }
        List<ChatMessage> all = new ArrayList<>(Prompt.head(system, systemRole));
        all.addAll(Prompt.history(conversation, true));
        all.addAll(live);
        return Compaction.estimate(all) + (systemRole ? 0 : Compaction.estimate(system));
    }

    private boolean hasHistory() {
        return !conversation.isEmpty() || userSaved;
    }

    private boolean hasLiveImages() {
        return !live.isEmpty() && live.getFirst().isUser() && !live.getFirst().images().isEmpty();
    }

    private ChatMessage prepareImages(ChatMessage user) {
        if (user.images().isEmpty() || capabilities.vision(profile, protocol)) {
            return user;
        }
        return user.withoutImages(VisionProbe.UNSUPPORTED);
    }

    private void buildSystem() {
        system = turn.mind() ? Prompt.mind(persona) : Prompt.system(persona, workspace, enabled, qqSession());
        String learned = persona == null ? "" : agent.skills().prompt(persona.id());
        if (!learned.isEmpty()) {
            system = system.isEmpty() ? learned : system + "\n\n" + learned;
        }
        systemRole = system.isEmpty() || capabilities.systemRole(profile, protocol);
    }
}
