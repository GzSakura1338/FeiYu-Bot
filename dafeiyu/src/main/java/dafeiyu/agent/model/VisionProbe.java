package dafeiyu.agent.model;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.protocol.ChatRequest;
import dafeiyu.agent.protocol.ModelProtocol;

import java.awt.Color;
import java.util.List;
import java.util.regex.Pattern;

public final class VisionProbe {

    public static final String UNSUPPORTED = "ERROR: Cannot read image (this model does not support image input). Inform the user.";

    private static final ImagePart SAMPLE = ImageLoader.solid(new Color(220, 20, 20), 64);
    private static final Pattern IMAGE_ERROR = Pattern.compile(
            "image|vision|multimodal|multi-modal|多模态|图片|图像|image_url|input_image|unsupported content|content type",
            Pattern.CASE_INSENSITIVE);

    private VisionProbe() {}

    static boolean probe(ModelProtocol protocol, ModelProfile profile) throws AgentException {
        ChatMessage question = ChatMessage.user("What is the main color of this image? Reply with one word.", List.of(SAMPLE));
        try {
            String answer = protocol.stream(profile, ChatRequest.of(List.of(question), null, 0), StreamListener.NONE)
                    .content().toLowerCase();
            return answer.contains("red") || answer.contains("红");
        } catch (AgentException e) {
            if (isImageError(e)) {
                return false;
            }
            throw e;
        }
    }

    static boolean isImageError(AgentException e) {
        return (e.kind() == Kind.INVALID_REQUEST || e.kind() == Kind.PAYLOAD_TOO_LARGE)
                && e.getMessage() != null && IMAGE_ERROR.matcher(e.getMessage()).find();
    }
}
