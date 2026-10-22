package dafeiyu.plugin.store;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

public final class Dpapi {

    public static final String PREFIX = "dpapi:";
    private static final int UI_FORBIDDEN = 0x1;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup CRYPT32 = SymbolLookup.libraryLookup("crypt32", Arena.global());
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final MethodHandle PROTECT = LINKER.downcallHandle(CRYPT32.find("CryptProtectData").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle UNPROTECT = LINKER.downcallHandle(CRYPT32.find("CryptUnprotectData").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle LOCAL_FREE = LINKER.downcallHandle(KERNEL32.find("LocalFree").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    private Dpapi() {}

    public static String encrypt(String plain) {
        return PREFIX + Base64.getEncoder().encodeToString(call(PROTECT, plain.getBytes(StandardCharsets.UTF_8)));
    }

    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        return new String(call(UNPROTECT, Base64.getDecoder().decode(stored.substring(PREFIX.length()))), StandardCharsets.UTF_8);
    }

    private static byte[] call(MethodHandle fn, byte[] input) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(1, input.length));
            MemorySegment.copy(input, 0, data, ValueLayout.JAVA_BYTE, 0, input.length);
            MemorySegment in = arena.allocate(16);
            in.set(JAVA_INT, 0, input.length);
            in.set(ADDRESS, 8, data);
            MemorySegment out = arena.allocate(16);
            int ok = (int) fn.invokeExact(in, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, UI_FORBIDDEN, out);
            if (ok == 0) {
                throw new IllegalStateException("DPAPI call failed");
            }
            int size = out.get(JAVA_INT, 0);
            MemorySegment result = out.get(ADDRESS, 8).reinterpret(size);
            byte[] bytes = result.toArray(ValueLayout.JAVA_BYTE);
            MemorySegment ignored = (MemorySegment) LOCAL_FREE.invokeExact(out.get(ADDRESS, 8));
            return bytes;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("DPAPI call failed: " + e);
        }
    }
}
