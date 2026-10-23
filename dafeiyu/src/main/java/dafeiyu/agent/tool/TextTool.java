package dafeiyu.agent.tool;

abstract class TextTool implements Tool {

    @Override
    public final ToolResult run(Args args, ToolContext context) throws ToolFailure {
        return ToolResult.text(text(args, context));
    }

    protected abstract String text(Args args, ToolContext context) throws ToolFailure;
}
