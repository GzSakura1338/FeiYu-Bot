package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

final class DeleteTool extends TextTool {

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "path": {"type": "string", "description": "The file or folder to delete"}
            }, "required": ["path"]}""");

    @Override
    public String name() {
        return "delete";
    }

    @Override
    public String description(ToolContext context) {
        return "Delete a file or folder by moving it into the .trash folder of the working directory, where the owner can restore it. "
                + "Only delete files you created or that the user asked you to remove.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    protected String text(Args args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        Path target = ws.resolve(args.string("path"));
        if (!Files.exists(target)) {
            throw new ToolFailure("File not found: " + args.string("path"));
        }
        ReentrantLock lock = Workspace.lock(target);
        lock.lock();
        try {
            return "Moved " + ws.relative(target) + " to " + ws.trash(target) + " (it can be restored from there)";
        } finally {
            lock.unlock();
        }
    }
}
