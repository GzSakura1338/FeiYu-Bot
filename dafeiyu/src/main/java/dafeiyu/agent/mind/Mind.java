package dafeiyu.agent.mind;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.BackgroundTasks;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.ChatTurn;
import dafeiyu.agent.SessionDetail;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.intercom.Intercom;
import dafeiyu.agent.persona.Persona;
import dafeiyu.agent.qq.QqChatBridge;
import dafeiyu.log.LogBuffer;
import dafeiyu.util.Clock;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static dafeiyu.util.Texts.abbreviate;

public final class Mind implements QqChatBridge.Observer {

    public record Status(boolean enabled, boolean thinking, String pace, long lastAt, long nextAt, int thoughts,
                         int spokeToday, String why, long contextTokens, int contextWindow, long compactAt,
                         int compactions) {}

    public static final String SESSION = "mind:self";
    private static final Map<String, Long> PACES = Map.of("fast", 15_000L, "normal", 40_000L, "slow", 120_000L);
    private static final long ACTING_MS = 3_000;
    private static final long PAUSED_MS = 30_000;
    private static final long FAILED_MS = 60_000;
    private static final long CALLED_MS = 2_000;
    private static final long NOTICED_MS = 8_000;
    private static final int MAX_BOREDOM = 3;
    private static final int RECENT_THOUGHTS = 6;
    private static final int RESPITE = 3;
    private static final double SAME_THOUGHT = 0.35;
    private static final int MAX_ACTING_STREAK = 3;
    private static final int LINES_PER_PLACE = 3;
    private static final int MAX_ERRANDS = 5;
    private static final int MAX_PERCEIVED = 4_000;
    private static final int MAX_PER_LINE = 400;
    private static final int MAX_RETURNED_LINE = 2_200;
    private static final String RETURNED = "【子 agent 回来了】";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final class Place {
        int count;
        boolean toMe;
        final Deque<String> lines = new ArrayDeque<>();
    }

    private final ChatAgent agent;
    private final QqChatBridge bridge;
    private final LogBuffer log;
    private final Object bell = new Object();
    private final Map<String, Place> places = new LinkedHashMap<>();
    private final List<String> own = new ArrayList<>();
    private final List<String> errands = new ArrayList<>();
    private volatile boolean thinking;
    private volatile boolean wake;
    private volatile long lastAt;
    private volatile long nextAt;
    private volatile int thoughts;
    private volatile long stirAt = Long.MAX_VALUE;
    private int actingStreak;
    private int quiet;
    private int circling;
    private int respite;
    private final Deque<String> recent = new ArrayDeque<>();
    private final MindCues cues;

    public Mind(ChatAgent agent, QqChatBridge bridge, LogBuffer log) {
        this.agent = agent;
        this.bridge = bridge;
        this.log = log;
        this.cues = new MindCues(agent, bridge);
        bridge.setObserver(this);
        agent.setErrands(this::errand);
        agent.setSubagentResults(this::returned);
        agent.intercom().onNews(() -> stir(System.currentTimeMillis() + CALLED_MS));
        for (SessionDetail.Entry e : agent.session(SESSION, RECENT_THOUGHTS * 8).turns()) {
            if (e.role().equals("assistant") && e.content() != null && !e.content().isBlank()) {
                notice(e.content().replaceAll("\\s+", " ").strip(), true);
            }
        }
    }

    private void errand(ChatAgent.Errand errand) {
        Intercom.Commitment c = agent.intercom().commit(errand.session(), errand.who(), errand.task());
        synchronized (places) {
            errands.add("【有人托你办事】#" + c.id() + " " + errand.who() + "在" + where(errand.session()) + "托你：" + abbreviate(errand.task(), 200)
                    + "（你已经在那边答应了。办完" + replyRoute(errand.session()) + "，再 settle #" + c.id() + "）");
            while (errands.size() > MAX_ERRANDS) {
                errands.removeFirst();
            }
        }
        log.info("mind: got an errand from " + errand.session());
        synchronized (bell) {
            wake = true;
            bell.notifyAll();
        }
    }

    private String where(String session) {
        if (session.startsWith("group:")) {
            return "「" + bridge.groupName(session.substring(6)) + "」（群号 " + session.substring(6) + "）";
        }
        return "私聊里";
    }

    private static String replyRoute(String session) {
        if (session.startsWith("private:")) {
            return "用 tell_private（user_id " + session.substring(8) + "）回私聊说一声，进展和结果都只在私聊说，别交给群里";
        }
        return session.startsWith("group:") ? "用 tell_group（group_id " + session.substring(6) + "）回那边说一声" : "回那边说一声";
    }

    public void start() {
        Thread.ofPlatform().daemon().name("mind").start(this::loop);
    }

    public Status status() {
        SessionDetail detail = agent.session(SESSION, 0);
        return new Status(agent.store().freeWill(), thinking, agent.store().mindPace(), lastAt, nextAt, thoughts,
                bridge.proactiveToday(), blocker(), agent.contextTokens(SESSION), agent.contextWindow(),
                agent.compactAt(SESSION), detail.compactions());
    }

    public boolean wakeUp() {
        if (blocker() != null) {
            return false;
        }
        synchronized (bell) {
            wake = true;
            bell.notifyAll();
        }
        return true;
    }

    @Override
    public void heard(String where, String who, String text, boolean toMe) {
        synchronized (places) {
            Place place = places.computeIfAbsent(where, k -> new Place());
            place.count++;
            place.toMe |= toMe;
            place.lines.addLast(where.startsWith("private:") ? who : who + "：" + abbreviate(text, 60));
            while (place.lines.size() > LINES_PER_PLACE) {
                place.lines.pollFirst();
            }
        }
        long now = System.currentTimeMillis();
        long base = PACES.getOrDefault(agent.store().mindPace(), PACES.get("normal"));
        stir(toMe || where.startsWith("private:") ? now + CALLED_MS : Math.max(now + NOTICED_MS, lastAt + base / 2));
    }

    private void stir(long at) {
        synchronized (bell) {
            stirAt = Math.min(stirAt, at);
            bell.notifyAll();
        }
    }

    @Override
    public void said(String where, String text) {
    }

    private void returned(String text) {
        agent.intercom().report(SESSION, text);
        log.info("mind: a subagent came back");
        if (blocker() == null) {
            synchronized (bell) {
                wake = true;
                bell.notifyAll();
            }
        }
    }

    @Override
    public void requested(String what) {
        synchronized (places) {
            own.add("【新的申请】" + what);
        }
        if (blocker() == null) {
            synchronized (bell) {
                wake = true;
                bell.notifyAll();
            }
        }
    }

    private void loop() {
        while (true) {
            long pause;
            boolean blocked = false;
            try {
                if (blocker() == null && agent.intercom().nextReminder() <= System.currentTimeMillis()) {
                    wake = true;
                }
                boolean asked = wake;
                if (blocker() != null || (!agent.store().freeWill() && !asked)) {
                    pause = PAUSED_MS;
                    blocked = true;
                } else {
                    wake = false;
                    pause = step();
                }
            } catch (RuntimeException e) {
                log.warn("mind: a thought failed: " + e);
                pause = FAILED_MS;
            }
            long until = System.currentTimeMillis() + pause;
            nextAt = until;
            synchronized (bell) {
                try {
                    while (!wake) {
                        long reminder = blocker() == null ? agent.intercom().nextReminder() : Long.MAX_VALUE;
                        long due = Math.min(blocked ? until : Math.min(until, stirAt), reminder);
                        long left = due - System.currentTimeMillis();
                        if (left <= 0) {
                            break;
                        }
                        nextAt = due;
                        bell.wait(left);
                    }
                } catch (InterruptedException e) {
                    return;
                }
                stirAt = Long.MAX_VALUE;
            }
        }
    }

    private long step() {
        thinking = true;
        try {
            Persona persona = agent.personas().active();
            String perceived = drain();
            quiet = perceived.isEmpty() ? quiet + 1 : 0;
            String cue = wander(persona.id(), perceived.isEmpty());
            String now = "【" + LocalTime.now().format(CLOCK) + "】";
            agent.hear(SESSION, now + (perceived.isEmpty() ? "四周安静。" : "\n" + perceived) + (cue == null ? "" : "\n" + cue));
            MindTools tools = new MindTools(agent, bridge, persona.id());
            ChatTurn turn = new ChatTurn(SESSION, ChatMessage.user(context(persona, perceived)), null, null, "mind", List.of(), Map.of(), true).withTools(tools);
            ChatResult result = agent.chat(turn, StreamListener.NONE);
            String flat = result.content().replaceAll("\\s+", " ").strip();
            notice(flat, !perceived.isEmpty() || tools.used());
            String thought = abbreviate(flat, 160);
            if (!thought.isEmpty() && agent.guard().scan(thought) == null) {
                agent.mood().remember(persona.id(), thought, SESSION);
                agent.extensions().thought(thought);
            }
            thoughts++;
            if (!tools.spoke().isEmpty()) {
                log.info("mind: decided to talk in " + tools.spoke().size() + " group(s)");
            }
            return pace(tools.used(), pending());
        } catch (AgentException e) {
            log.warn("mind: a thought failed: " + e.getMessage());
            return FAILED_MS;
        } finally {
            lastAt = System.currentTimeMillis();
            thinking = false;
        }
    }

    private String wander(String personaId, boolean calm) {
        List<String> stuck = respite > 0 ? List.of() : MindCues.stuckOn(recent);
        respite = Math.max(0, respite - 1);
        boolean bored = circling >= 2 || !stuck.isEmpty();
        if (!bored && (!calm || quiet < 2 || ThreadLocalRandom.current().nextInt(2) == 0)) {
            return null;
        }
        if (!stuck.isEmpty()) {
            respite = RESPITE;
            circling = 0;
            log.info("mind: noticed she keeps circling " + stuck);
            return "（这几个念头翻来覆去都是「" + String.join("」「", stuck) + "」，你自己都有点腻了。没人要你守着这些，接下来想什么、去哪儿，随你）";
        }
        String cue = cues.next(personaId, String.join(" ", recent));
        if (cue == null) {
            return bored ? "（刚才几个念头一直绕着同一件事打转，那事暂时没进展，你已经有点腻了，想点别的吧）" : null;
        }
        return bored ? "（刚才几个念头一直绕着同一件事打转，那事暂时没进展，你已经有点腻了，思绪飘开了）脑子里闪过：" + cue
                : "脑子里闪过：" + cue;
    }

    private void notice(String thought, boolean news) {
        if (thought.isEmpty()) {
            return;
        }
        boolean same = recent.stream().anyMatch(old -> MindCues.similarity(old, thought) >= SAME_THOUGHT);
        circling = same && !news ? circling + 1 : same ? circling : 0;
        recent.addLast(thought);
        while (recent.size() > RECENT_THOUGHTS) {
            recent.removeFirst();
        }
    }

    private long pace(boolean acting, boolean eventful) {
        long base = PACES.getOrDefault(agent.store().mindPace(), PACES.get("normal"));
        if (acting && actingStreak < MAX_ACTING_STREAK) {
            actingStreak++;
            return ACTING_MS;
        }
        actingStreak = 0;
        return eventful ? base / 2 : base * (1 + Math.min(quiet, MAX_BOREDOM));
    }

    private boolean pending() {
        synchronized (places) {
            return !places.isEmpty() || !own.isEmpty() || !errands.isEmpty() || agent.intercom().hasNews();
        }
    }

    private String drain() {
        List<String> lines = new ArrayList<>(agent.intercom().drain());
        synchronized (places) {
            lines.addAll(errands);
            errands.clear();
            lines.addAll(own);
            own.clear();
            for (Map.Entry<String, Place> e : places.entrySet()) {
                Place p = e.getValue();
                if (e.getKey().startsWith("private:")) {
                    lines.add(String.join("、", p.lines) + " 在私聊里找你说了 " + p.count + " 条（私聊你会在那边自己回，内容不在这里想）");
                    continue;
                }
                lines.add(placeName(e.getKey()) + "来了 " + p.count + " 条新消息" + (p.toMe ? "，有人在叫你（那边的你会自己回）" : "") + "：" + String.join("；", p.lines));
            }
            places.clear();
        }
        StringBuilder out = new StringBuilder();
        int written = 0;
        for (String line : lines) {
            String item = abbreviate(line, line.startsWith(RETURNED) ? MAX_RETURNED_LINE : MAX_PER_LINE);
            if (out.length() + item.length() > MAX_PERCEIVED) {
                break;
            }
            out.append(out.isEmpty() ? "" : "\n").append(item);
            written++;
        }
        if (written < lines.size()) {
            out.append("\n（还有 ").append(lines.size() - written).append(" 件事一下子想不过来，下一轮接着看）");
            synchronized (places) {
                own.addAll(0, lines.subList(written, lines.size()));
            }
        }
        return out.toString();
    }

    private String placeName(String where) {
        if (where.startsWith("group:")) {
            return "「" + bridge.groupName(where.substring(6)) + "」";
        }
        return "私聊里";
    }

    private String context(Persona persona, String perceived) {
        String memories = agent.memories().recall(persona.id(), SESSION, List.of(), Set.copyOf(agent.personas().owners()), perceived);
        long last = bridge.lastActivity();
        List<Intercom.Commitment> open = agent.intercom().open();
        if (!open.isEmpty()) {
            StringBuilder hung = new StringBuilder("<心里挂着的事>\n");
            for (Intercom.Commitment c : open.subList(Math.max(0, open.size() - 8), open.size())) {
                hung.append("- #").append(c.id()).append(" ").append(c.who()).append("在").append(where(c.session())).append("托的：")
                        .append(c.what()).append("（").append(Clock.ago(c.createdAt()))
                        .append(c.what().startsWith("后台在做：") ? "；后台任务正在做，做完会有回音，你别再做一遍、也别开子 agent" : "")
                        .append(c.session().startsWith("private:") ? "；要说进展用 tell_private（user_id " + c.session().substring(8) + "），别在群里提" : "")
                        .append("）\n");
            }
            memories += hung.append("</心里挂着的事>\n\n");
        }
        List<BackgroundTasks.Task> working = agent.tasks().subagentsAtWork();
        if (!working.isEmpty()) {
            StringBuilder busy = new StringBuilder("<子 agent 在做>\n");
            for (BackgroundTasks.Task t : working) {
                busy.append("- #").append(t.id()).append("「").append(t.title()).append("」（").append(BackgroundTasks.label(t.type())).append("，")
                        .append(BackgroundTasks.QUEUED.equals(t.status()) ? "在排队" : BackgroundTasks.PAUSED.equals(t.status()) ? "暂停着"
                                : Clock.ago(t.startedAt()) + "开始的，在做").append("）\n");
            }
            memories += busy.append("</子 agent 在做>\n\n");
        }
        String requests = bridge.requests().describe();
        if (!requests.isEmpty()) {
            memories += "<等你处理的申请>\n" + requests + "\n（用 answer_friend / answer_group 同意或拒绝，处理完就不在这里了）\n</等你处理的申请>\n\n";
        }
        return memories + "（现在是 " + Clock.now() + "。最近一次和人聊天是 "
                + (last <= 0 ? "很久以前" : Clock.ago(last)) + "；今天主动去群里说过 " + bridge.proactiveToday() + " 次。）\n接着想吧，想什么都由你；想做的、该办的，这一轮就用工具去做。";
    }

    private String blocker() {
        if (agent.personas().active() == null) {
            return "没有选人格";
        }
        if (agent.store().active() == null) {
            return "没有选模型";
        }
        return null;
    }
}
