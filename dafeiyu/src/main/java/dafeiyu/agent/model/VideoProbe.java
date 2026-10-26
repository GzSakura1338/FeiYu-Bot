package dafeiyu.agent.model;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ImagePart;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.image.VideoFrames;
import dafeiyu.agent.protocol.ChatRequest;
import dafeiyu.agent.protocol.ModelProtocol;
import dafeiyu.agent.tool.ToolFailure;

import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 探测模型能不能**直接看视频**（不是抽帧，是原生视频输入）。
 *
 * <p>2026-10-27 实测结论：中转站能接受 {@code video_url} 这个 content 类型，
 * 但当前模型（deepseek-v4.1-flash）会回「当前模型不支持视频」。
 * 所以这里既要有探测，也要有明确的降级路径 —— 探测失败就老老实实抽帧。
 *
 * <p>测试视频用 ffmpeg 现造（1 秒纯红），不往仓库塞二进制资源。
 */
public final class VideoProbe {

    /** 模型明确表示"看不了视频"时的措辞（中英文都算）。 */
    private static final Pattern REFUSAL = Pattern.compile(
            "不支持视频|无法查看(?:这段)?视频|不能查看(?:这段)?视频|看不到视频|视频.*不支持"
                    + "|unsupported content|content type|not support(?:ed)? video|cannot view video|unable to view",
            Pattern.CASE_INSENSITIVE);

    private VideoProbe() {}

    /**
     * @return true = 模型能直接看视频；false = 只能抽帧
     * @throws AgentException 探测本身没做成（网络/额度等），调用方**不要**缓存结论
     */
    static boolean probe(ModelProtocol protocol, ModelProfile profile, VideoFrames videos) throws AgentException {
        byte[] sample;
        try {
            sample = videos.sampleVideo();
        } catch (ToolFailure e) {
            throw new AgentException(Kind.LOCAL, "视频能力探测没做成：" + e.getMessage());
        }
        ImagePart clip = new ImagePart("video/mp4", Base64.getEncoder().encodeToString(sample));
        ChatMessage question = ChatMessage.user("What is the main color of this video? Reply with one word.",
                List.of(clip));
        try {
            String raw = protocol.stream(profile, ChatRequest.of(List.of(question), null, 1024), StreamListener.NONE)
                    .content();
            String answer = raw == null ? "" : raw.toLowerCase();
            if (answer.isBlank()) {
                throw new AgentException(Kind.LOCAL,
                        "video probe got an empty reply (reasoning model may have spent the whole budget on thinking)");
            }
            if (REFUSAL.matcher(answer).find()) {
                return false;
            }
            return answer.contains("red") || answer.contains("红");
        } catch (AgentException e) {
            if (e.kind() == Kind.INVALID_REQUEST || e.kind() == Kind.PAYLOAD_TOO_LARGE) {
                return false;   // 拒绝这个 content 类型 ⇒ 不支持
            }
            throw e;
        }
    }
}
