package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ListTool implements Tool {

    private static final int LIMIT = 200;
    private static final int MAX_DEPTH = 3;
    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "path": {"type": "string", "description": "Directory to list, relative to the working directory. Defaults to the working directory."},
              "hidden": {"type": "boolean", "description": "Include hidden entries such as .trash (default: false)."}
            }}""");

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String description() {
        return "List files and folders in a directory as a tree (up to 3 levels deep), with file sizes. Use it to see how the working directory is organized before reading, moving or tidying files.";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public String run(JsonObject args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        Path dir = ws.existingDirectory(Args.optString(args, "path"));
        boolean hidden = Args.optBool(args, "hidden", false);
        List<String> lines = new ArrayList<>();
        lines.add(ws.relative(dir) + "/");
        int[] count = {0};
        walk(ws, dir, hidden, "  ", 1, lines, count);
        if (count[0] == 0) {
            lines.add("  (empty)");
        }
        if (count[0] > LIMIT) {
            lines.add("");
            lines.add("(Listing truncated at " + LIMIT + " entries. List a subdirectory to see more.)");
        }
        return String.join("\n", lines);
    }

    private static void walk(Workspace ws, Path dir, boolean hidden, String indent, int depth, List<String> lines, int[] count) throws ToolFailure {
        for (Workspace.Entry entry : ws.entries(dir)) {
            if (!hidden && entry.name().startsWith(".")) {
                continue;
            }
            if (++count[0] > LIMIT) {
                return;
            }
            if (entry.directory()) {
                lines.add(indent + entry.name() + "/");
                if (depth < MAX_DEPTH) {
                    walk(ws, dir.resolve(entry.name()), hidden, indent + "  ", depth + 1, lines, count);
                }
            } else {
                lines.add(indent + entry.name() + "  (" + size(entry.size()) + ")");
            }
        }
    }

    private static String size(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return bytes >= 1024 ? Math.round(bytes / 1024.0) + " KB" : bytes + " B";
    }
}
