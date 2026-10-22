package dafeiyu.agent.model;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.protocol.ChatRequest;
import dafeiyu.agent.protocol.ModelProtocol;

import java.util.List;

final class SystemRoleProbe {

    private static final String MARKER = "PINEAPPLE";

    private SystemRoleProbe() {}

    static boolean probe(ModelProtocol protocol, ModelProfile profile) throws AgentException {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("You must end every reply with the exact word " + MARKER + "."),
                ChatMessage.user("Say hello in one short sentence."));
        String answer = protocol.stream(profile, ChatRequest.of(messages, null, 0), StreamListener.NONE).content();
        return answer.toUpperCase().contains(MARKER);
    }
}
