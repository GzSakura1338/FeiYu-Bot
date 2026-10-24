package dafeiyu.sandbox;

import dafeiyu.agent.tool.ToolFailure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class Processes {

    static final int MAX_CAPTURE = 200_000;

    private Processes() {}

    static Process start(List<String> argv, Path cwd, Map<String, String> env) throws ToolFailure {
        ProcessBuilder builder = new ProcessBuilder(argv).directory(cwd.toFile());
        builder.environment().clear();
        builder.environment().putAll(env);
        try {
            return builder.start();
        } catch (IOException e) {
            throw new ToolFailure("Could not run the command: " + e.getMessage());
        }
    }

    static Sandbox.Result run(List<String> argv, Path cwd, Map<String, String> env, long timeoutMs) throws ToolFailure {
        return wait(start(argv, cwd, env), timeoutMs);
    }

    static Sandbox.Result wait(Process process, long timeoutMs) throws ToolFailure {
        long started = System.nanoTime();
        try {
            process.getOutputStream().close();
            Capture out = new Capture(process.getInputStream());
            Capture err = new Capture(process.getErrorStream());
            Thread outThread = Thread.ofVirtual().start(out);
            Thread errThread = Thread.ofVirtual().start(err);
            boolean done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                killTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outThread.join(2000);
            errThread.join(2000);
            long millis = (System.nanoTime() - started) / 1_000_000;
            return new Sandbox.Result(done ? process.exitValue() : -1, out.text(), err.text(), !done, out.truncated || err.truncated, millis);
        } catch (IOException e) {
            killTree(process);
            throw new ToolFailure("Could not run the command: " + e.getMessage());
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new ToolFailure("Cancelled");
        }
    }

    static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            String name = System.getProperty("native.encoding", "");
            try {
                return new String(bytes, name.isBlank() ? Charset.defaultCharset() : Charset.forName(name));
            } catch (RuntimeException ignored) {
                return new String(bytes, Charset.defaultCharset());
            }
        }
    }

    static final class Running implements Sandbox.Running {
        private final Process process;

        Running(Process process) {
            this.process = process;
        }

        @Override
        public InputStream stdout() {
            return process.getInputStream();
        }

        @Override
        public InputStream stderr() {
            return process.getErrorStream();
        }

        @Override
        public OutputStream stdin() {
            return process.getOutputStream();
        }

        @Override
        public long pid() {
            return process.pid();
        }

        @Override
        public boolean alive() {
            return process.isAlive();
        }

        @Override
        public int exitCode() {
            return process.isAlive() ? -1 : process.exitValue();
        }

        @Override
        public boolean waitFor(long millis) {
            try {
                return process.waitFor(Math.max(0, millis), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public void kill() {
            killTree(process);
        }

        @Override
        public void close() {
            kill();
            waitFor(2_000);
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
            try {
                process.getErrorStream().close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class Capture implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private volatile boolean truncated;

        Capture(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (InputStream in = stream) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (data.size() >= MAX_CAPTURE) {
                        truncated = true;
                        continue;
                    }
                    data.write(buffer, 0, Math.min(n, MAX_CAPTURE - data.size()));
                }
            } catch (IOException ignored) {
            }
        }

        String text() {
            return decode(data.toByteArray());
        }
    }
}
