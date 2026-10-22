package dafeiyu.agent;

import dafeiyu.agent.intercom.Intercom;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.agent.tool.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dafeiyu.util.Texts.abbreviate;

public final class BackgroundTasks {

    private static final int MAX_RUNNING = 2;
    private static final long TIME_LIMIT_MS = 30 * 60_000;
    private static final Pattern FILE = Pattern.compile(
            "[\\w\\u4e00-\\u9fa5./\\\\-]+\\.(html?|zip|png|jpe?g|gif|svg|webp|txt|md|py|js|css|json|bat|cmd|ps1|java|pdf|docx?|xlsx?|csv|mp3|mp4)",
            Pattern.CASE_INSENSITIVE);

    private final ChatAgent agent;
    private final Semaphore slots = new Semaphore(MAX_RUNNING);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-timer");
        t.setDaemon(true);
        return t;
    });

    BackgroundTasks(ChatAgent agent) {
        this.agent = agent;
    }

    public String start(String origin, String requester, String requesterQq, String title, String instructions) {
        String id = Long.toString(System.currentTimeMillis(), 36);
        Intercom.Commitment commitment = agent.intercom().commit(origin, requester, "后台在做：" + title);
        Thread.ofVirtual().name("task-" + id).start(() -> run(id, origin, requester, requesterQq, title, instructions, commitment));
        return id;
    }

    private void run(String id, String origin, String requester, String requesterQq, String title, String instructions,
                     Intercom.Commitment commitment) {
        slots.acquireUninterruptibly();
        String session = "task:" + id;
        ScheduledFuture<?> limit = timer.schedule(() -> agent.stop(session), TIME_LIMIT_MS, TimeUnit.MILLISECONDS);
        try {
            ChatTurn turn = new ChatTurn(session, ChatMessage.user(prompt(requester, title, instructions)), requesterQq, null, "task");
            String summary = agent.chat(turn, StreamListener.NONE).content().strip();
            List<String> files = files(summary);
            agent.intercom().settle(commitment.id(), Intercom.DONE, abbreviate(summary, 160));
            agent.intercom().tell(origin, "后台做的「" + title + "」做好了" + (files.isEmpty() ? "" : "，文件：" + String.join("、", files))
                    + "。现在告诉" + requester + "做好了" + (files.isEmpty() ? "" : "，并用 send_file 把文件发出去") + "。做的情况：" + summary);
        } catch (AgentException e) {
            agent.intercom().settle(commitment.id(), Intercom.DROPPED, "没做成：" + e.getMessage());
            agent.intercom().tell(origin, "后台做的「" + title + "」没做成（" + abbreviate(e.getMessage(), 80) + "）。跟" + requester
                    + "如实说一声，问问要不要换个做法再试。");
        } catch (RuntimeException e) {
            agent.intercom().settle(commitment.id(), Intercom.DROPPED, "出错了：" + e);
            agent.intercom().tell(origin, "后台做的「" + title + "」出错了，没做成。跟" + requester + "如实说一声。");
        } finally {
            limit.cancel(false);
            slots.release();
        }
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
