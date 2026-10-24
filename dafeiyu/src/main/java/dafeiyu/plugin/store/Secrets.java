package dafeiyu.plugin.store;

import dafeiyu.sandbox.Platform;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class Secrets {

    public static final String AES = "aes:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile Path keyFile = Path.of("data", "secret.key");
    private static byte[] key;

    private Secrets() {}

    public static void init(Path dataDir) {
        keyFile = dataDir.resolve("secret.key");
    }

    public static boolean sealed(String value) {
        return value != null && (value.startsWith(Dpapi.PREFIX) || value.startsWith(AES));
    }

    public static String encrypt(String plain) {
        return Platform.windows() ? Dpapi.encrypt(plain) : seal(plain);
    }

    static String seal(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = Arrays.copyOf(iv, iv.length + sealed.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return AES + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed: " + e.getMessage());
        }
    }

    public static String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (stored.startsWith(Dpapi.PREFIX)) {
            if (!Platform.windows()) {
                throw new IllegalStateException("this secret was sealed with Windows DPAPI");
            }
            return Dpapi.decrypt(stored);
        }
        return stored.startsWith(AES) ? open(stored) : stored;
    }

    static String open(String stored) {
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(AES.length()));
            if (data.length < 28) {
                throw new IllegalStateException("broken secret");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, data, 0, 12));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("decryption failed: " + e.getMessage());
        }
    }

    private static synchronized byte[] key() {
        if (key != null) {
            return key;
        }
        Path file = keyFile;
        try {
            if (Files.isRegularFile(file)) {
                byte[] stored = Files.readAllBytes(file);
                if (stored.length == 32) {
                    key = stored;
                    return key;
                }
                throw new IllegalStateException("secret key file is damaged: " + file);
            }
            byte[] fresh = new byte[32];
            RANDOM.nextBytes(fresh);
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path temp;
            try {
                temp = Files.createTempFile(dir, "secret", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                temp = Files.createTempFile(dir, "secret", ".tmp");
            }
            try {
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    channel.write(ByteBuffer.wrap(fresh));
                    channel.force(true);
                }
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            key = fresh;
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("cannot use the secret key file " + file + ": " + e.getMessage());
        }
    }
}
