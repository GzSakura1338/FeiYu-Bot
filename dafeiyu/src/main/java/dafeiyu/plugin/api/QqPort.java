package dafeiyu.plugin.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dafeiyu.agent.tool.ToolFailure;

import java.util.Map;

public interface QqPort {

    String send(String session, String text, String image, String quote, String label) throws ToolFailure;

    Map<String, String> groupNames();

    JsonArray members(String groupId) throws ToolFailure;

    boolean connected();

    boolean writes(String action) throws ToolFailure;

    JsonObject raw(String action, JsonObject params) throws ToolFailure;
}
