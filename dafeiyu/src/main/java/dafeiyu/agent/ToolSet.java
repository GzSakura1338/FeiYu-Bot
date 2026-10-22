package dafeiyu.agent;

import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.tool.Toolbox;

import java.util.List;

public interface ToolSet {

    List<ToolSpec> specs();

    Toolbox.Outcome run(ToolCall call);

    default int maxSteps() {
        return TurnRunner.MAX_STEPS;
    }
}
