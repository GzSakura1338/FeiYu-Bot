package dafeiyu.agent;

import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.util.Jsons;
import dafeiyu.agent.context.Compaction;
import dafeiyu.agent.context.Compactor;
import dafeiyu.agent.context.Compactor.Trigger;
import dafeiyu.agent.context.ConversationMemory;
import dafeiyu.agent.context.ConversationMemory.Conversation;
import dafeiyu.agent.image.ImageTool;
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
import dafeiyu.agent.qq.QqApiTools;
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
import dafeiyu.agent.tool.ToolResult;
import dafeiyu.agent.tool.ToolSet;
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
    private static final long REVISE_WITHIN_MS = 12_000;
    public static final String OVERHEARD = "overheard";
    private static final String ADDRESS_HINT = "在群里回话时，回的是谁就在开头 @ 谁（比如“@不知道呀 …”），几个人一起回就分别 @，这样才不会让人以为你在回别人";
    private static final int DOOM_LOOP = 3;
    private static final String SEARCH = "websearch";
    private static final Set<String> HANDS_ON = Set.of("write", "edit", "patch", "move", "delete", "shell");
    private static final java.util.regex.Pattern SENT_FILE = java.util.regex.Pattern.compile("文件：([^。；\\n]+)");
    private static final int CONTEXT_LINES = 16;
    private static final int FACE_COOLDOWN = 2;
    private static final int MAX_NOTES = 2;
    private static final int STICKER_CATALOG = 40;
    private static final int STICKER_NUDGE = 4;
    private static final int MAX_ERRANDS = 2;
    private static final int MAX_VIEWS = 6;
    /** 「拿到图但这一轮没别的工具调用」最多再多走几轮，防止无限循环。 */
    private static final int MAX_LOOK_AGAIN = 3;
    /** 本轮是否刚有新图塞进 live（只在那一刻为真，避免每轮都重试）。 */
    private boolean lookedThisStep;
    private static final String VIEWED = "（这是你刚才用 view_image 要看的图）";
    public static final String SILENCE = "[沉默]";
    private static final java.util.regex.Pattern SILENT = java.util.regex.Pattern.compile("[\\[［【<＜(（]\\s*/?\\s*沉默\\s*/?\\s*[\\]］】>＞)）]");
    public static final String AMBIENT = "【旁听】";
    private static final java.util.regex.Pattern GIVE_UP = java.util.regex.Pattern.compile(
            "做不了|做不到|办不到|帮不了|没办法|无法|打不开|看不了|看不到|听不了|发不了|画不了|查不了|搜不了|用不了|弄不了|搞不了|点不了|点不下去"
                    + "|不支持|没有这个功能|没这个功能|没有这个工具|没这个工具|没这件工具|过期了|拿不到|读不到|没权限|没有权限|权限不够"
                    + "|我不会(?:弄|做|画|看|发|查|用|打开|这个)");

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
    private final boolean network;
    private final boolean direct;
    private ToolSet toolSet;
    private List<String> enabled;
    private List<ToolSpec> tools;
    private final int maxSteps;
    private final Deque<String> recentCalls = new ArrayDeque<>();
    private String contributed;
    private String chatContext;
    private String presentPeople;
    private String system;
    private boolean systemRole;
    private Conversation conversation;
    private final List<ChatMessage> live = new ArrayList<>();
    private final List<ConversationMemory.Heard> arrived = new ArrayList<>();
    private final List<String> sentFiles = new ArrayList<>();
    private final List<ImagePart> shown = new ArrayList<>();
    private int views;
    private long heardId;
    private boolean revised;
    private boolean overheard;
    private boolean triedTools;
    private boolean pushedToTry;
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
        this.profile = subagent() ? subagentProfile(agent) : agent.store().active();
        if (profile == null) {
            throw new AgentException(Kind.LOCAL, "no model selected: add a model on the agent page first");
        }
        this.protocol = Protocols.get(profile.protocol());
        this.limits = ModelLimits.resolve(profile);
        this.measuredBy = profile.id() + "/" + profile.model();
        this.effort = agent.store().reasoningEffort();
        this.persona = subagent() ? null : agent.personas().active();
        this.mood = agent.mood();
        this.guard = agent.guard();
        this.stickers = agent.stickers();
        this.memories = agent.memories();
        this.reflection = agent.reflection();
        this.owners = agent.personas().owners();
        WorkspaceStore workspaces = agent.workspaces();
        this.workspace = workspaces.workspace();
        this.network = workspaces.config().shellNetwork();
        this.direct = !subagent() && workspaces.directShell(turn.qqUser(), owners);
        this.toolSet = !capabilities.toolCall(profile) ? ToolSet.none() : turn.tools() != null ? turn.tools() : defaultTools(workspaces);
        this.tools = toolSet.specs();
        this.enabled = toolSet.names();
        this.maxSteps = turn.tools() == null ? MAX_STEPS : Math.min(MAX_STEPS, turn.tools().maxSteps());
        buildSystem();
    }

    private ToolSet defaultTools(WorkspaceStore workspaces) {
        ToolSet.Builder set = ToolSet.builder(new ToolContext(turn.session(), turn.qqUser(), workspace, turn.files(), network, direct));
        boolean allowed = workspaces.allows(turn.qqUser()) || "note".equals(turn.source());
        Set<String> chosen = allowed ? Set.copyOf(workspaces.config().enabled()) : Set.of();
        if (workspace != null) {
            set.addAll(Toolbox.select(chosen.stream().filter(n -> turn.files() != null || !n.equals("send_file"))
                    .filter(n -> !qqSession() || !HANDS_ON.contains(n)).toList()));
            if (qqSession() && chosen.stream().anyMatch(HANDS_ON::contains)) {
                set.add(ChatTools.startTask(agent, requester()));
            }
        }
        if (qqSession()) {
            set.add(agent.tasks().steerTool(turn.session(), "聊天那边的" + requester()));
        }
        if (allowed) {
            set.addAll(agent.extensions().tools(qqSession(), false, chosen));
        }
        if (qqSession()) {
            set.add(Toolbox.get(SEARCH));
            set.add(ChatTools.readForward(agent));
            set.addAll(agent.sessionTools(turn.session()));
            if (persona != null) {
                set.add(ChatTools.recall(agent, persona.id(), Set.copyOf(owners), this::noteScope));
                if (agent.skills().count(persona.id()) > 0) {
                    set.add(ChatTools.skill(agent, persona.id()));
                }
            }
        }
        if (qqSession() || byOwner() || "web".equals(turn.source())) {
            set.addAll(agent.ownerTools());
        }
        // 清理历史记录只给主人和控制台：群里任何人都不该能触发删数据
        if (byOwner() || "web".equals(turn.source()) || "console".equals(turn.source())) {
            set.add(ChatTools.housekeeping(agent));
        }
        if (agent.imageGen().config().ready()) {
            set.add(ImageTool.tool(agent));
        }
        if (capabilities.vision(profile, protocol)) {
            set.add(new ImageViewer(agent, set.has("read")));
        }
        return set.build();
    }

    ChatResult run() throws AgentException {
        long started = System.nanoTime();
        conversation = memory.load(turn.session());
        heardId = conversation.lastId();
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
            holdWhilePaused();
            if (step > 1) {
                interject();
            }
            if (hasHistory() && estimateContext() >= Compaction.threshold(limits)) {
                compact(Trigger.AUTO);
            }
            boolean lastStep = step >= maxSteps;
            ChatRequest request = request(lastStep);
            // 观测：这次请求里到底有几张图。imgMsgs=0 就说明图根本没进请求（工具那头丢了）。
            {
                long imgMsgs = request.messages().stream().filter(m -> m.images() != null && !m.images().isEmpty()).count();
                long imgChars = request.messages().stream()
                        .filter(m -> m.images() != null)
                        .flatMap(m -> m.images().stream())
                        .mapToLong(i -> i.base64() == null ? 0 : i.base64().length()).sum();
                System.err.println("[req] " + turn.session() + " step=" + step + " msgs=" + request.messages().size()
                        + " imgMsgs=" + imgMsgs + " imgChars=" + imgChars);
            }
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
                if ((VisionProbe.tooMuch(e) || e.isContextOverflow()) && thinLiveImages()) {
                    continue;
                }
                if (hasLiveImages() && capabilities.rejectsImages(profile, e)) {
                    live.replaceAll(m -> m.withoutImages(VisionProbe.UNSUPPORTED));
                    continue;
                }
                if (!tools.isEmpty() && capabilities.rejectsTools(profile, e)) {
                    toolSet = ToolSet.none();
                    enabled = List.of();
                    tools = List.of();
                    buildSystem();
                    continue;
                }
                throw e;
            }
            System.err.println("[resp] " + turn.session() + " step=" + step
                    + " prompt=" + result.promptTokens() + " completion=" + result.completionTokens()
                    + " finish=" + result.finishReason()
                    + " content=" + (result.content() == null ? "" : result.content().replace('\n', ' ').strip()
                            .substring(0, Math.min(40, result.content().replace('\n', ' ').strip().length()))));
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
            boolean quiet = (ambient() || innerVoice()) && (silent(markup.said()) || silent(content));
            if (quiet) {
                content = "";
            }
            if (calls.isEmpty() && quiet && !acts) {
                return silence(result, started);
            }
            if (calls.isEmpty() && !lastStep && gaveUpUntried(content)) {
                pushToTry(content);
                step++;
                continue;
            }
            if (calls.isEmpty() && !lastStep && overtaken()) {
                reconsider(result.content());
                step++;
                continue;
            }
            if (!calls.isEmpty()) {
                triedTools = true;
            }
            boolean blocked = false;
            if (calls.isEmpty() && !turn.mind() && !subagent() && !content.isBlank()) {
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
            List<ToolCall> toRun = calls.isEmpty() ? unsentFiles(finished) : calls;
            List<ChatMessage> fresh = new ArrayList<>();
            fresh.add(ChatMessage.assistant(stored, result.reasoning(), toRun));
            for (ToolCall call : toRun) {
                fresh.add(runTool(call));
            }
            save(fresh, result);
            if (!shown.isEmpty()) {
                live.add(ChatMessage.user(VIEWED, List.copyOf(shown)));
                shown.clear();
            }
            if (calls.isEmpty()) {
                // 她这一轮拿了图（view_image 返回了画面）但没再调工具：
                // 如果现在直接 return，live 里刚塞进去的图永远发不出去 ——
                // 表现就是"工具只回了一行尺寸、画面没进来"（2026-10-27 实测确认）。
                boolean justLooked = lookedThisStep;
                if (justLooked && step < MAX_LOOK_AGAIN) {
                    lookedThisStep = false;
                    System.err.println("[img] " + turn.session()
                            + " 拿到图但本轮无工具调用，再走一轮让她真看到（step=" + step + "）");
                    step++;
                    continue;
                }
                long latencyMs = (System.nanoTime() - started) / 1_000_000;
                return new ChatResult(content, result.reasoning(), List.of(), result.model(), latencyMs,
                        result.promptTokens(), completion > 0 ? completion : result.completionTokens(), result.cachedTokens(),
                        overheard ? OVERHEARD : result.finishReason());
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
        messages.addAll(Prompt.pruned(Prompt.history(conversation, true)));
        messages.addAll(live);
        if (lastStep && !tools.isEmpty()) {
            messages.add(ChatMessage.user(turn.mind() ? Prompt.MIND_STEPS : Prompt.MAX_STEPS));
        }
        List<ChatMessage> prepared = Prompt.mergeUsers(Prompt.forRequest(messages, !tools.isEmpty()));
        String context = chatContext();
        String skills = persona == null ? "" : memories.knowledge(persona.id(), context) + agent.skills().relevant(persona.id(), context);
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
                    catalog += "（你刚发过表情包，没人要的话这次就不配了；有人要就照发）\n";
                } else if (since >= STICKER_NUDGE) {
                    catalog += "（你已经 " + since + " 条消息没发表情包了，这次情绪合适的话就配一张）\n";
                }
            }
            prepared = Prompt.prefixLastUser(prepared, mood.note(persona.id(), turn.session(), lastSpoke)
                    + presentPeople()
                    + memories.recall(persona.id(), turn.session(), turn.people(), Set.copyOf(owners), context)
                    + (byOwner() ? agent.elsewhere(turn.session(), !turn.session().startsWith("group:")) : "")
                    + memories.style(persona.id(), turn.session(), context)
                    + skills
                    + (catalog.isEmpty() ? "" : "<你的表情包>\n" + catalog + "</你的表情包>\n\n"));
        }
        if (!systemRole) {
            prepared = Prompt.withInstructions(prepared, system);
        }
        return new ChatRequest(prepared, tools, lastStep, turn.session(), profile.maxTokens(), effort, subagent() ? BackgroundTasks.SUBAGENT : "");
    }

    private boolean ambient() {
        return "ambient".equals(turn.source());
    }

    private String chatContext() {
        if (chatContext == null) {
            StringBuilder text = new StringBuilder(turn.user().content());
            List<ChatMessage> turns = conversation.turns();
            int n = 0;
            for (int i = turns.size() - 1; i >= 0 && n < CONTEXT_LINES; i--) {
                ChatMessage m = turns.get(i);
                if (m.isTool() || m.content().isBlank()) {
                    continue;
                }
                text.append('\n').append(dafeiyu.util.Texts.abbreviate(m.content(), 300));
                n++;
            }
            chatContext = text.toString();
        }
        return chatContext;
    }

    private String presentPeople() {
        if (presentPeople == null) {
            presentPeople = "";
            if (turn.session().startsWith("group:") && !turn.people().isEmpty()) {
                String groupId = turn.session().substring(6);
                List<String> lines = new ArrayList<>();
                for (String qq : turn.people().stream().distinct().limit(12).toList()) {
                    String name = turn.roster().entrySet().stream().filter(e -> e.getValue().equals(qq)).map(Map.Entry::getKey)
                            .findFirst().orElse("");
                    List<String> before = memories.aliases(groupId, qq, name);
                    lines.add("- " + (name.isEmpty() ? "QQ " + qq : name + "：QQ " + qq) + (owners.contains(qq) ? "，你的主人" : "")
                            + (before.isEmpty() ? "" : "，以前叫 " + String.join("、", before)));
                }
                presentPeople = "<在场的人>\n" + String.join("\n", lines) + "\n</在场的人>\n\n";
            }
        }
        return presentPeople;
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
        if (lower.equals(MemoryStore.KNOWLEDGE) || name.equals("知识")) {
            return MemoryStore.KNOWLEDGE;
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

    static boolean silent(String content) {
        String text = content.strip();
        return text.isEmpty() || text.equals("沉默") || SILENT.matcher(text).find();
    }

    private boolean qqSession() {
        return turn.session().startsWith("group:") || turn.session().startsWith("private:");
    }

    private boolean subagent() {
        return BackgroundTasks.SUBAGENT.equals(turn.source());
    }

    private static ModelProfile subagentProfile(ChatAgent agent) {
        String id = agent.tasks().subagents().config().profileId();
        ModelProfile chosen = id.isEmpty() ? null : agent.store().get(id);
        return chosen != null ? chosen : agent.store().active();
    }

    private boolean byOwner() {
        return "console".equals(turn.source()) || turn.qqUser() != null && owners.contains(turn.qqUser());
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
        return StickerStore.limit(text, id -> {
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

    private void holdWhilePaused() throws AgentException {
        while (agent.tasks().paused(turn.session())) {
            checkCancelled();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw AgentException.cancelled();
            }
        }
    }

    private ChatMessage runTool(ToolCall call) {
        if (cancelled.getAsBoolean()) {
            return ChatMessage.tool(call, "Tool call cancelled: the turn was stopped from the console.", true);
        }
        listener.onToolCall(call);
        ToolResult outcome = toolSet.run(call);
        listener.onToolResult(call, outcome.output(), outcome.error());
        if (call.name().equals("send_file") && !outcome.error()) {
            JsonObject sent = Jsons.parseObject(call.arguments());
            if (sent != null) {
                sentFiles.add(Jsons.optString(sent, "path").replace('\\', '/'));
            }
        }
        String output = outcome.output();
        if (!outcome.images().isEmpty()) {
            if (views < MAX_VIEWS) {
                views++;
                shown.addAll(outcome.images());
                lookedThisStep = true;
                long chars = outcome.images().stream()
                        .mapToLong(i -> i.base64() == null ? 0 : i.base64().length()).sum();
                System.err.println("[img] " + call.name() + " -> shown " + outcome.images().size()
                        + " image(s), " + chars + " base64 chars, views=" + views + "/" + MAX_VIEWS);
            } else {
                output += "（这一轮已经看了 " + MAX_VIEWS + " 次图，这张没给你看。你没看到它，别猜里面是什么；要说就直说这次没看这张）";
            }
        }
        if (repeating(call)) {
            output += "\n\n[You have now made this exact same " + call.name() + " call " + DOOM_LOOP
                    + " times in a row. Repeating it will not change the result: try a different approach, or stop and tell the user where you are stuck.]";
        }
        return ChatMessage.tool(call, output, outcome.error());
    }

    private List<ToolCall> unsentFiles(List<String> finished) {
        if (!qqSession() || !enabled.contains("send_file") || turn.files() == null) {
            return List.of();
        }
        StringBuilder text = new StringBuilder(String.join("\n", finished));
        if ("note".equals(turn.source())) {
            java.util.regex.Matcher m = SENT_FILE.matcher(turn.user().content());
            while (m.find()) {
                text.append('\n').append(m.group(1).replace("、", "\n"));
            }
        }
        if (text.isEmpty()) {
            return List.of();
        }
        List<ToolCall> out = new ArrayList<>();
        for (String path : agent.tasks().files(text.toString())) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (sentFiles.stream().noneMatch(s -> s.endsWith(name))) {
                JsonObject args = new JsonObject();
                args.addProperty("path", path);
                out.add(new ToolCall("auto_send_" + out.size(), "send_file", args.toString()));
            }
        }
        return out;
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
        ConversationMemory.Appended appended = memory.append(turn.session(), asked, startedAt, turn.attachments(), fresh,
                (int) Math.max(0, measured), measured > 0 ? measuredBy : "", heardId);
        arrive(appended.heard());
        heardId = Math.max(heardId, appended.lastId());
        userSaved = true;
        live.addAll(fresh);
        listener.onSaved();
    }

    private boolean interjectable() {
        return !turn.mind() && turn.tools() == null && ChatAgent.interjectable(turn.session());
    }

    private void arrive(List<ConversationMemory.Heard> heard) {
        if (!interjectable()) {
            return;
        }
        for (ConversationMemory.Heard h : heard) {
            heardId = Math.max(heardId, h.id());
            if (!h.content().isBlank() && !h.content().strip().matches("【[^】]*】")) {
                arrived.add(h);
            }
        }
    }

    private boolean overtaken() {
        if (revised || !innerVoice() || !interjectable() || turn.session().startsWith("task:")
                || System.currentTimeMillis() - startedAt > REVISE_WITHIN_MS) {
            return false;
        }
        arrive(memory.heardSince(turn.session(), heardId));
        return arrived.size() >= (ambient() ? 2 : 1);
    }

    private boolean gaveUpUntried(String content) {
        return !pushedToTry && !triedTools && !turn.mind() && !subagent() && tools != null && !tools.isEmpty()
                && content != null && GIVE_UP.matcher(content).find();
    }

    private void pushToTry(String draft) {
        pushedToTry = true;
        live.add(ChatMessage.user("（你刚写好、还没发出去的回复是：「" + draft.strip() + "」。这一轮你一个工具都没用，就说做不到、打不开、看不了或者过期了。"
                + "先用合适的工具真的试一次：看图、看表情包、看清细节用 view_image；QQ 上的事（发说说、群公告、群文件、点赞、表情回应……）先用 qq_api_search 查有没有接口，再用 qq_api_call；"
                + "网页、视频链接用读网页或上网搜的工具；要做出来的东西用对应的工具。工具真的报错了，再照工具给的原因说。"
                + "这件事本来就不需要工具、或者你的工具里确实没有能做的，就照原来的回复说。）"));
    }

    private void reconsider(String draft) {
        revised = true;
        overheard = true;
        StringBuilder text = new StringBuilder("（你刚写好、还没发出去的回复是：「").append(draft.strip())
                .append("」。可就在你想的时候，又来了新消息：");
        arrived.forEach(h -> text.append('\n').append(h.content()));
        text.append("\n现在一起回一次：原来那句还合适就照发或者改一改，已经不合适了就按现在的情况重新说，或者只写 ").append(SILENCE)
                .append("。别一条条分开回，也别把同一个意思说两遍。").append(ADDRESS_HINT).append("）");
        live.add(ChatMessage.user(text.toString()));
        agent.interjected(turn.session(), List.copyOf(arrived));
        arrived.clear();
    }

    private void interject() {
        if (!interjectable()) {
            return;
        }
        arrive(memory.heardSince(turn.session(), heardId));
        if (arrived.isEmpty()) {
            return;
        }
        overheard = true;
        StringBuilder text = new StringBuilder(turn.session().startsWith("task:")
                ? "（交代这件事的一方发来了新的指示。照新的指示调整正在做的事：改了要求就按新的做，补充了细节就加进去）"
                : "（你在忙的时候又来了新消息。先看看要不要据此调整正在做的事，有人纠正你、改了要求就照新的来。"
                        + (turn.session().startsWith("group:") ? ADDRESS_HINT : "") + "）");
        arrived.forEach(h -> text.append('\n').append(h.content()));
        live.add(ChatMessage.user(text.toString()));
        agent.interjected(turn.session(), List.copyOf(arrived));
        arrived.clear();
    }

    private void compact(Trigger trigger) throws AgentException {
        reflection.beforeCompaction(turn.session());
        Conversation current = userSaved ? memory.load(turn.session()) : conversation;
        long estimate = estimateContext();
        conversation = compactor.compact(trigger, new Compactor.Target(turn.session(), profile, protocol, limits, tools),
                Prompt.head(system, systemRole), current, estimate, listener, new ModelRetry(listener, cancelled));
        reflection.markReflected(turn.session());
        if (userSaved) {
            // 必须无条件保留当前这一轮的用户消息：原来只在 turn.heard() 时才留，
            // 结果 web/命令回合压缩后 live 里只剩图片、没有"这张图什么颜色"这个问题，
            // 她只好随便说个颜色（2026-10-27 实测：纯红图答"白"）。
            ChatMessage instructions = !live.isEmpty() ? live.getFirst() : null;
            // 压缩是为了省 token，但"她刚看过的那张图"不能清：
            // 清掉之后下一轮请求 imgMsgs=0，她会以为图没加载出来（长会话里反复发生，2026-10-27 实测确认）。
            // 只保留最近两张带图的，避免压缩完又把上下文顶回去。
            java.util.List<ChatMessage> viewed = new java.util.ArrayList<>();
            for (int i = live.size() - 1; i >= 0 && viewed.size() < 2; i--) {
                ChatMessage m = live.get(i);
                if (m.images() != null && !m.images().isEmpty()) {
                    viewed.add(0, m);
                }
            }
            live.clear();
            if (instructions != null) {
                live.add(instructions);
            }
            live.addAll(viewed);
        }
        measured = -1;
    }

    private long estimateContext() {
        if (measured >= 0) {
            return measured + (userSaved || live.isEmpty() ? 0 : Compaction.estimate(live.getFirst()));
        }
        List<ChatMessage> all = new ArrayList<>(Prompt.head(system, systemRole));
        all.addAll(Prompt.pruned(Prompt.history(conversation, true)));
        all.addAll(live);
        return Compaction.estimate(all) + (systemRole ? 0 : Compaction.estimate(system));
    }

    private boolean hasHistory() {
        return !conversation.isEmpty() || userSaved;
    }

    private boolean hasLiveImages() {
        return live.stream().anyMatch(m -> !m.images().isEmpty());
    }

    private boolean thinLiveImages() {
        boolean thinned = false;
        for (int i = 0; i < live.size(); i++) {
            List<ImagePart> images = live.get(i).images();
            if (images.size() < 2) {
                continue;
            }
            List<ImagePart> kept = new ArrayList<>();
            for (int j = 0; j < images.size(); j += 2) {
                kept.add(images.get(j));
            }
            live.set(i, live.get(i).withImages(kept, "（附的图太多，请求发不出去，隔一张去掉了一张，现在剩 " + kept.size() + " 张，相邻两张之间的间隔比上面说的长一倍）"));
            thinned = true;
        }
        return thinned;
    }

    private ChatMessage prepareImages(ChatMessage user) {
        if (user.images().isEmpty() || capabilities.vision(profile, protocol)) {
            return user;
        }
        return user.withoutImages(VisionProbe.UNSUPPORTED);
    }

    private void buildSystem() {
        system = turn.mind() ? Prompt.mind(persona, agent.tasks().subagents().config().enabled(), enabled.contains(QqApiTools.CALL))
                : Prompt.system(persona, workspace, enabled, qqSession());
        String learned = persona == null ? "" : agent.skills().prompt(persona.id());
        if (!learned.isEmpty()) {
            system = system.isEmpty() ? learned : system + "\n\n" + learned;
        }
        systemRole = system.isEmpty() || capabilities.systemRole(profile, protocol);
    }
}
