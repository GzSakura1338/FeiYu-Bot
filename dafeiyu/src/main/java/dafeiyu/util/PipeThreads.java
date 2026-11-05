package dafeiyu.util;

public final class PipeThreads {

    private PipeThreads() {
    }

    public static Thread start(String name, Runnable reader) {
        return Thread.ofPlatform().name(name).daemon(true).start(reader);
    }
}
