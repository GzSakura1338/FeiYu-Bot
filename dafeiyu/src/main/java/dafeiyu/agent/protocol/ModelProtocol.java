package dafeiyu.agent.protocol;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.model.ModelProfile;

import java.util.List;

public interface ModelProtocol {

    ChatResult stream(ModelProfile profile, ChatRequest request, StreamListener listener) throws AgentException;

    List<String> listModels(String baseUrl, String apiKey) throws AgentException;
}
