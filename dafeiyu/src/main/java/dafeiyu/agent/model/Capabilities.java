package dafeiyu.agent.model;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.protocol.ModelProtocol;

import java.util.regex.Pattern;

public final class Capabilities {

    private static final Pattern TOOL_ERROR = Pattern.compile("tool|function", Pattern.CASE_INSENSITIVE);

    private final AgentStore store;

    public Capabilities(AgentStore store) {
        this.store = store;
    }

    public boolean vision(ModelProfile profile, ModelProtocol protocol) {
        Boolean known = store.vision(profile.id());
        if (known != null && known) {
            return true;
        }
        try {
            boolean supported = VisionProbe.probe(protocol, profile);
            store.setVision(profile.id(), supported);
            return supported;
        } catch (AgentException e) {
            // 探测本身失败（429 限流 / 网络 / 超时）不等于「模型不支持看图」。
            // 以前这里直接 return false，导致视频抽帧被静默跳过（连日志都没有）。
            System.err.println("WARN vision probe failed (assume supported): " + e.getMessage());
            return true;
        }
    }

    public boolean nativeVideo(ModelProfile profile, ModelProtocol protocol,
                               dafeiyu.agent.image.VideoFrames videos) {
        Boolean known = store.video(profile.id());
        if (known != null) {
            return known;
        }
        try {
            boolean supported = VideoProbe.probe(protocol, profile, videos);
            store.setVideo(profile.id(), supported);
            return supported;
        } catch (AgentException e) {
            return false;
        }
    }

    public boolean systemRole(ModelProfile profile, ModelProtocol protocol) {
        Boolean known = store.systemRole(profile.id());
        if (known != null) {
            return known;
        }
        try {
            boolean works = SystemRoleProbe.probe(protocol, profile);
            store.setSystemRole(profile.id(), works);
            return works;
        } catch (AgentException e) {
            return true;
        }
    }

    public boolean toolCall(ModelProfile profile) {
        return !Boolean.FALSE.equals(store.toolCall(profile.id()));
    }

    public boolean rejectsImages(ModelProfile profile, AgentException e) {
        if (!VisionProbe.isImageError(e)) {
            return false;
        }
        store.setVision(profile.id(), false);
        return true;
    }

    public boolean rejectsTools(ModelProfile profile, AgentException e) {
        if (e.kind() != Kind.INVALID_REQUEST || e.getMessage() == null || !TOOL_ERROR.matcher(e.getMessage()).find()) {
            return false;
        }
        store.setToolCall(profile.id(), false);
        return true;
    }

    public boolean probeVision(ModelProfile profile, ModelProtocol protocol) throws AgentException {
        boolean supported = VisionProbe.probe(protocol, profile);
        store.setVision(profile.id(), supported);
        return supported;
    }
}
