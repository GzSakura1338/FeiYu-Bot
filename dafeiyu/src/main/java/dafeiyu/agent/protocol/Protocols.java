package dafeiyu.agent.protocol;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.model.ModelProfile;

import java.util.List;
import java.util.Map;

public final class Protocols {

    private static final Map<String, ModelProtocol> ALL = Map.of(
            "openai-chat", new OpenAiChatProtocol(),
            "openai-responses", new OpenAiResponsesProtocol(),
            "anthropic", new AnthropicProtocol());

    private Protocols() {}

    public static boolean exists(String id) {
        return id != null && ALL.containsKey(id);
    }

    public interface Meter {
        void record(ModelProfile profile, ChatRequest request, ChatResult result);
    }

    private static volatile Meter meter = (profile, request, result) -> {};

    public static void setMeter(Meter next) {
        meter = next;
    }

    public static ModelProtocol get(String id) throws AgentException {
        ModelProtocol protocol = id == null ? null : ALL.get(id);
        if (protocol == null) {
            throw new AgentException("unknown protocol: " + id);
        }
        return new ModelProtocol() {
            @Override
            public ChatResult stream(ModelProfile profile, ChatRequest request, StreamListener listener) throws AgentException {
                ChatResult result = protocol.stream(profile, request, listener);
                try {
                    meter.record(profile, request, result);
                } catch (RuntimeException ignored) {
                }
                return result;
            }

            @Override
            public List<String> listModels(String baseUrl, String apiKey) throws AgentException {
                return protocol.listModels(baseUrl, apiKey);
            }
        };
    }
}
