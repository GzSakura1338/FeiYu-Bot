package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

final class WriteTool implements Tool {

    private static final int MAX_CHARS = 10 * 1024 * 1024;

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "path": {"type": "string", "description": "Path to the file to write to"},
              "content": {"type": "string", "description": "Content to write to the file"}
            }, "required": ["path", "content"]}""");

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "Writes a file to the local filesystem, overwriting if one exists.\n\n"
                + "Missing parent directories are created automatically.\n\n"
                + "Use this tool to create new files or overwrite existing files. For partial changes, use the edit tool instead.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public String run(JsonObject args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        Path target = ws.resolve(Args.string(args, "path"));
        String content = Args.string(args, "content");
        if (content.length() > MAX_CHARS) {
            throw new ToolFailure("Content is too large to write (over " + MAX_CHARS / 1024 / 1024 + " MB of text)");
        }
        String display = ws.relative(target);
        if (Files.isDirectory(target)) {
            throw new ToolFailure("Path is a directory, not a file: " + display);
        }
        ReentrantLock lock = Workspace.lock(target);
        lock.lock();
        try {
            boolean existed = Files.isRegularFile(target);
            Charset charset = StandardCharsets.UTF_8;
            boolean bom = false;
            if (existed) {
                byte[] current = TextFile.readBytes(target, display);
                if (!TextFile.isBinary(current)) {
                    TextFile.Decoded decoded = TextFile.decode(current);
                    charset = decoded.charset();
                    bom = decoded.bom();
                }
            }
            bom |= content.startsWith(TextFile.BOM);
            content = TextFile.stripBom(content);
            ws.write(target, TextFile.encode(content, charset, bom));
            return (existed ? "Wrote" : "Created") + " file successfully: " + display;
        } finally {
            lock.unlock();
        }
    }
}
