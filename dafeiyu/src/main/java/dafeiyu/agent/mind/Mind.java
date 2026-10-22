package dafeiyu.agent.mind;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.ChatTurn;
import dafeiyu.agent.SessionDetail;
import dafeiyu.agent.StreamListener;
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
    private int actingStreak;

    public Mind(ChatAgent agent, QqChatBridge bridge, LogBuffer log) {
        this.agent = agent;
        this.bridge = bridge;
        this.log = log;
        bridge.setObserver(this);
        agent.setErrands(this::errand);
    }

    private void errand(ChatAgent.Errand errand) {
        synchronized (places) {
            errands.add("【有人托你办事】" + errand.who() + "在" + where(errand.session()) + "托你：" + abbreviate(errand.task(), 200)
                    + "（你已经在那边答应了。办完可以回那边说一声：私聊用 say_private 给 QQ " + errand.userId()
                    + "，群里用 say_in_group）");
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
            place.lines.addLast(who + "：" + abbreviate(text, 60));
            while (place.lines.size() > LINES_PER_PLACE) {
                place.lines.pollFirst();
            }
        }
    }

    @Override
    public void said(String where, String text) {
        synchronized (places) {
            own.add("你刚在" + placeName(where) + "说了：" + abbreviate(text.replace('\n', ' '), 60));
            while (own.size() > 4) {
                own.removeFirst();
            }
        }
    }

    private void loop() {
        while (true) {
            long pause;
            try {
                boolean asked = wake;
                if (blocker() != null || (!agent.store().freeWill() && !asked)) {
                    pause = PAUSED_MS;
                } else {
                    wake = false;
                    pause = step();
                }
            } catch (RuntimeException e) {
                log.warn("mind: a thought failed: " + e);
                pause = FAILED_MS;
            }
            nextAt = System.currentTimeMillis() + pause;
            synchronized (bell) {
                if (!wake) {
                    try {
                        bell.wait(pause);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    private long step() {
        thinking = true;
        try {
            Persona persona = agent.personas().active();
            String perceived = drain();
            agent.hear(SESSION, "【" + LocalTime.now().format(CLOCK) + "】" + (perceived.isEmpty() ? "没什么新动静。" : "\n" + perceived));
            MindTools tools = new MindTools(agent, bridge, persona.id());
            ChatTurn turn = new ChatTurn(SESSION, ChatMessage.user(context(persona)), null, null, "mind", List.of(), Map.of(), true).withTools(tools);
            ChatResult result = agent.chat(turn, StreamListener.NONE);
            String thought = abbreviate(result.content().replaceAll("\\s+", " ").strip(), 160);
            if (!thought.isEmpty() && agent.guard().scan(thought) == null) {
                agent.mood().remember(persona.id(), thought, SESSION);
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
        return eventful ? base / 2 : base;
    }

    private boolean pending() {
        synchronized (places) {
            return !places.isEmpty() || !own.isEmpty() || !errands.isEmpty();
        }
    }

    private String drain() {
        List<String> lines = new ArrayList<>();
        synchronized (places) {
            lines.addAll(errands);
            errands.clear();
            lines.addAll(own);
            own.clear();
            for (Map.Entry<String, Place> e : places.entrySet()) {
                Place p = e.getValue();
                String head = e.getKey().startsWith("private:")
                        ? "私聊里有人找你" + (p.count > 1 ? "（" + p.count + " 条）" : "")
                        : placeName(e.getKey()) + "来了 " + p.count + " 条新消息" + (p.toMe ? "，有人在叫你" : "");
                lines.add(head + "：" + String.join("；", p.lines));
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
