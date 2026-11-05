package dafeiyu.util;

/**
 * 长时间阻塞读子进程管道的线程，用平台线程，不用虚拟线程。
 *
 * <p>虚拟线程读管道（FileInputStream / 本机 ReadFile）时不会让出载体线程，JDK 只会临时补一个载体；
 * 补出来的载体空闲约 1 分钟就被回收，读的那个还在阻塞，载体就一直被占着。载体数默认等于 CPU 核数，
 * 每个插件常驻两个读线程（stdout 和 stderr），插件一多载体被占满，控制台、聊天等所有虚拟线程任务都排不上队，
 * 表现为控制台能连上但一直没响应。4 核机器上两个插件就会出现。
 */
public final class PipeThreads {

    private PipeThreads() {
    }

    /** 起一个读管道的守护平台线程。 */
    public static Thread start(String name, Runnable reader) {
        return Thread.ofPlatform().name(name).daemon(true).start(reader);
    }
}
