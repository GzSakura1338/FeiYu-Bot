package dafeiyu.agent;

import dafeiyu.agent.protocol.ToolSpec;
import dafeiyu.agent.tool.Toolbox;

import java.util.List;
import java.util.Set;

public interface Extensions {

    record ToolInfo(String name, String label, String description, String source, boolean qq) {}

    Extensions NONE = new Extensions() {
        @Override
        public List<ToolSpec> tools(boolean qq, boolean mind, Set<String> enabled) {
            return List.of();
        }

        @Override
        public boolean owns(String toolName) {
            return false;
        }

        @Override
        public Toolbox.Outcome run(ToolCall call, String session) {
            return new Toolbox.Outcome("Unknown tool: " + call.name(), true);
        }

        @Override
        public String contribute(String session, List<String> people) {
            return "";
        }

        @Override
        public List<ToolInfo> catalog() {
            return List.of();
        }

        @Override
        public void thought(String text) {
        }
    };

    List<ToolSpec> tools(boolean qq, boolean mind, Set<String> enabled);

    boolean owns(String toolName);

    Toolbox.Outcome run(ToolCall call, String session);

    String contribute(String session, List<String> people);

    List<ToolInfo> catalog();

    void thought(String text);
}
