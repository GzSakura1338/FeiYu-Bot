package dafeiyu.agent;

import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.tool.ToolContext;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.agent.tool.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ResearchTools implements ToolSet {

    private static final Set<String> WEB = Set.of("websearch", "webfetch");
    private static final Set<String> WEB_AND_FILES = Set.of("websearch", "webfetch", "read", "list", "glob", "grep");
    private static final int MAX_STEPS = 60;

    private final ChatAgent agent;
    private final Workspace workspace;
    private final Set<String> names;
    private final boolean vision;

    ResearchTools(ChatAgent agent) {
        this.agent = agent;
        this.workspace = agent.workspaces().workspace();
        this.names = workspace == null ? WEB : WEB_AND_FILES;
        this.vision = agent.vision();
    }

    @Override
    public List<ToolSpec> specs() {
        List<ToolSpec> all = new ArrayList<>(Toolbox.specs(names, false, false));
        if (vision) {
            all.add(ImageViewer.SPEC);
        }
        return all;
    }

    @Override
    public Toolbox.Outcome run(ToolCall call) {
        if (vision && call.name().equals(ImageViewer.NAME)) {
            return ImageViewer.run(agent, call.arguments(), workspace);
        }
        return Toolbox.run(call, names, new ToolContext(workspace, null, false));
    }

    @Override
    public int maxSteps() {
        return MAX_STEPS;
    }
}
