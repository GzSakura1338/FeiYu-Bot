package dafeiyu.agent.protocol;

import com.google.gson.JsonObject;

public record ToolSpec(String name, String description, JsonObject parameters) {}
