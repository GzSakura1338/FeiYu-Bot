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
            // maxOutput 不能给 0：推理模型会把预算全花在 thinking 上、正文返回空串，
            // 于是下面的 contains 判 false，vision 被永久缓存成 false，之后所有图都被剥离。
            // 给足预算 + 空回复视为"探测没成"而不是"不支持"（抛出去让上层不缓存）。
            String answer = protocol.stream(profile, ChatRequest.of(List.of(question), null, 1024), StreamListener.NONE)
                    .content().toLowerCase();
            if (answer.isBlank()) {
                throw new AgentException(Kind.LOCAL,
                        "vision probe got an empty reply (reasoning model may have spent the whole budget on thinking)");
            }
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
