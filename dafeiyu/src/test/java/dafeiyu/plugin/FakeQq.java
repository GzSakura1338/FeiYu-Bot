package dafeiyu.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dafeiyu.agent.tool.ToolFailure;
import dafeiyu.plugin.api.QqPort;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FakeQq implements QqPort {

    public record Sent(String session, String text, String image, String quote, String label) {}

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    public volatile boolean connected = true;

    @Override
    public String send(String session, String text, String image, String quote, String label) throws ToolFailure {
        if (!connected) {
            throw new ToolFailure("QQ is not connected");
        }
        sent.add(new Sent(session, text, image, quote, label));
        return text;
    }

    @Override
    public Map<String, String> groupNames() {
        return Map.of("900001", "测试群", "900002", "另一个群");
    }

    @Override
    public JsonArray members(String groupId) {
        JsonArray out = new JsonArray();
        JsonObject m = new JsonObject();
        m.addProperty("userId", "10001");
        m.addProperty("nickname", "测试员");
        out.add(m);
        return out;
    }

    @Override
    public boolean connected() {
        return connected;
    }

    public long count(String textPart) {
        return sent.stream().filter(s -> s.text() != null && s.text().contains(textPart)).count();
    }

    public Sent last() {
        return sent.isEmpty() ? null : sent.getLast();
    }
}
