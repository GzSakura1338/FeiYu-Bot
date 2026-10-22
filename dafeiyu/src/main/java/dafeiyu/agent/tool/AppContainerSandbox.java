package dafeiyu.agent.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class AppContainerSandbox {

    record Result(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated, long millis) {}

    static final String CONTAINER = "dafeiyu.sandbox";
    private static final int ALREADY_EXISTS = 0x800700B7;
    private static final long PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x20009;
    private static final long PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x20002;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x80000;
    private static final int CREATE_SUSPENDED = 0x4;
    private static final int CREATE_NO_WINDOW = 0x8000000;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x400;
    private static final int CREATE_BREAKAWAY_FROM_JOB = 0x1000000;
    private static final int STARTF_USESTDHANDLES = 0x100;
    private static final int HANDLE_FLAG_INHERIT = 1;
    private static final int WAIT_TIMEOUT = 0x102;
    private static final int JOB_EXTENDED_LIMIT = 9;
    private static final int JOB_UI_RESTRICTIONS = 4;
    private static final int JOB_CPU_RATE = 15;
    private static final int LIMIT_ACTIVE_PROCESS = 0x8;
    private static final int LIMIT_PRIORITY_CLASS = 0x20;
    private static final int LIMIT_JOB_MEMORY = 0x200;
    private static final int LIMIT_DIE_ON_UNHANDLED_EXCEPTION = 0x400;
    private static final int LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    private static final int BELOW_NORMAL_PRIORITY_CLASS = 0x4000;
    private static final int UI_LIMIT_ALL = 0xFF;
    private static final int CPU_RATE_ENABLE = 0x1;
    private static final int CPU_RATE_HARD_CAP = 0x4;
    private static final int MAX_PROCESSES = 64;
    private static final long MAX_MEMORY = 1024L * 1024 * 1024;
    private static final int CPU_PERCENT = 50;
    static final int MAX_CAPTURE = 200_000;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena GLOBAL = Arena.global();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", GLOBAL);
    private static final SymbolLookup USERENV = SymbolLookup.libraryLookup("userenv", GLOBAL);
    private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32", GLOBAL);
    private static final MemoryLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle LAST_ERROR = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

    private static final MethodHandle CREATE_PROFILE = fn(USERENV, "CreateAppContainerProfile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle DERIVE_SID = fn(USERENV, "DeriveAppContainerSidFromAppContainerName",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SID_TO_STRING = fn(ADVAPI32, "ConvertSidToStringSidW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle LOCAL_FREE = fn(KERNEL32, "LocalFree", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_PIPE = fn(KERNEL32, "CreatePipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle SET_HANDLE_INFO = fn(KERNEL32, "SetHandleInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle INIT_ATTRIBUTES = fn(KERNEL32, "InitializeProcThreadAttributeList",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle UPDATE_ATTRIBUTE = fn(KERNEL32, "UpdateProcThreadAttribute",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle DELETE_ATTRIBUTES = fn(KERNEL32, "DeleteProcThreadAttributeList", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CREATE_PROCESS = LINKER.downcallHandle(KERNEL32.find("CreateProcessW").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
            Linker.Option.captureCallState("GetLastError"));
    private static final MethodHandle CREATE_JOB = fn(KERNEL32, "CreateJobObjectW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SET_JOB_INFO = fn(KERNEL32, "SetInformationJobObject",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle ASSIGN_JOB = fn(KERNEL32, "AssignProcessToJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle RESUME_THREAD = fn(KERNEL32, "ResumeThread", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle WAIT = fn(KERNEL32, "WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle TERMINATE_JOB = fn(KERNEL32, "TerminateJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle TERMINATE_PROCESS = fn(KERNEL32, "TerminateProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle EXIT_CODE =fn(KERNEL32, "GetExitCodeProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle READ_FILE = fn(KERNEL32, "ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CLOSE = fn(KERNEL32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static MemorySegment sid;
    private static String sidString;

    private AppContainerSandbox() {}

    private static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new IllegalStateException("missing " + name)), descriptor);
    }

    static synchronized String sid() throws ToolFailure {
        if (sidString != null) {
            return sidString;
        }
        try {
            MemorySegment out = GLOBAL.allocate(ADDRESS);
            int hr = (int) CREATE_PROFILE.invokeExact(wide(GLOBAL, CONTAINER), wide(GLOBAL, "dafeiyu sandbox"),
                    wide(GLOBAL, "Sandbox for the dafeiyu agent shell tool"), MemorySegment.NULL, 0, out);
            if (hr == ALREADY_EXISTS) {
                hr = (int) DERIVE_SID.invokeExact(wide(GLOBAL, CONTAINER), out);
            }
            if (hr != 0) {
                throw new ToolFailure("Unable to create the sandbox container (HRESULT 0x" + Integer.toHexString(hr) + ")");
            }
            sid = out.get(ADDRESS, 0);
            MemorySegment text = GLOBAL.allocate(ADDRESS);
            if ((int) SID_TO_STRING.invokeExact(sid, text) == 0) {
                throw new ToolFailure("Unable to read the sandbox container SID");
            }
            MemorySegment chars = text.get(ADDRESS, 0);
            sidString = chars.reinterpret(512).getString(0, StandardCharsets.UTF_16LE);
            MemorySegment ignored = (MemorySegment) LOCAL_FREE.invokeExact(chars);
            return sidString;
        } catch (ToolFailure e) {
            throw e;
        } catch (Throwable e) {
            throw new ToolFailure("Unable to set up the sandbox: " + e);
        }
    }

    static void grant(Path path, boolean write) throws ToolFailure {
        String account = "*" + sid();
        List<String> command = List.of("icacls", path.toString(), "/grant", account + ":(OI)(CI)" + (write ? "M" : "RX"), "/Q", "/C");
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset());
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new ToolFailure("Unable to give the sandbox access to " + path + ": " + out.strip());
            }
        } catch (IOException e) {
            throw new ToolFailure("Unable to run icacls: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("Cancelled");
        }
    }

    static Result run(String commandLine, Path cwd, Map<String, String> env, long timeoutMs) throws ToolFailure {
        sid();
        long started = System.nanoTime();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sa = arena.allocate(24);
            sa.set(JAVA_INT, 0, 24);
            sa.set(JAVA_INT, 16, 1);
            MemorySegment[] out = pipe(arena, sa);
            MemorySegment[] err = pipe(arena, sa);
            MemorySegment[] in = pipe(arena, sa);
            noInherit(out[0]);
            noInherit(err[0]);
            noInherit(in[1]);
            MemorySegment job = MemorySegment.NULL;
            MemorySegment process = MemorySegment.NULL;
            MemorySegment thread = MemorySegment.NULL;
            MemorySegment attributes = MemorySegment.NULL;
            try {
                MemorySegment size = arena.allocate(JAVA_LONG);
                int ignored = (int) INIT_ATTRIBUTES.invokeExact(MemorySegment.NULL, 2, 0, size);
                attributes = arena.allocate(size.get(JAVA_LONG, 0), 16);
                check((int) INIT_ATTRIBUTES.invokeExact(attributes, 2, 0, size), "InitializeProcThreadAttributeList");
                MemorySegment caps = arena.allocate(24);
                caps.set(ADDRESS, 0, sid);
                caps.set(ADDRESS, 8, MemorySegment.NULL);
                caps.set(JAVA_INT, 16, 0);
                caps.set(JAVA_INT, 20, 0);
                check((int) UPDATE_ATTRIBUTE.invokeExact(attributes, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, caps, 24L,
                        MemorySegment.NULL, MemorySegment.NULL), "UpdateProcThreadAttribute(security)");
                MemorySegment handles = arena.allocate(24);
                handles.set(ADDRESS, 0, in[0]);
                handles.set(ADDRESS, 8, out[1]);
                handles.set(ADDRESS, 16, err[1]);
                check((int) UPDATE_ATTRIBUTE.invokeExact(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handles, 24L,
                        MemorySegment.NULL, MemorySegment.NULL), "UpdateProcThreadAttribute(handles)");

                MemorySegment startup = arena.allocate(112);
                startup.set(JAVA_INT, 0, 112);
                startup.set(JAVA_INT, 60, STARTF_USESTDHANDLES);
                startup.set(ADDRESS, 80, in[0]);
                startup.set(ADDRESS, 88, out[1]);
                startup.set(ADDRESS, 96, err[1]);
                startup.set(ADDRESS, 104, attributes);
                MemorySegment info = arena.allocate(24);
                MemorySegment state = arena.allocate(CALL_STATE);
                int flags = EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT | CREATE_BREAKAWAY_FROM_JOB;
                int ok = (int) CREATE_PROCESS.invokeExact(state, MemorySegment.NULL, wide(arena, commandLine), MemorySegment.NULL,
                        MemorySegment.NULL, 1, flags, environment(arena, env), wide(arena, cwd.toString()), startup, info);
                if (ok == 0) {
                    int code = (int) LAST_ERROR.get(state, 0L);
                    if (code == 5) {
                        ok = (int) CREATE_PROCESS.invokeExact(state, MemorySegment.NULL, wide(arena, commandLine), MemorySegment.NULL,
                                MemorySegment.NULL, 1, flags & ~CREATE_BREAKAWAY_FROM_JOB, environment(arena, env), wide(arena, cwd.toString()), startup, info);
                        code = (int) LAST_ERROR.get(state, 0L);
                    }
                    if (ok == 0) {
                        throw new ToolFailure("Unable to start the sandboxed process (Windows error " + code + ")");
                    }
                }
                process = info.get(ADDRESS, 0);
                thread = info.get(ADDRESS, 8);
                job = (MemorySegment) CREATE_JOB.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
                if (job.address() == 0) {
                    terminate(process);
                    throw new ToolFailure("Unable to create the job object for the sandbox");
                }
                limit(arena, job);
                if ((int) ASSIGN_JOB.invokeExact(job, process) == 0) {
                    terminate(process);
                    throw new ToolFailure("Unable to put the sandboxed process into its job");
                }
                close(out[1]);
                close(err[1]);
                close(in[0]);
                close(in[1]);
                out[1] = err[1] = in[0] = in[1] = MemorySegment.NULL;
                int resumed = (int) RESUME_THREAD.invokeExact(thread);
                Capture stdout = new Capture(out[0]);
                Capture stderr = new Capture(err[0]);
                Thread a = Thread.ofVirtual().start(stdout);
                Thread b = Thread.ofVirtual().start(stderr);
                int waited = (int) WAIT.invokeExact(process, (int) Math.min(Integer.MAX_VALUE, timeoutMs));
                boolean timedOut = waited == WAIT_TIMEOUT;
                int killed = (int) TERMINATE_JOB.invokeExact(job, timedOut ? 1 : 0);
                a.join(5_000);
                b.join(5_000);
                MemorySegment exit = arena.allocate(JAVA_INT);
                int got = (int) EXIT_CODE.invokeExact(process, exit);
                long millis = (System.nanoTime() - started) / 1_000_000;
                return new Result(timedOut ? -1 : exit.get(JAVA_INT, 0), stdout.text(), stderr.text(), timedOut,
                        stdout.truncated || stderr.truncated, millis);
            } finally {
                if (attributes.address() != 0) {
                    DELETE_ATTRIBUTES.invokeExact(attributes);
                }
                for (MemorySegment h : new MemorySegment[] {out[0], out[1], err[0], err[1], in[0], in[1], thread, process, job}) {
                    close(h);
                }
            }
        } catch (ToolFailure e) {
            throw e;
        } catch (Throwable e) {
            throw new ToolFailure("Sandbox failure: " + e);
        }
    }

    private static void limit(Arena arena, MemorySegment job) throws Throwable {
        MemorySegment limits = arena.allocate(144);
        limits.set(JAVA_INT, 16, LIMIT_KILL_ON_JOB_CLOSE | LIMIT_ACTIVE_PROCESS | LIMIT_JOB_MEMORY | LIMIT_DIE_ON_UNHANDLED_EXCEPTION | LIMIT_PRIORITY_CLASS);
        limits.set(JAVA_INT, 40, MAX_PROCESSES);
        limits.set(JAVA_INT, 56, BELOW_NORMAL_PRIORITY_CLASS);
        limits.set(JAVA_LONG, 120, MAX_MEMORY);
        check((int) SET_JOB_INFO.invokeExact(job, JOB_EXTENDED_LIMIT, limits, 144), "SetInformationJobObject(limits)");
        MemorySegment ui = arena.allocate(4);
        ui.set(JAVA_INT, 0, UI_LIMIT_ALL);
        check((int) SET_JOB_INFO.invokeExact(job, JOB_UI_RESTRICTIONS, ui, 4), "SetInformationJobObject(ui)");
        MemorySegment cpu = arena.allocate(8);
        cpu.set(JAVA_INT, 0, CPU_RATE_ENABLE | CPU_RATE_HARD_CAP);
        cpu.set(JAVA_INT, 4, CPU_PERCENT * 100);
        int ignored = (int) SET_JOB_INFO.invokeExact(job, JOB_CPU_RATE, cpu, 8);
    }

    private static MemorySegment[] pipe(Arena arena, MemorySegment sa) throws Throwable {
        MemorySegment read = arena.allocate(ADDRESS);
        MemorySegment write = arena.allocate(ADDRESS);
        check((int) CREATE_PIPE.invokeExact(read, write, sa, 0), "CreatePipe");
        return new MemorySegment[] {read.get(ADDRESS, 0), write.get(ADDRESS, 0)};
    }

    private static void noInherit(MemorySegment handle) throws Throwable {
        check((int) SET_HANDLE_INFO.invokeExact(handle, HANDLE_FLAG_INHERIT, 0), "SetHandleInformation");
    }

    private static void terminate(MemorySegment process) {
        try {
            int ignored = (int) TERMINATE_PROCESS.invokeExact(process, 1);
        } catch (Throwable ignored) {
        }
    }

    private static void close(MemorySegment handle) {
        if (handle == null || handle.address() == 0) {
            return;
        }
        try {
            int ignored = (int) CLOSE.invokeExact(handle);
        } catch (Throwable ignored) {
        }
    }

    private static void check(int ok, String what) throws ToolFailure {
        if (ok == 0) {
            throw new ToolFailure("Sandbox setup failed at " + what);
        }
    }

    private static MemorySegment wide(Arena arena, String text) {
        return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
    }

    private static MemorySegment environment(Arena arena, Map<String, String> env) {
        StringBuilder block = new StringBuilder();
        env.entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> block.append(e.getKey()).append('=').append(e.getValue()).append('\0'));
        block.append('\0');
        return wide(arena, block.toString());
    }

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    static Map<String, String> baseEnvironment(Path workspace, List<Path> extraPath) throws ToolFailure {
        String windows = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path sandbox = workspace.resolve(".sandbox");
        Path home = sandbox.resolve("home");
        Path temp = sandbox.resolve("tmp");
        try {
            Files.createDirectories(home);
            Files.createDirectories(temp);
        } catch (IOException e) {
            throw new ToolFailure("Unable to prepare the sandbox folders: " + e.getMessage());
        }
        List<String> path = new ArrayList<>(List.of(windows + "\\System32", windows, windows + "\\System32\\Wbem",
                windows + "\\System32\\WindowsPowerShell\\v1.0"));
        extraPath.forEach(p -> path.add(p.toString()));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SystemRoot", windows);
        env.put("windir", windows);
        env.put("ComSpec", windows + "\\System32\\cmd.exe");
        env.put("PATH", String.join(";", path));
        env.put("PATHEXT", ".COM;.EXE;.BAT;.CMD;.VBS;.JS;.PS1;.PY");
        env.put("TEMP", temp.toString());
        env.put("TMP", temp.toString());
        env.put("USERPROFILE", home.toString());
        env.put("HOME", home.toString());
        env.put("APPDATA", home.resolve("AppData").resolve("Roaming").toString());
        env.put("LOCALAPPDATA", home.resolve("AppData").resolve("Local").toString());
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("PYTHONNOUSERSITE", "1");
        env.put("NUMBER_OF_PROCESSORS", String.valueOf(Runtime.getRuntime().availableProcessors()));
        env.put("PROCESSOR_ARCHITECTURE", System.getenv().getOrDefault("PROCESSOR_ARCHITECTURE", "AMD64"));
        env.put("SANDBOX", "1");
        return env;
    }

    private static final class Capture implements Runnable {
        private final MemorySegment handle;
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private volatile boolean truncated;

        Capture(MemorySegment handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(8192);
                MemorySegment read = arena.allocate(JAVA_INT);
                while (true) {
                    int ok = (int) READ_FILE.invokeExact(handle, buffer, 8192, read, MemorySegment.NULL);
                    int n = read.get(JAVA_INT, 0);
                    if (ok == 0 || n <= 0) {
                        return;
                    }
                    if (data.size() < MAX_CAPTURE) {
                        data.write(buffer.asSlice(0, Math.min(n, MAX_CAPTURE - data.size())).toArray(ValueLayout.JAVA_BYTE), 0,
                                Math.min(n, MAX_CAPTURE - data.size()));
                    }
                    if (data.size() >= MAX_CAPTURE) {
                        truncated = true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        String text() {
            return decode(data.toByteArray());
        }
    }
}
