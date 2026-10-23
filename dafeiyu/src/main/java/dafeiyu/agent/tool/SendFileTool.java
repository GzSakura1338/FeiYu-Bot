package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SendFileTool extends TextTool {

    static final long MAX_BYTES = 100L * 1024 * 1024;

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "path": {"type": "string", "description": "File in the working directory to send"}
            }, "required": ["path"]}""");

    @Override
    public String name() {
        return "send_file";
    }

    @Override
    public String description(ToolContext context) {
        return "Send a file from the working directory to the user in the current chat as an attachment. Images are shown inline. "
                + "Use it when the user asks you to send, share, or deliver a file. The file must already exist; create it with write first if needed.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    protected String text(Args args, ToolContext context) throws ToolFailure {
        if (context.files() == null) {
            throw new ToolFailure("Sending files is not available in this conversation.");
        }
        Workspace ws = context.workspace();
        Path file = ws.existingFile(args.string("path"));
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new ToolFailure("Unable to read " + ws.relative(file) + ": " + e.getMessage());
        }
        if (size == 0) {
            throw new ToolFailure("File is empty: " + ws.relative(file));
        }
        if (size > MAX_BYTES) {
            throw new ToolFailure("File is too large to send (" + size / 1024 / 1024 + " MB, limit " + MAX_BYTES / 1024 / 1024 + " MB)");
        }
        return context.files().send(file, file.getFileName().toString());
    }
}
