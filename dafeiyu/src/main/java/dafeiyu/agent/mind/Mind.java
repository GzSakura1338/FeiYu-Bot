package dafeiyu.agent.mind;

import dafeiyu.agent.AgentException;
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

    public record Status(boolean enabled, boolean awake, boolean thinking, String pace, long lastAt, long nextAt, int thoughts,
                         int spokeToday, String why, long contextTokens, int contextWindow, long compactAt,
                         int compactions) {}

    public static final String SESSION = "mind:self";
    private static final Map<String, Long> PACES = Map.of("fast", 15_000L, "normal", 40_000L, "slow", 120_000L);
    private static final long NIGHT_MS = 5 * 60_000;
    private static final long ACTING_MS = 3_000;
    private static final long PAUSED_MS = 30_000;
    private static final long FAILED_MS = 60_000;
    private static final long CALLED_MS = 2_000;
    private static final long NOTICED_MS = 8_000;
    private static final int MAX_BOREDOM = 3;
    private static final int RECENT_THOUGHTS = 4;
    private static final double SAME_THOUGHT = 0.35;
    private static final int MAX_ACTING_STREAK = 3;
    private static final int LINES_PER_PLACE = 3;
    private static final int MAX_ERRANDS = 5;
    private static final int MAX_PERCEIVED = 900;
    private static final LocalTime SLEEP_FROM = LocalTime.of(1, 0);
    private static final LocalTime WAKE_AT = LocalTime.of(8, 0);
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
    private final Deque<String> recent = new ArrayDeque<>();
    private final MindCues cues;

    public Mind(ChatAgent agent, QqChatBridge bridge, LogBuffer log) {
        this.agent = agent;
        this.bridge = bridge;
        this.log = log;
        this.cues = new MindCues(agent, bridge);
        bridge.setObserver(this);
        agent.setErrands(this::errand);
        agent.intercom().onNews(() -> stir(System.currentTimeMillis() + CALLED_MS));
    }

    private void errand(ChatAgent.Errand errand) {
        Intercom.Commitment c = agent.intercom().commit(errand.session(), errand.who(), errand.task());
        synchronized (places) {
            errands.add("【有人托你办事】#" + c.id() + " " + errand.who() + "在" + where(errand.session()) + "托你：" + abbreviate(errand.task(), 200)
                    + "（你已经在那边答应了。办完用 tell_" + (errand.session().startsWith("group:") ? "group" : "private")
                    + " 回那边说一声，再 settle #" + c.id() + "）");
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

    public void start() {
        Thread.ofPlatform().daemon().name("mind").start(this::loop);
    }

    public Status status() {
        SessionDetail detail = agent.session(SESSION, 0);
        return new Status(agent.store().freeWill(), awake(), thinking, agent.store().mindPace(), lastAt, nextAt, thoughts,
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

    private void loop() {
        while (true) {
            long pause;
            boolean blocked = false;
            try {
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
                        long due = blocked || !awake() ? until : Math.min(until, stirAt);
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
            ChatTurn turn = new ChatTurn(SESSION, ChatMessage.user(context(persona)), null, null, "mind", List.of(), Map.of(), true).withTools(tools);
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
        boolean bored = circling >= 2;
        if (!bored && (!calm || quiet < 2 || ThreadLocalRandom.current().nextInt(2) == 0)) {
            return null;
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
        if (!awake()) {
            actingStreak = 0;
            return Math.max(base, NIGHT_MS);
        }
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
        return abbreviate(String.join("\n", lines), MAX_PERCEIVED);
    }

    private String placeName(String where) {
        if (where.startsWith("group:")) {
            return "「" + bridge.groupName(where.substring(6)) + "」";
        }
        return "私聊里";
    }

    private String context(Persona persona) {
        String memories = agent.memories().recall(persona.id(), SESSION, List.of(), Set.copyOf(agent.personas().owners()));
        long last = bridge.lastActivity();
        List<Intercom.Commitment> open = agent.intercom().open();
        if (!open.isEmpty()) {
            StringBuilder hung = new StringBuilder("<心里挂着的事>\n");
            for (Intercom.Commitment c : open.subList(Math.max(0, open.size() - 8), open.size())) {
                hung.append("- #").append(c.id()).append(" ").append(c.who()).append("在").append(where(c.session())).append("托的：")
                        .append(c.what()).append("（").append(Clock.ago(c.createdAt())).append("）\n");
            }
            memories += hung.append("</心里挂着的事>\n\n");
        }
        return memories + "（现在是 " + Clock.now() + (awake() ? "" : "，半夜了，你在半睡半醒") + "。最近一次和人聊天是 "
                + (last <= 0 ? "很久以前" : Clock.ago(last)) + "；今天主动去群里说过 " + bridge.proactiveToday() + " 次。）\n顺着刚才的思绪接着往下想。";
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

    private static boolean awake() {
        LocalTime now = LocalTime.now();
        return !(now.isAfter(SLEEP_FROM) && now.isBefore(WAKE_AT));
    }
}
