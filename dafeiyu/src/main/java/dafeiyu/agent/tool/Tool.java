package dafeiyu.agent.tool;

import com.google.gson.JsonObject;

interface Tool {

    String name();

    String description();

    JsonObject parameters();

    String run(JsonObject args, ToolContext context) throws ToolFailure;
}
