package dafeiyu.agent.tool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class TextFile {

    record Decoded(String text, Charset charset, boolean bom) {}

    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final String BOM = String.valueOf((char) 0xFEFF);
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final int SNIFF = 8 * 1024;

    private TextFile() {}

    static byte[] readBytes(Path path, String display) throws ToolFailure {
        try {
            long size = Files.size(path);
            if (size > MAX_BYTES) {
                throw new ToolFailure("File is too large (" + size / 1024 / 1024 + " MB): " + display + ". Use grep to search it.");
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ToolFailure("Unable to read " + display + ": " + e.getMessage());
        }
    }

    static boolean isBinary(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xFE) || startsWith(bytes, 0xFE, 0xFF)) {
            return false;
        }
        for (int i = 0; i < Math.min(bytes.length, SNIFF); i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    static Decoded decode(byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8, true);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE, true);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE, true);
        }
        String utf8 = strict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return new Decoded(utf8, StandardCharsets.UTF_8, false);
        }
        String gb = strict(bytes, GB18030);
        if (gb != null) {
            return new Decoded(gb, GB18030, false);
        }
        return new Decoded(new String(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8, false);
    }

    static byte[] encode(String text, Charset charset, boolean bom) {
        byte[] body = text.getBytes(charset);
        if (!bom) {
            return body;
        }
        byte[] mark = charset.equals(StandardCharsets.UTF_16LE) ? new byte[] {(byte) 0xFF, (byte) 0xFE}
                : charset.equals(StandardCharsets.UTF_16BE) ? new byte[] {(byte) 0xFE, (byte) 0xFF}
                : new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[mark.length + body.length];
        System.arraycopy(mark, 0, out, 0, mark.length);
        System.arraycopy(body, 0, out, mark.length, body.length);
        return out;
    }

    static String stripBom(String text) {
        return text.startsWith(BOM) ? text.substring(1) : text;
    }

    private static String strict(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
