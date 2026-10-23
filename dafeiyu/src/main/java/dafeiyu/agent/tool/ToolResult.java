package dafeiyu.agent.tool;

import dafeiyu.agent.ImagePart;

import java.util.List;

public record ToolResult(String output, boolean error, List<ImagePart> images) {

    public ToolResult {
        output = output == null ? "" : output;
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ToolResult(String output, boolean error) {
        this(output, error, List.of());
    }

    public static ToolResult text(String output) {
        return new ToolResult(output, false);
    }

    public static ToolResult error(String output) {
        return new ToolResult(output, true);
    }

    public static ToolResult withImages(String output, List<ImagePart> images) {
        return new ToolResult(output, false, images);
    }

    ToolResult truncated(int max) {
        return output.length() <= max ? this : new ToolResult(output.substring(0, max) + "\n[Output truncated]", error, images);
    }
}
