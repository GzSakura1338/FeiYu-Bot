package dafeiyu.agent;

import dafeiyu.agent.tool.FileSink;

import java.util.List;
import java.util.Map;

public record ChatTurn(String session, ChatMessage user, String qqUser, FileSink files, String source, List<String> people,
                       Map<String, String> roster, boolean heard, ToolSet tools, String attachments) {

    public ChatTurn {
        people = people == null ? List.of() : List.copyOf(people);
        roster = roster == null ? Map.of() : Map.copyOf(roster);
        attachments = attachments == null ? "" : attachments;
    }

    public ChatTurn(String session, ChatMessage user, String qqUser, FileSink files, String source, List<String> people,
                    Map<String, String> roster, boolean heard) {
        this(session, user, qqUser, files, source, people, roster, heard, null, "");
    }

    public ChatTurn(String session, ChatMessage user, String qqUser, FileSink files, String source, List<String> people,
                    Map<String, String> roster) {
        this(session, user, qqUser, files, source, people, roster, false);
    }

    public ChatTurn(String session, ChatMessage user, String qqUser, FileSink files, String source, List<String> people) {
        this(session, user, qqUser, files, source, people, Map.of());
    }

    public ChatTurn(String session, ChatMessage user, String qqUser, FileSink files, String source) {
        this(session, user, qqUser, files, source, qqUser == null || qqUser.isBlank() || qqUser.equals("0") ? List.of() : List.of(qqUser));
    }

    public ChatTurn withTools(ToolSet set) {
        return new ChatTurn(session, user, qqUser, files, source, people, roster, heard, set, attachments);
    }

    public ChatTurn withAttachments(String json) {
        return new ChatTurn(session, user, qqUser, files, source, people, roster, heard, tools, json);
    }

    public boolean mind() {
        return session.startsWith("mind:");
    }
}
