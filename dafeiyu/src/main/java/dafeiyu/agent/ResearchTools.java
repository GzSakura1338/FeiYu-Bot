package dafeiyu.agent;

import dafeiyu.agent.tool.ToolContext;
import dafeiyu.agent.tool.ToolSet;
import dafeiyu.agent.tool.Toolbox;
import dafeiyu.agent.tool.Workspace;

import java.util.Set;

final class ResearchTools {

    private static final Set<String> WEB = Set.of("websearch", "webfetch");
    private static final Set<String> WEB_AND_FILES = Set.of("websearch", "webfetch", "read", "list", "glob", "grep");
    private static final int MAX_STEPS = 60;

    private ResearchTools() {}

    static ToolSet create(ChatAgent agent, String session) {
        Workspace workspace = agent.workspaces().workspace();
        ToolSet.Builder set = ToolSet.builder(ToolContext.of(session, workspace)).maxSteps(MAX_STEPS)
                .addAll(Toolbox.select(workspace == null ? WEB : WEB_AND_FILES));
        if (agent.vision()) {
            set.add(new ImageViewer(agent, true));
        }
        return set.build();
    }
}
