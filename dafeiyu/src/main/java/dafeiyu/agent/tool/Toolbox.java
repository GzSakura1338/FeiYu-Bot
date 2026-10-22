package dafeiyu.agent.tool;

import com.google.gson.JsonObject;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.util.Jsons;

import java.util.List;
import java.util.Set;

public final class Toolbox {

    public record Outcome(String output, boolean error) {}

    private static final int MAX_OUTPUT = 50_000;
    private static final List<Tool> ALL = List.of(
            new ReadTool(), new WriteTool(), new EditTool(), new PatchTool(), new ListTool(), new GlobTool(), new GrepTool(), new MoveTool(),
            new DeleteTool(), new TodoTool(), new WebSearchTool(), new WebFetchTool(), new ShellTool(), new SendFileTool());

    private Toolbox() {}

    public static List<String> names() {
        return ALL.stream().map(Tool::name).toList();
    }

    public static boolean exists(String name) {
        return ALL.stream().anyMatch(t -> t.name().equals(name));
    }

    public static List<ToolSpec> specs(Set<String> enabled) {
        return ALL.stream()
                .filter(t -> enabled.contains(t.name()))
                .map(t -> new ToolSpec(t.name(), t.description(), t.parameters().deepCopy()))
                .toList();
    }

    public static Outcome run(ToolCall call, Set<String> enabled, ToolContext context) {
        Tool tool = ALL.stream().filter(t -> t.name().equals(call.name()) && enabled.contains(t.name())).findFirst().orElse(null);
        if (tool == null) {
            return new Outcome("Unknown tool: " + call.name() + ". Available tools: " + String.join(", ", enabled), true);
        }
        JsonObject args = Jsons.parseObject(call.arguments());
        if (args == null) {
            return new Outcome("Invalid arguments for " + call.name() + ": expected a JSON object, got " + abbreviate(call.arguments(), 200), true);
        }
        try {
            return new Outcome(abbreviate(tool.run(args, context), MAX_OUTPUT), false);
        } catch (ToolFailure e) {
            return new Outcome(e.getMessage(), true);
        } catch (RuntimeException e) {
            return new Outcome("Unexpected error in " + call.name() + ": " + e, true);
        }
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n[Output truncated]";
    }
}
