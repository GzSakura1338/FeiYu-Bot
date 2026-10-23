package dafeiyu.agent;

import com.google.gson.JsonObject;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.util.Jsons;
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
    private static final String SEARCH = "websearch";
    static final String READ_FORWARD = "read_forward";
    static final ToolSpec READ_FORWARD_SPEC = new ToolSpec(READ_FORWARD,
            "读别人分享的合并转发聊天记录。消息里的“[合并转发的聊天记录：编号]”就是，把冒号后面的编号原样填进来，会返回里面每条消息是谁说的、说了什么。"
                    + "别人发来让你看的、问你“你看看这个”的，先读了再回。",
            com.google.gson.JsonParser.parseString("""
                    {"type": "object", "properties": {
                      "id": {"type": "string", "description": "合并转发的编号"}
                    }, "required": ["id"]}""").getAsJsonObject());
    static final String RECALL = "recall";
    static final ToolSpec RECALL_SPEC = new ToolSpec(RECALL,
            "想想你记得的事。“<你记得>”里只放了跟现在相关的一部分；想起更多关于某个人、这个群、你自己的事，翻日记或查过的词，就用它，要多少查多少。",
            com.google.gson.JsonParser.parseString("""
                    {"type": "object", "properties": {
                      "about": {"type": "string", "description": "想谁或想什么：名字、QQ 号、self（你自己）、group（这个群）、knowledge（查过的词）、diary（日记）"},
                      "query": {"type": "string", "description": "只想找跟某件事有关的就写关键词，不写就是全部"},
                      "offset": {"type": "integer", "description": "接着上次往后看时填，结果里会告诉你填几"}
                    }, "required": ["about"]}""").getAsJsonObject());
    static final String SKILL = "skill";
    static final ToolSpec SKILL_SPEC = new ToolSpec(SKILL,
            "查看你学会的某个技能的完整做法。“【你学会的技能】”里只写了用途的技能，碰到对应的事先用它看清楚怎么做。",
            com.google.gson.JsonParser.parseString("""
                    {"type": "object", "properties": {
                      "name": {"type": "string", "description": "技能的名字"}
                    }, "required": ["name"]}""").getAsJsonObject());
    static final String START_TASK = "start_task";
    static final ToolSpec START_TASK_SPEC = new ToolSpec(START_TASK,
            "把要动手的活交给后台去做：写网页、做小游戏、做或改文件、改代码、跑命令、查一堆资料整理成文档这类要好几步的事。"
                    + "交出去以后聊天这边马上就能接着聊，后台做好了会让你把结果告诉对方、把文件发出去。这类活不要在聊天里自己一步步做。",
            com.google.gson.JsonParser.parseString("""
                    {"type": "object", "properties": {
                      "title": {"type": "string", "description": "一句话的任务名，比如“网页版狼人杀”"},
                      "instructions": {"type": "string", "description": "完整的要求：要做成什么样、给谁用、有什么细节，改已有文件就写清楚文件路径和要改什么"}
                    }, "required": ["title", "instructions"]}""").getAsJsonObject());
    static final String STEER_TASK = "steer_task";
    private static final String STEER_SCHEMA = """
            {"type": "object", "properties": {
              "task": {"type": "string", "description": "任务编号（#后面那串）或任务名里的词；只有一个在做时可以不写"},
              "action": {"type": "string", "enum": ["tell", "pause", "resume", "stop"], "description": "tell 转达新的要求、方向或补充；pause 暂停；resume 继续；stop 叫停不做了"},
              "message": {"type": "string", "description": "tell 时要转达的话，写清楚要改成什么、补充什么"}
            }, "required": ["action"]}""";
    private static final Set<String> HANDS_ON = Set.of("write", "edit", "patch", "move", "delete", "shell");
    private static final java.util.regex.Pattern SENT_FILE = java.util.regex.Pattern.compile("文件：([^。；\\n]+)");
    private static final int CONTEXT_LINES = 16;
    private static final int FACE_COOLDOWN = 2;
    private static final int MAX_NOTES = 2;
    private static final int STICKER_CATALOG = 40;
    private static final int STICKER_NUDGE = 4;
    private static final int MAX_ERRANDS = 2;
    private static final int MAX_VIEWS = 6;
    private static final String VIEWED = "（这是你刚才用 view_image 要看的图）";
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
    private final boolean network;
    private final boolean direct;
    private final ToolSet requestTools;
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
        this.requestTools = turn.tools() == null && qqSession() && capabilities.toolCall(profile)
                && ("console".equals(turn.source()) || turn.qqUser() != null && owners.contains(turn.qqUser())) ? agent.requestTools() : null;
        if (turn.tools() != null) {
            this.tools = capabilities.toolCall(profile) ? turn.tools().specs() : List.of();
            this.enabled = tools.stream().map(ToolSpec::name).toList();
        } else {
            boolean allowed = (workspaces.allows(turn.qqUser()) || "note".equals(turn.source())) && capabilities.toolCall(profile);
            Set<String> chosen = allowed ? Set.copyOf(workspaces.config().enabled()) : Set.of();
            List<String> builtin = workspace == null ? List.of()
                    : chosen.stream().filter(Toolbox::exists).filter(n -> turn.files() != null || !n.equals("send_file"))
                    .filter(n -> !qqSession() || !HANDS_ON.contains(n)).toList();
            List<ToolSpec> all = new ArrayList<>(Toolbox.specs(Set.copyOf(builtin), network, direct));
            if (qqSession() && allowed && workspace != null && chosen.stream().anyMatch(HANDS_ON::contains)) {
                all.add(START_TASK_SPEC);
            }
            List<BackgroundTasks.Task> running = qqSession() ? agent.tasks().unfinished(turn.session()) : List.of();
            if (!running.isEmpty() && capabilities.toolCall(profile)) {
                all.add(steerSpec(running));
            }
            if (allowed) {
                all.addAll(agent.extensions().tools(qqSession(), false, chosen));
            }
            if (qqSession() && capabilities.toolCall(profile) && all.stream().noneMatch(t -> t.name().equals(SEARCH))) {
                all.addAll(Toolbox.specs(Set.of(SEARCH)));
            }
            if (qqSession() && capabilities.toolCall(profile)) {
                all.add(READ_FORWARD_SPEC);
                if (persona != null) {
                    all.add(RECALL_SPEC);
                    if (agent.skills().count(persona.id()) > 0) {
                        all.add(SKILL_SPEC);
                    }
                }
            }
            if (requestTools != null) {
                all.addAll(requestTools.specs());
            }
            if (capabilities.toolCall(profile) && capabilities.vision(profile, protocol)) {
                all.add(ImageViewer.SPEC);
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
                    live.replaceAll(m -> m.withoutImages(VisionProbe.UNSUPPORTED));
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
            if (calls.isEmpty() && !lastStep && overtaken()) {
                reconsider(result.content());
                step++;
                continue;
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

    private static boolean silent(String content) {
        String text = content.strip();
        return text.isEmpty() || text.contains(SILENCE) || text.equals("沉默");
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
        Toolbox.Outcome outcome = turn.tools() != null ? turn.tools().run(call)
                : call.name().equals(READ_FORWARD) && enabled.contains(READ_FORWARD) ? readForward(call)
                : call.name().equals(RECALL) && enabled.contains(RECALL) ? recallTool(call)
                : call.name().equals(SKILL) && enabled.contains(SKILL) ? skillTool(call)
                : call.name().equals(START_TASK) && enabled.contains(START_TASK) ? startTask(call)
                : call.name().equals(STEER_TASK) && enabled.contains(STEER_TASK) ? steerTask(call)
                : requestTools != null && enabled.contains(call.name()) && requestTools.specs().stream().anyMatch(s -> s.name().equals(call.name()))
                ? requestTools.run(call)
                : call.name().equals(ImageViewer.NAME) && enabled.contains(ImageViewer.NAME)
                ? ImageViewer.run(agent, call.arguments(), enabled.contains("read") ? workspace : null)
                : agent.extensions().owns(call.name()) && enabled.contains(call.name()) ? agent.extensions().run(call, turn.session())
                : Toolbox.run(call, Set.copyOf(enabled), new ToolContext(workspace, turn.files(), network, direct));
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
            } else {
                output += "（这一轮已经看了 " + MAX_VIEWS + " 次图，这张没给你看了，先根据看到的说吧）";
            }
        }
        if (repeating(call)) {
            output += "\n\n[You have now made this exact same " + call.name() + " call " + DOOM_LOOP
                    + " times in a row. Repeating it will not change the result: try a different approach, or stop and tell the user where you are stuck.]";
        }
        return ChatMessage.tool(call, output, outcome.error());
    }

    private Toolbox.Outcome readForward(ToolCall call) {
        JsonObject args = Jsons.parseObject(call.arguments());
        String id = args == null ? "" : Jsons.optString(args, "id").replace("合并转发的聊天记录：", "").replace("[", "").replace("]", "").strip();
        try {
            return new Toolbox.Outcome(agent.readForward(id), false);
        } catch (ToolFailure e) {
            return new Toolbox.Outcome(e.getMessage(), true);
        }
    }

    private Toolbox.Outcome recallTool(ToolCall call) {
        JsonObject args = Jsons.parseObject(call.arguments());
        String about = args == null ? "" : Jsons.optString(args, "about").strip();
        String query = args == null ? "" : Jsons.optString(args, "query");
        int offset = args != null && args.has("offset") && args.get("offset").isJsonPrimitive() ? Math.max(0, args.get("offset").getAsInt()) : 0;
        List<String> scopes = recallScopes(about);
        if (scopes.isEmpty()) {
            return new Toolbox.Outcome("不知道「" + about + "」是谁或什么。about 写名字、QQ 号、self、group、knowledge 或 diary。", true);
        }
        String text = memories.lookup(persona.id(), scopes.getFirst(), turn.session(), Set.copyOf(owners), query, offset);
        return new Toolbox.Outcome(scopes.size() > 1 ? text + "\n（叫这个名字的不止一个人，这是 " + scopes.getFirst()
                + "；其他的：" + String.join("、", scopes.subList(1, scopes.size())) + "，可以填 QQ 号查）" : text, false);
    }

    private List<String> recallScopes(String about) {
        String lower = about.toLowerCase(Locale.ROOT);
        if (about.isEmpty()) {
            return List.of();
        }
        if (lower.equals(MemoryStore.DIARY) || about.equals("日记")) {
            return List.of(MemoryStore.DIARY);
        }
        if (lower.startsWith("user:") || about.matches("\\d{5,12}")) {
            return List.of("user:" + about.replaceFirst("(?i)^user:", "").strip());
        }
        if (lower.startsWith("group:") && about.substring(6).strip().matches("\\d{5,12}")) {
            return List.of("group:" + about.substring(6).strip());
        }
        String scope = noteScope(about);
        if (scope != null) {
            return List.of(scope);
        }
        return memories.scopesNamed(persona.id(), about);
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

    private Toolbox.Outcome startTask(ToolCall call) {
        JsonObject args = Jsons.parseObject(call.arguments());
        String title = args == null ? "" : Jsons.optString(args, "title").strip();
        String instructions = args == null ? "" : Jsons.optString(args, "instructions").strip();
        if (title.isEmpty() || instructions.isEmpty()) {
            return new Toolbox.Outcome("title 和 instructions 都要写", true);
        }
        agent.tasks().start(turn.session(), requester(), turn.qqUser() == null ? "" : turn.qqUser(), title, instructions);
        return new Toolbox.Outcome("「" + title + "」已经交给后台在做了。现在先回对方一句在做了（别说已经做好）；做好后会让你把结果告诉对方、把文件发出去。"
                + "别在这里自己再动手做一遍。", false);
    }

    public static ToolSpec steerSpec(List<BackgroundTasks.Task> running) {
        return new ToolSpec(STEER_TASK, "管后台正在做的活：转达新的要求或方向、暂停、继续、叫停。对方中途改主意、补充细节、说先别做了，"
                + "就用它告诉后台，不要再开一个新任务。现在后台的：" + BackgroundTasks.describe(running),
                com.google.gson.JsonParser.parseString(STEER_SCHEMA).getAsJsonObject());
    }

    private Toolbox.Outcome steerTask(ToolCall call) {
        JsonObject args = Jsons.parseObject(call.arguments());
        if (args == null) {
            return new Toolbox.Outcome("参数不对", true);
        }
        try {
            return new Toolbox.Outcome(agent.tasks().steer(Jsons.optString(args, "task"), Jsons.optString(args, "action"),
                    Jsons.optString(args, "message"), "聊天那边的" + requester(), turn.session()), false);
        } catch (ToolFailure e) {
            return new Toolbox.Outcome(e.getMessage(), true);
        }
    }

    private Toolbox.Outcome skillTool(ToolCall call) {
        JsonObject args = Jsons.parseObject(call.arguments());
        String name = args == null ? "" : Jsons.optString(args, "name");
        String detail = agent.skills().detail(persona.id(), name);
        return detail == null ? new Toolbox.Outcome("没有叫「" + name + "」的技能，看看【你学会的技能】里的名字", true) : new Toolbox.Outcome(detail, false);
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
        if (revised || !innerVoice() || !interjectable() || turn.session().startsWith("task:")) {
            return false;
        }
        arrive(memory.heardSince(turn.session(), heardId));
        return arrived.size() >= (ambient() ? 2 : 1);
    }

    private void reconsider(String draft) {
        revised = true;
        StringBuilder text = new StringBuilder("（你刚写好、还没发出去的回复是：「").append(draft.strip())
                .append("」。可就在你想的时候，又来了新消息：");
        arrived.forEach(h -> text.append('\n').append(h.content()));
        text.append("\n现在一起回一次：原来那句还合适就照发或者改一改，已经不合适了就按现在的情况重新说，或者只写 ").append(SILENCE)
                .append("。别一条条分开回，也别把同一个意思说两遍。）");
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
        StringBuilder text = new StringBuilder(turn.session().startsWith("task:")
                ? "（交代这件事的一方发来了新的指示。照新的指示调整正在做的事：改了要求就按新的做，补充了细节就加进去）"
                : "（你在忙的时候又来了新消息。先看看要不要据此调整正在做的事，有人纠正你、改了要求就照新的来）");
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

    private ChatMessage prepareImages(ChatMessage user) {
        if (user.images().isEmpty() || capabilities.vision(profile, protocol)) {
            return user;
        }
        return user.withoutImages(VisionProbe.UNSUPPORTED);
    }

    private void buildSystem() {
        system = turn.mind() ? Prompt.mind(persona, agent.tasks().subagents().config().enabled()) : Prompt.system(persona, workspace, enabled, qqSession());
        String learned = persona == null ? "" : agent.skills().prompt(persona.id());
        if (!learned.isEmpty()) {
            system = system.isEmpty() ? learned : system + "\n\n" + learned;
        }
        systemRole = system.isEmpty() || capabilities.systemRole(profile, protocol);
    }
}
