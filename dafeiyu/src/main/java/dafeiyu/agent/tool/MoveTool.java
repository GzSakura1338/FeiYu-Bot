package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

final class MoveTool extends TextTool {

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
    public String description(ToolContext context) {
        return "Move or rename a file or folder inside the working directory. Use it to organize files into folders.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    protected String text(Args args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        Path from = ws.resolve(args.string("from"));
        if (!Files.exists(from)) {
            throw new ToolFailure("File not found: " + args.string("from"));
        }
        Path to = ws.resolve(args.string("to"));
        ReentrantLock lock = Workspace.lock(from);
        lock.lock();
        try {
            ws.move(from, to, args.bool("overwrite", false));
        } finally {
            lock.unlock();
        }
        return "Moved " + ws.relative(from) + " to " + ws.relative(to);
    }
}
