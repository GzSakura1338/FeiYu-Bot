package dafeiyu.agent;

public record ToolCall(String id, String name, String arguments) {

    public ToolCall {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
    }
}
