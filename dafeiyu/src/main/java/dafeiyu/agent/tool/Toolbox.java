package dafeiyu.agent.tool;

import java.util.Collection;
import java.util.List;

public final class Toolbox {

    private static final List<Tool> ALL = List.of(
            new ReadTool(), new WriteTool(), new EditTool(), new PatchTool(), new ListTool(), new GlobTool(), new GrepTool(), new MoveTool(),
            new DeleteTool(), new TodoTool(), new WebSearchTool(), new WebFetchTool(), new ShellTool(), new SendFileTool());

    private Toolbox() {}

    public static List<String> names() {
        return ALL.stream().map(Tool::name).toList();
    }

    public static boolean exists(String name) {
        return get(name) != null;
    }

    public static Tool get(String name) {
        return ALL.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    public static List<Tool> select(Collection<String> names) {
        return ALL.stream().filter(t -> names.contains(t.name())).toList();
    }

    public static String sandboxTest(Workspace workspace, boolean network, boolean direct) throws ToolFailure {
        if (workspace == null) {
            throw new ToolFailure("还没有设置工作目录");
        }
        return ShellTool.selfTest(workspace, network, direct);
    }
}
