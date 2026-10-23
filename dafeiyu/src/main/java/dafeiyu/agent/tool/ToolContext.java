package dafeiyu.agent.tool;

public record ToolContext(Workspace workspace, FileSink files, boolean network, boolean direct) {

    public ToolContext(Workspace workspace, FileSink files, boolean network) {
        this(workspace, files, network, false);
    }
}
