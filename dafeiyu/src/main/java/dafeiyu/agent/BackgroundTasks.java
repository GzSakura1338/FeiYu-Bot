package dafeiyu.agent;

import com.google.gson.Gson;
import dafeiyu.agent.intercom.Intercom;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.agent.tool.Workspace;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dafeiyu.util.Texts.abbreviate;

public final class BackgroundTasks {

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String INTERRUPTED = "interrupted";
    public static final String PAUSED = "paused";
    public static final String QQ = "qq";
    public static final String RESEARCH = "research";
    public static final String BUILD = "build";
    public static final String MIND = "mind:self";
    static final String SUBAGENT = "subagent";

    public record Task(String id, String origin, String requester, String title, String status, long startedAt, long finishedAt,
                       String result, String kind) {

        public String session() {
            return "task:" + id;
        }

        public boolean unfinished() {
            return QUEUED.equals(status) || RUNNING.equals(status) || PAUSED.equals(status);
        }

        public String type() {
            return kind == null || kind.isBlank() ? QQ : kind;
        }

        public boolean subagent() {
            return !type().equals(QQ);
        }

        Task with(String next, String outcome) {
            boolean over = !next.equals(QUEUED) && !next.equals(RUNNING) && !next.equals(PAUSED);
            return new Task(id, origin, requester, title, next, startedAt, over ? System.currentTimeMillis() : 0, outcome, kind);
        }

        Task restarted() {
            return new Task(id, origin, requester, title, QUEUED, System.currentTimeMillis(), 0, "", kind);
        }
    }

    private static final class Control {
        volatile boolean paused;
        volatile boolean timedOut;
        volatile String stoppedBy;
        long remaining;
        long since = System.currentTimeMillis();
        ScheduledFuture<?> limit;

        Control(long limitMs) {
            this.remaining = limitMs;
        }
    }

    private static final class Pool {
        private int running;

        synchronized void acquire(IntSupplier limit) {
            while (running >= limit.getAsInt()) {
                try {
                    wait(2_000);
                } catch (InterruptedException ignored) {
                }
            }
            running++;
        }

        synchronized void release() {
            running--;
            notifyAll();
        }

        synchronized void wake() {
            notifyAll();
        }
    }

    private static final String KEY = "agent.tasks";
    private static final int MAX_KEPT = 100;
    private static final Gson GSON = new Gson();
    private static final long QQ_LIMIT_MS = 30 * 60_000;
    private static final long WRAP_UP_MS = 3 * 60_000;
    private static final int MAX_RETURN = 2_000;
    private static final Pattern FILE = Pattern.compile(
            "[\\w\\u4e00-\\u9fa5./\\\\-]+\\.(html?|zip|png|jpe?g|gif|svg|webp|txt|md|py|js|css|json|bat|cmd|ps1|java|pdf|docx?|xlsx?|csv|mp3|mp4)",
            Pattern.CASE_INSENSITIVE);
    private static final String RESEARCH_RULES = "用 websearch 搜、用 webfetch 打开网页读（只要其中一部分就加 find 写关键词），需要看图就用 view_image。"
            + "按最合理的理解直接查，不要停下来提问。你没有写文件的工具，要求里让你写文件、存报告的，在结论第一句说明「没写文件，结论都在这里」。查清楚后交回结论，是给她自己看的，用平常说话的口气写，不用表格和标题，1500 字以内："
            + "先一两句话说结论，再分点写要点和关键细节，最后列出用到的网址。"
            + "查不到、网站打不开就如实说，别编。";
    private static final String BUILD_RULES = "在工作目录里把它完整做完（写文件、改文件、查资料、运行检查都可以），按最合理的理解直接做，不要停下来提问。"
            + "做完后用两三句话说明做好了什么、怎么用，并写出做好的文件路径（相对工作目录）。";
    private static final String WRAP_UP = "时间到了，停下来，不要再用工具。把目前查到、做到的整理成结论交回：做到哪一步、已经确定的、还没弄完的。";
    private static final ToolSet NO_TOOLS = new ToolSet() {
        @Override
        public List<ToolSpec> specs() {
            return List.of();
        }

        @Override
        public Toolbox.Outcome run(ToolCall call) {
            return new Toolbox.Outcome("现在不能再用工具了", true);
        }

        @Override
        public int maxSteps() {
            return 1;
        }
    };

    private final ChatAgent agent;
    private final Settings settings;
    private final SubagentSettings subagents;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Map<String, Control> controls = new ConcurrentHashMap<>();
    private final Set<String> withdrawn = ConcurrentHashMap.newKeySet();
    private final Pool qqPool = new Pool();
    private final Pool mindPool = new Pool();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-timer");
        t.setDaemon(true);
        return t;
    });

    BackgroundTasks(ChatAgent agent, Database db) {
        this.agent = agent;
        this.settings = new Settings(db);
        this.subagents = new SubagentSettings(db);
        load();
    }

    private synchronized void load() {
        try {
            Task[] saved = GSON.fromJson(settings.get(KEY), Task[].class);
            for (Task task : saved == null ? new Task[0] : saved) {
                if (task == null || task.id() == null) {
                    continue;
                }
                if (task.unfinished() && task.subagent()) {
                    agent.intercom().report(MIND, "【子 agent 回来了】#" + task.id() + "「" + task.title() + "」程序重启了，没做完；还要的话用 subagent 的 continue 接着做");
                }
                tasks.put(task.id(), task.unfinished() ? task.with(INTERRUPTED, "程序重启了，没做完") : task);
            }
        } catch (RuntimeException ignored) {
        }
        save();
        for (Intercom.Commitment c : agent.intercom().open()) {
            if (c.what().startsWith("后台在做：")) {
                agent.intercom().settle(c.id(), Intercom.DROPPED, "程序重启了，后台没做完；还要的话重新交代一次");
            }
        }
    }

    public SubagentSettings subagents() {
        return subagents;
    }

    public void limitsChanged() {
        qqPool.wake();
        mindPool.wake();
    }

    public synchronized List<Task> list() {
        return List.copyOf(tasks.values());
    }

    public synchronized Task get(String session) {
        return session.startsWith("task:") ? tasks.get(session.substring(5)) : null;
    }

    private synchronized void put(Task task) {
        tasks.put(task.id(), task);
        while (tasks.size() > MAX_KEPT) {
            tasks.remove(tasks.keySet().iterator().next());
        }
        save();
    }

    private void save() {
        settings.put(KEY, GSON.toJson(tasks.values()));
    }

    private synchronized String newId() {
        long now = System.currentTimeMillis();
        String id = Long.toString(now, 36);
        while (tasks.containsKey(id)) {
            id = Long.toString(++now, 36);
        }
        return id;
    }

    public synchronized int spawnedToday() {
        long start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return (int) tasks.values().stream().filter(Task::subagent).filter(t -> t.startedAt() >= start).count();
    }

    public synchronized List<Task> subagentsAtWork() {
        return tasks.values().stream().filter(Task::subagent).filter(Task::unfinished).toList();
    }

    public static String label(String kind) {
        return switch (kind) {
            case RESEARCH -> "查资料";
            case BUILD -> "动手";
            default -> "后台任务";
        };
    }

    public String start(String origin, String requester, String requesterQq, String title, String instructions) {
        String id = newId();
        Task task = new Task(id, origin, requester, title, QUEUED, System.currentTimeMillis(), 0, "", QQ);
        put(task);
        Intercom.Commitment commitment = agent.intercom().commit(origin, requester, "后台在做：" + title);
        Thread.ofVirtual().name("task-" + id).start(() -> run(task, requesterQq, prompt(requester, title, instructions), commitment));
        return id;
    }

    public Task spawn(String kind, String title, String instructions) throws ToolFailure {
        SubagentSettings.Config config = subagents.config();
        if (!config.enabled()) {
            throw new ToolFailure("子 agent 没有打开");
        }
        if (!kind.equals(RESEARCH) && !kind.equals(BUILD)) {
            throw new ToolFailure("kind 只能是 research（查资料）或 build（动手）");
        }
        if (kind.equals(RESEARCH) && !config.research() || kind.equals(BUILD) && !config.build()) {
            throw new ToolFailure("「" + label(kind) + "」这种子 agent 没有打开");
        }
        if (kind.equals(BUILD) && agent.workspaces().workspace() == null) {
            throw new ToolFailure("还没有设工作目录，动手的子 agent 开不了");
        }
        if (config.dailyLimit() > 0 && spawnedToday() >= config.dailyLimit()) {
            throw new ToolFailure("今天已经开了 " + config.dailyLimit() + " 个子 agent，到上限了，明天再开；这件事先自己用 search / webfetch 查");
        }
        if (title.isBlank() || instructions.isBlank()) {
            throw new ToolFailure("title 和 task 都要写");
        }
        Task task = new Task(newId(), MIND, "内心", abbreviate(title.strip(), 40), QUEUED, System.currentTimeMillis(), 0, "", kind);
        put(task);
        String prompt = "你是在后台替她做事的子 agent，她交给你一件事：「" + task.title() + "」。\n要求：" + instructions.strip() + "\n\n"
                + (kind.equals(RESEARCH) ? RESEARCH_RULES : BUILD_RULES);
        Thread.ofVirtual().name("subagent-" + task.id()).start(() -> run(task, null, prompt, null));
        return task;
    }

    public String again(String key, String instructions) throws ToolFailure {
        String wanted = key == null ? "" : key.strip().replaceFirst("^#", "");
        Task task = get("task:" + wanted);
        if (task == null || !task.subagent()) {
            throw new ToolFailure("没有编号为 #" + wanted + " 的子 agent");
        }
        if (instructions.isBlank()) {
            throw new ToolFailure("要它接着做什么？task 里写清楚");
        }
        if (task.unfinished()) {
            agent.hear(task.session(), "【内心】" + instructions.strip());
            return "#" + task.id() + "「" + task.title() + "」还在做，已经把新要求转达给它了，它做下一步之前会看到。";
        }
        if (!subagents.config().enabled()) {
            throw new ToolFailure("子 agent 没有打开");
        }
        Task next = task.restarted();
        put(next);
        String prompt = "接着刚才的事：" + instructions.strip() + "\n\n"
                + (next.type().equals(RESEARCH) ? "做完照样交回结论和用到的网址。" : "做完照样说明做好了什么，写出文件路径。");
        Thread.ofVirtual().name("subagent-" + next.id()).start(() -> run(next, null, prompt, null));
        return "已经让 #" + next.id() + "「" + next.title() + "」接着做了，做好会告诉你。";
    }

    private long limitMs(Task task) {
        SubagentSettings.Config config = subagents.config();
        return switch (task.type()) {
            case RESEARCH -> config.researchMinutes() * 60_000L;
            case BUILD -> config.buildMinutes() * 60_000L;
            default -> QQ_LIMIT_MS;
        };
    }

    private void expire(Task task, Control control) {
        control.timedOut = true;
        agent.stop(task.session());
    }

    private void run(Task task, String requesterQq, String prompt, Intercom.Commitment commitment) {
        boolean sub = task.subagent();
        Pool pool = sub ? mindPool : qqPool;
        pool.acquire(() -> sub ? subagents.config().mindLimit() : subagents.config().qqLimit());
        String session = task.session();
        if (withdrawn.remove(task.id())) {
            pool.release();
            put(task.with(FAILED, "还没开始就被叫停了"));
            if (commitment != null) {
                agent.intercom().settle(commitment.id(), Intercom.DROPPED, "还没开始就被叫停了");
            }
            return;
        }
        Control control = new Control(limitMs(task));
        controls.put(task.id(), control);
        put(task.with(RUNNING, ""));
        control.limit = timer.schedule(() -> expire(task, control), control.remaining, TimeUnit.MILLISECONDS);
        try {
            ChatTurn turn = new ChatTurn(session, ChatMessage.user(prompt), requesterQq, null, sub ? SUBAGENT : "task");
            if (task.type().equals(RESEARCH)) {
                turn = turn.withTools(new ResearchTools(agent));
            }
            String summary = agent.chat(turn, StreamListener.NONE).content().strip();
            put(task.with(DONE, abbreviate(summary, 300)));
            if (sub) {
                returnToMind(task, "做好了", summary);
            } else {
                finishedForQq(task, summary, commitment);
            }
        } catch (AgentException e) {
            String why = control.stoppedBy != null ? "被" + control.stoppedBy + "叫停了"
                    : control.timedOut ? "超过 " + limitMs(task) / 60_000 + " 分钟没做完，到时间停下了" : e.getMessage();
            if (sub && control.timedOut && control.stoppedBy == null) {
                String partial = wrapUp(task);
                put(task.with(INTERRUPTED, abbreviate(why + (partial.isEmpty() ? "" : "：" + partial), 300)));
                returnToMind(task, why + "，先交回目前的结论", partial);
            } else {
                put(task.with(FAILED, abbreviate(why, 300)));
                if (sub) {
                    if (!"内心".equals(control.stoppedBy)) {
                        returnToMind(task, "没做成（" + why + "）", "");
                    }
                } else {
                    failedForQq(task, why, control.stoppedBy != null, commitment);
                }
            }
        } catch (RuntimeException e) {
            put(task.with(FAILED, abbreviate(e.toString(), 300)));
            if (sub) {
                returnToMind(task, "出错了，没做成", "");
            } else {
                if (commitment != null) {
                    agent.intercom().settle(commitment.id(), Intercom.DROPPED, "出错了：" + e);
                }
                agent.intercom().tell(task.origin(), "后台做的「" + task.title() + "」出错了，没做成。跟" + task.requester() + "如实说一声。");
            }
        } finally {
            synchronized (control) {
                control.limit.cancel(false);
            }
            controls.remove(task.id());
            pool.release();
        }
    }

    private void finishedForQq(Task task, String summary, Intercom.Commitment commitment) {
        List<String> files = files(summary);
        if (commitment != null) {
            agent.intercom().settle(commitment.id(), Intercom.DONE, abbreviate(summary, 160));
        }
        agent.intercom().tell(task.origin(), "后台做的「" + task.title() + "」做好了" + (files.isEmpty() ? "" : "，文件：" + String.join("、", files))
                + "。现在告诉" + task.requester() + "做好了" + (files.isEmpty() ? "" : "，并用 send_file 把文件发出去") + "。做的情况：" + summary);
    }

    private void failedForQq(Task task, String why, boolean stopped, Intercom.Commitment commitment) {
        if (commitment != null) {
            agent.intercom().settle(commitment.id(), Intercom.DROPPED, "没做成：" + why);
        }
        if (!stopped) {
            agent.intercom().tell(task.origin(), "后台做的「" + task.title() + "」没做成（" + abbreviate(why, 80) + "）。跟" + task.requester()
                    + "如实说一声，问问要不要换个做法再试。");
        }
    }

    private String wrapUp(Task task) {
        ScheduledFuture<?> cap = timer.schedule(() -> agent.stop(task.session()), WRAP_UP_MS, TimeUnit.MILLISECONDS);
        try {
            ChatTurn turn = new ChatTurn(task.session(), ChatMessage.user(WRAP_UP), null, null, SUBAGENT).withTools(NO_TOOLS);
            return agent.chat(turn, StreamListener.NONE).content().strip();
        } catch (AgentException | RuntimeException e) {
            return "";
        } finally {
            cap.cancel(false);
        }
    }

    private void returnToMind(Task task, String state, String text) {
        String body = text.strip();
        boolean cut = body.length() > MAX_RETURN;
        List<String> files = cut && task.type().equals(BUILD) ? files(body) : List.of();
        agent.subagentReturned("【子 agent 回来了】#" + task.id() + "「" + task.title() + "」（" + label(task.type()) + "）" + state
                + (body.isEmpty() ? "" : "：" + (cut ? body.substring(0, MAX_RETURN - 500) + "\n…（中间略）…\n" + body.substring(body.length() - 450) : body))
                + (files.isEmpty() ? "" : "（文件：" + String.join("、", files) + "）"));
    }

    public synchronized List<Task> unfinished(String origin) {
        return tasks.values().stream().filter(Task::unfinished).filter(t -> origin == null || t.origin().equals(origin)).toList();
    }

    public boolean paused(String session) {
        Control control = session.startsWith("task:") ? controls.get(session.substring(5)) : null;
        return control != null && control.paused;
    }

    public static String describe(List<Task> list) {
        StringBuilder out = new StringBuilder();
        for (Task t : list) {
            out.append(out.isEmpty() ? "" : "；").append('#').append(t.id()).append("「").append(t.title()).append("」")
                    .append(t.subagent() ? "（子 agent·" + label(t.type()) + "）" : "")
                    .append(PAUSED.equals(t.status()) ? "暂停着" : QUEUED.equals(t.status()) ? "在排队" : "在做");
        }
        return out.toString();
    }

    public String steer(String key, String action, String message, String from, String origin) throws ToolFailure {
        List<Task> open = unfinished(origin);
        String wanted = key == null ? "" : key.strip().replaceFirst("^#", "");
        List<Task> matches = open.stream().filter(t -> t.id().equals(wanted) || !wanted.isEmpty() && t.title().contains(wanted)).toList();
        if (wanted.isEmpty() && open.size() == 1) {
            matches = open;
        }
        if (matches.size() != 1) {
            throw new ToolFailure((matches.isEmpty() ? "没找到这个后台任务" : "有好几个对得上，写编号") + "。现在后台的："
                    + (open.isEmpty() ? "没有在做的" : describe(open)));
        }
        Task task = matches.getFirst();
        Control control = controls.get(task.id());
        return switch (action == null ? "" : action.strip()) {
            case "tell" -> {
                if (message == null || message.isBlank()) {
                    throw new ToolFailure("要转达的话是空的");
                }
                agent.hear(task.session(), "【" + from + "】" + message.strip());
                yield "已经转达给「" + task.title() + "」，它做下一步之前会看到" + (control != null && control.paused ? "；它现在暂停着，要它接着做就 resume" : "") + "。";
            }
            case "pause" -> {
                if (control == null) {
                    throw new ToolFailure("「" + task.title() + "」还在排队，没开始，不用暂停");
                }
                synchronized (control) {
                    if (!control.paused) {
                        control.paused = true;
                        control.limit.cancel(false);
                        control.remaining -= System.currentTimeMillis() - control.since;
                    }
                }
                put(task.with(PAUSED, ""));
                yield "「" + task.title() + "」做完手上这一步就停下来等着，要它接着做就 resume。";
            }
            case "resume" -> {
                if (control == null || !control.paused) {
                    throw new ToolFailure("「" + task.title() + "」没有暂停");
                }
                synchronized (control) {
                    control.paused = false;
                    control.since = System.currentTimeMillis();
                    control.limit = timer.schedule(() -> expire(task, control), Math.max(60_000, control.remaining), TimeUnit.MILLISECONDS);
                }
                put(task.with(RUNNING, ""));
                yield "「" + task.title() + "」接着做了。";
            }
            case "stop" -> {
                if (control == null) {
                    withdrawn.add(task.id());
                    put(task.with(FAILED, "还没开始就被" + from + "叫停了"));
                } else {
                    control.stoppedBy = from;
                    agent.stop(task.session());
                }
                yield "「" + task.title() + "」叫停了。";
            }
            default -> throw new ToolFailure("action 只能是 tell、pause、resume、stop");
        };
    }

    private static String prompt(String requester, String title, String instructions) {
        return "你在后台帮" + requester + "做一件事：「" + title + "」。\n要求：" + instructions + "\n\n" + BUILD_RULES;
    }

    List<String> files(String text) {
        Workspace workspace = agent.workspaces().workspace();
        if (workspace == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = FILE.matcher(text);
        while (m.find() && out.size() < 5) {
            String path = m.group().replace('\\', '/').replaceAll("^\\./", "");
            try {
                workspace.existingFile(path);
                out.add(path);
            } catch (ToolFailure ignored) {
            }
        }
        return new ArrayList<>(out);
    }
}
