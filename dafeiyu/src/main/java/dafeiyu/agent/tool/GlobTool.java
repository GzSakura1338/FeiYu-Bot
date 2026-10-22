package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class GlobTool implements Tool {

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "pattern": {"type": "string", "description": "Glob pattern to match files against"},
              "path": {"type": "string", "description": "Directory to search. Defaults to the current working directory."},
              "hidden": {"type": "boolean", "description": "Include hidden files and directories (default: false)."},
              "limit": {"type": "integer", "description": "Maximum number of matching files to return (default: 100)"}
            }, "required": ["pattern"]}""");

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Search file paths using a glob pattern (examples: \"**/*.ts\", \"src/**/*.tsx\").";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    public String run(JsonObject args, ToolContext context) throws ToolFailure {
        Workspace ws = context.workspace();
        String pattern = Args.string(args, "pattern");
        if (pattern.isBlank()) {
            throw new ToolFailure("Pattern must not be empty");
        }
        String dirArg = Args.optString(args, "path");
        if ("undefined".equals(dirArg) || "null".equals(dirArg)) {
            dirArg = null;
        }
        Path root = ws.existingDirectory(dirArg);
        Integer limitArg = Args.optInt(args, "limit");
        int limit = limitArg == null || limitArg < 1 ? FileWalker.DEFAULT_LIMIT : limitArg;
        Pattern matcher = FileWalker.glob(pattern.strip());
        List<String> found = new ArrayList<>();
        FileWalker.walk(ws, root, Args.optBool(args, "hidden", false), file -> {
            if (matcher.matcher(FileWalker.relative(root, file)).matches()) {
                found.add(ws.relative(file));
            }
            return found.size() <= limit;
        });
        boolean truncated = found.size() > limit;
        List<String> shown = found.subList(0, Math.min(limit, found.size())).stream().sorted().toList();
        List<String> lines = new ArrayList<>(shown.isEmpty() ? List.of("No files found") : shown);
        if (truncated) {
            lines.add("");
            lines.add("(Results are truncated: showing first " + shown.size() + " results. Consider using a more specific path or pattern.)");
        }
        return String.join("\n", lines);
    }
}
