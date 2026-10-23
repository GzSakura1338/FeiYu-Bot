package dafeiyu.agent;

import com.google.gson.Gson;
import dafeiyu.agent.intercom.Intercom;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Workspace;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;

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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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

    public record Task(String id, String origin, String requester, String title, String status, long startedAt, long finishedAt,
                       String result) {

        public String session() {
            return "task:" + id;
        }

        public boolean unfinished() {
            return QUEUED.equals(status) || RUNNING.equals(status) || PAUSED.equals(status);
        }

        Task with(String next, String outcome) {
            boolean over = !next.equals(QUEUED) && !next.equals(RUNNING) && !next.equals(PAUSED);
            return new Task(id, origin, requester, title, next, startedAt, over ? System.currentTimeMillis() : 0, outcome);
        }
    }

    private static final class Control {
        volatile boolean paused;
        volatile String stoppedBy;
        long remaining = TIME_LIMIT_MS;
        long since = System.currentTimeMillis();
        ScheduledFuture<?> limit;
    }

    private static final String KEY = "agent.tasks";
    private static final int MAX_KEPT = 100;
    private static final Gson GSON = new Gson();
    private static final int MAX_RUNNING = 2;
    private static final long TIME_LIMIT_MS = 30 * 60_000;
    private static final Pattern FILE = Pattern.compile(
            "[\\w\\u4e00-\\u9fa5./\\\\-]+\\.(html?|zip|png|jpe?g|gif|svg|webp|txt|md|py|js|css|json|bat|cmd|ps1|java|pdf|docx?|xlsx?|csv|mp3|mp4)",
            Pattern.CASE_INSENSITIVE);

    private final ChatAgent agent;
    private final Settings settings;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Map<String, Control> controls = new ConcurrentHashMap<>();
    private final Set<String> withdrawn = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(MAX_RUNNING);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-timer");
        t.setDaemon(true);
        return t;
    });

    BackgroundTasks(ChatAgent agent, Database db) {
        this.agent = agent;
        this.settings = new Settings(db);
        load();
    }

    private synchronized void load() {
        try {
            Task[] saved = GSON.fromJson(settings.get(KEY), Task[].class);
            for (Task task : saved == null ? new Task[0] : saved) {
                if (task != null && task.id() != null) {
                    tasks.put(task.id(), task.unfinished() ? task.with(INTERRUPTED, "程序重启了，没做完") : task);
                }
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

    public String start(String origin, String requester, String requesterQq, String title, String instructions) {
        String id = Long.toString(System.currentTimeMillis(), 36);
        Task task = new Task(id, origin, requester, title, QUEUED, System.currentTimeMillis(), 0, "");
        put(task);
        Intercom.Commitment commitment = agent.intercom().commit(origin, requester, "后台在做：" + title);
        Thread.ofVirtual().name("task-" + id).start(() -> run(task, requesterQq, instructions, commitment));
        return id;
    }

    private void run(Task task, String requesterQq, String instructions, Intercom.Commitment commitment) {
        slots.acquireUninterruptibly();
        String session = task.session();
        String origin = task.origin();
        String requester = task.requester();
        String title = task.title();
        if (withdrawn.remove(task.id())) {
            slots.release();
            put(task.with(FAILED, "还没开始就被叫停了"));
            agent.intercom().settle(commitment.id(), Intercom.DROPPED, "还没开始就被叫停了");
            return;
        }
        Control control = new Control();
        controls.put(task.id(), control);
        put(task.with(RUNNING, ""));
        control.limit = timer.schedule(() -> agent.stop(session), TIME_LIMIT_MS, TimeUnit.MILLISECONDS);
        try {
            ChatTurn turn = new ChatTurn(session, ChatMessage.user(prompt(requester, title, instructions)), requesterQq, null, "task");
            String summary = agent.chat(turn, StreamListener.NONE).content().strip();
            List<String> files = files(summary);
            put(task.with(DONE, abbreviate(summary, 300)));
            agent.intercom().settle(commitment.id(), Intercom.DONE, abbreviate(summary, 160));
            agent.intercom().tell(origin, "后台做的「" + title + "」做好了" + (files.isEmpty() ? "" : "，文件：" + String.join("、", files))
                    + "。现在告诉" + requester + "做好了" + (files.isEmpty() ? "" : "，并用 send_file 把文件发出去") + "。做的情况：" + summary);
        } catch (AgentException e) {
            String why = control.stoppedBy != null ? "被" + control.stoppedBy + "叫停了" : e.getMessage();
            put(task.with(FAILED, abbreviate(why, 300)));
            agent.intercom().settle(commitment.id(), Intercom.DROPPED, "没做成：" + why);
            if (control.stoppedBy == null) {
                agent.intercom().tell(origin, "后台做的「" + title + "」没做成（" + abbreviate(why, 80) + "）。跟" + requester
                        + "如实说一声，问问要不要换个做法再试。");
            }
        } catch (RuntimeException e) {
            put(task.with(FAILED, abbreviate(e.toString(), 300)));
            agent.intercom().settle(commitment.id(), Intercom.DROPPED, "出错了：" + e);
            agent.intercom().tell(origin, "后台做的「" + title + "」出错了，没做成。跟" + requester + "如实说一声。");
        } finally {
            synchronized (control) {
                control.limit.cancel(false);
            }
            controls.remove(task.id());
            slots.release();
        }
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
                    control.limit = timer.schedule(() -> agent.stop(task.session()), Math.max(60_000, control.remaining), TimeUnit.MILLISECONDS);
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
        return "你在后台帮" + requester + "做一件事：「" + title + "」。\n要求：" + instructions + "\n\n"
                + "在工作目录里把它完整做完（写文件、改文件、查资料、运行检查都可以），按最合理的理解直接做，不要停下来提问。"
                + "做完后用两三句话说明做好了什么、怎么用，并写出做好的文件路径（相对工作目录）。";
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
