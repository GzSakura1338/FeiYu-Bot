package dafeiyu.agent.tool;

import com.google.gson.JsonObject;
import dafeiyu.agent.protocol.ToolSpec;

public interface Tool {

    String name();

    String description(ToolContext context);

    JsonObject parameters();

    ToolResult run(Args args, ToolContext context) throws ToolFailure;

    default ToolSpec spec(ToolContext context) {
        return new ToolSpec(name(), description(context), parameters().deepCopy());
    }

    @FunctionalInterface
    interface Handler {

        ToolResult run(Args args, ToolContext context) throws ToolFailure;
    }

    @FunctionalInterface
    interface TextHandler {

        String run(Args args, ToolContext context) throws ToolFailure;
    }

    static Tool of(ToolSpec spec, Handler handler) {
        return new Defined(spec, handler);
    }

    static Tool of(String name, String description, String schema, Handler handler) {
        return of(new ToolSpec(name, description, Args.schema(schema)), handler);
    }

    static Tool text(String name, String description, String schema, TextHandler handler) {
        return of(name, description, schema, (args, context) -> ToolResult.text(handler.run(args, context)));
    }

    record Defined(ToolSpec definition, Handler handler) implements Tool {

        @Override
        public String name() {
            return definition.name();
        }

        @Override
        public String description(ToolContext context) {
            return definition.description();
        }

        @Override
        public JsonObject parameters() {
            return definition.parameters();
        }

        @Override
        public ToolResult run(Args args, ToolContext context) throws ToolFailure {
            return handler.run(args, context);
        }
    }
}
