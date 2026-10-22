package dafeiyu.plugin.api;

import com.google.gson.JsonArray;
import dafeiyu.agent.tool.ToolFailure;

import java.util.Map;

public interface QqPort {

    String send(String session, String text, String image, String quote, String label) throws ToolFailure;

    Map<String, String> groupNames();

    JsonArray members(String groupId) throws ToolFailure;

    boolean connected();
}
