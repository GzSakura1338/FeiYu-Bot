package dafeiyu.agent.tool;

import dafeiyu.agent.ToolCall;
import dafeiyu.agent.protocol.ToolSpec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class ToolSet {

    private static final int MAX_OUTPUT = 50_000;

    private final Map<String, Tool> tools;
    private final List<String> order;
    private final ToolContext context;
    private final int maxSteps;
    private final AtomicInteger calls;

    private ToolSet(Map<String, Tool> tools, ToolContext context, int maxSteps) {
        this(Map.copyOf(tools), List.copyOf(tools.keySet()), context, maxSteps, new AtomicInteger());
    }

    private ToolSet(Map<String, Tool> tools, List<String> order, ToolContext context, int maxSteps, AtomicInteger calls) {
        this.tools = tools;
        this.order = order;
        this.context = context;
        this.maxSteps = maxSteps;
        this.calls = calls;
    }

    public ToolSet askedBy(String userId) {
        return new ToolSet(tools, order, new ToolContext(context.session(), userId, context.workspace(), context.files(), context.network(), context.direct()),
                maxSteps, calls);
    }

    public static ToolSet none() {
        return builder(ToolContext.of("")).build();
    }

    public static ToolSet none(int maxSteps) {
        return builder(ToolContext.of("")).maxSteps(maxSteps).build();
    }

    public static Builder builder(ToolContext context) {
        return new Builder(context);
    }

    public ToolContext context() {
        return context;
    }

    public List<String> names() {
        return order;
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    public int maxSteps() {
        return maxSteps;
    }

    public boolean used() {
        return calls.get() > 0;
    }

    public List<ToolSpec> specs() {
        return order.stream().map(name -> tools.get(name).spec(context)).toList();
    }

    public ToolResult run(ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + call.name() + ". Available tools: " + String.join(", ", order));
        }
        calls.incrementAndGet();
        try {
            return tool.run(Args.parse(call.arguments()), context).truncated(MAX_OUTPUT);
        } catch (ToolFailure e) {
            return ToolResult.error(e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error("Unexpected error in " + call.name() + ": " + e);
        }
    }

    public static final class Builder {

        private final ToolContext context;
        private final Map<String, Tool> tools = new LinkedHashMap<>();
        private int maxSteps = Integer.MAX_VALUE;

        private Builder(ToolContext context) {
            this.context = context == null ? ToolContext.of("") : context;
        }

        public Builder add(Tool tool) {
            if (tool != null) {
                tools.putIfAbsent(tool.name(), tool);
            }
            return this;
        }

        public Builder addAll(Collection<? extends Tool> list) {
            if (list != null) {
                list.forEach(this::add);
            }
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = Math.max(1, maxSteps);
            return this;
        }

        public boolean has(String name) {
            return tools.containsKey(name);
        }

        public ToolSet build() {
            return new ToolSet(tools, context, maxSteps);
        }
    }
}
