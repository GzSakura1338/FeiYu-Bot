package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

final class MoveTool implements Tool {

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "from": {"type": "string", "description": "The file or folder to move or rename"},
              "to": {"type": "string", "description": "The new path. Missing parent folders are created."},
              "overwrite": {"type": "boolean", "description": "Replace an existing file at the destination (default: false)"}
            }, "required": ["from", "to"]}""");

    @Override
    public String name() {
        return "move";
    }

    @Override
    public String description() {
        return "Move or rename a file or folder inside the working directory. Use it to organize files into folders.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public String run(JsonObject args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        Path from = ws.resolve(Args.string(args, "from"));
        if (!Files.exists(from)) {
            throw new ToolFailure("File not found: " + Args.string(args, "from"));
        }
        Path to = ws.resolve(Args.string(args, "to"));
        ReentrantLock lock = Workspace.lock(from);
        lock.lock();
        try {
            ws.move(from, to, Args.optBool(args, "overwrite", false));
        } finally {
            lock.unlock();
        }
        return "Moved " + ws.relative(from) + " to " + ws.relative(to);
    }
}
