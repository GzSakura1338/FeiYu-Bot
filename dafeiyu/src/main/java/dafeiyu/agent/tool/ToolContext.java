package dafeiyu.agent.tool;

public record ToolContext(String session, String userId, Workspace workspace, FileSink files, boolean network, boolean direct) {

    public ToolContext {
        session = session == null ? "" : session;
        userId = userId == null ? "" : userId;
    }

    public static ToolContext of(String session) {
        return new ToolContext(session, "", null, null, false, false);
    }

    public static ToolContext of(String session, Workspace workspace) {
        return new ToolContext(session, "", workspace, null, false, false);
    }
}
