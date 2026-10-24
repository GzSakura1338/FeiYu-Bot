package dafeiyu.sandbox;

import dafeiyu.agent.tool.ToolFailure;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface Sandbox {

    record Spec(List<String> command, String shell, Path cwd, Map<String, String> env, String profile, boolean isolated, boolean network,
                List<Path> readable, List<Path> shared, List<Path> writable, long memoryBytes, int cpuPercent, int maxProcesses) {

        public static Spec exec(List<String> command, Path cwd, Map<String, String> env) {
            return new Spec(List.copyOf(command), null, cwd, env, "", false, true, List.of(), List.of(), List.of(), 1024L << 20, 80, 64);
        }

        public static Spec shell(String line, Path cwd, Map<String, String> env) {
            return new Spec(List.of(), line, cwd, env, "", false, true, List.of(), List.of(), List.of(), 1024L << 20, 80, 64);
        }

        public Spec isolated(boolean on, String name, boolean net) {
            return new Spec(command, shell, cwd, env, name, on, net, readable, shared, writable, memoryBytes, cpuPercent, maxProcesses);
        }

        public Spec reading(List<Path> own, List<Path> common) {
            return new Spec(command, shell, cwd, env, profile, isolated, network, List.copyOf(own), List.copyOf(common), writable, memoryBytes,
                    cpuPercent, maxProcesses);
        }

        public Spec writing(List<Path> paths) {
            return new Spec(command, shell, cwd, env, profile, isolated, network, readable, shared, List.copyOf(paths), memoryBytes, cpuPercent,
                    maxProcesses);
        }

        public Spec limits(long memory, int cpu, int processes) {
            return new Spec(command, shell, cwd, env, profile, isolated, network, readable, shared, writable, memory, cpu, processes);
        }
    }

    record Result(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated, long millis) {}

    interface Running extends AutoCloseable {

        InputStream stdout();

        InputStream stderr();

        OutputStream stdin();

        long pid();

        boolean alive();

        int exitCode();

        boolean waitFor(long millis);

        void kill();

        @Override
        void close();
    }

    String kind();

    String label();

    String missing();

    default boolean isolates() {
        return missing() == null;
    }

    String shellName();

    boolean reachesLan();

    default String networkMissing() {
        return null;
    }

    Map<String, String> environment(Path root, List<Path> extraPath) throws ToolFailure;

    String quote(List<String> args);

    Result run(Spec spec, long timeoutMs) throws ToolFailure;

    Running spawn(Spec spec) throws ToolFailure;

    default void warm(List<Path> shared) {
    }
}
