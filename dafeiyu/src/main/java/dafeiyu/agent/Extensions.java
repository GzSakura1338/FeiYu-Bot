package dafeiyu.agent;

import dafeiyu.agent.tool.Tool;

import java.util.List;
import java.util.Set;

public interface Extensions {

    record ToolInfo(String name, String label, String description, String source, boolean qq) {}

    Extensions NONE = new Extensions() {
        @Override
        public List<Tool> tools(boolean qq, boolean mind, Set<String> enabled) {
            return List.of();
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

    List<Tool> tools(boolean qq, boolean mind, Set<String> enabled);

    String contribute(String session, List<String> people);

    List<ToolInfo> catalog();

    void thought(String text);
}
