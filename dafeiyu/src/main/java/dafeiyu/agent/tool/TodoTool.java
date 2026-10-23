package dafeiyu.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class TodoTool extends TextTool {

    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed", "cancelled");
    private static final int MAX_ITEMS = 50;

    private static final JsonObject PARAMETERS = Args.schema("""
            {"type": "object", "properties": {
              "todos": {"type": "array", "description": "The updated todo list", "items": {"type": "object", "properties": {
                "content": {"type": "string", "description": "Brief description of the task"},
                "status": {"type": "string", "enum": ["pending", "in_progress", "completed", "cancelled"],
                           "description": "Current status of the task: pending, in_progress, completed, cancelled"}
              }, "required": ["content", "status"]}}
            }, "required": ["todos"]}""");

    @Override
    public String name() {
        return "todowrite";
    }

    @Override
    public String description(ToolContext context) {
        return """
                Create and update a todo list for the current task. The user sees it as your working plan.

                Use it proactively when a task has 3 or more distinct steps, or when the user gives several tasks or asks for a todo list. \
                Skip it for a single simple task or a purely conversational request.

                - Send the full list on every call; it replaces the previous one.
                - Mark items `in_progress` when you start them and `completed` as soon as they're actually done.
                - Only call it when the list changes.""";
    }

    @Override
    public JsonObject parameters() {
        return PARAMETERS;
    }

    @Override
    protected String text(Args args, ToolContext context) throws ToolFailure {
        JsonElement raw = args.get("todos");
        if (raw == null || !raw.isJsonArray()) {
            throw new ToolFailure("Missing required parameter: todos (an array of {content, status})");
        }
        JsonArray items = raw.getAsJsonArray();
        if (items.size() > MAX_ITEMS) {
            throw new ToolFailure("Too many todos: keep the list under " + MAX_ITEMS + " items");
        }
        List<String> lines = new ArrayList<>();
        int done = 0;
        List<String> active = new ArrayList<>();
        for (JsonElement item : items) {
            if (!item.isJsonObject()) {
                throw new ToolFailure("Each todo must be an object with content and status");
            }
            Args todo = new Args(item.getAsJsonObject());
            String content = todo.string("content").strip();
            String status = todo.string("status").strip();
            if (!STATUSES.contains(status)) {
                throw new ToolFailure("Invalid status '" + status + "': use pending, in_progress, completed or cancelled");
            }
            if (status.equals("completed")) {
                done++;
            }
            if (status.equals("in_progress")) {
                active.add("\"" + content + "\"");
            }
            lines.add(switch (status) {
                case "completed" -> "[x] ";
                case "in_progress" -> "[>] ";
                case "cancelled" -> "[-] ";
                default -> "[ ] ";
            } + content);
        }
        if (lines.isEmpty()) {
            return "Todos cleared";
        }
        return "Todos updated: " + done + "/" + lines.size() + " completed" + (active.isEmpty() ? "" : ". In progress: " + String.join(", ", active))
                + "\n" + String.join("\n", lines);
    }
}
