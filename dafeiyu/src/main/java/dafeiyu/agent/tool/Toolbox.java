package dafeiyu.agent.tool;

import com.google.gson.JsonObject;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.ToolCall;
import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.util.Jsons;

import java.util.List;
import java.util.Set;

public final class Toolbox {

    public record Outcome(String output, boolean error, List<ImagePart> images) {

        public Outcome {
            images = images == null ? List.of() : List.copyOf(images);
        }

        public Outcome(String output, boolean error) {
            this(output, error, List.of());
        }
    }

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
        return specs(enabled, false, false);
    }

    public static List<ToolSpec> specs(Set<String> enabled, boolean network, boolean direct) {
        return ALL.stream()
                .filter(t -> enabled.contains(t.name()))
                .map(t -> new ToolSpec(t.name(), t.description(network, direct), t.parameters().deepCopy()))
                .toList();
    }

    public static String sandboxTest(Workspace workspace, boolean network, boolean direct) throws ToolFailure {
        if (workspace == null) {
            throw new ToolFailure("还没有设置工作目录");
        }
        return ShellTool.selfTest(workspace, network, direct);
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
