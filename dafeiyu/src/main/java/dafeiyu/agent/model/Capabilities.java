package dafeiyu.agent.model;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.protocol.ModelProtocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class Capabilities {

    private static final Pattern TOOL_ERROR = Pattern.compile("tool|function", Pattern.CASE_INSENSITIVE);
    private static final long PROBE_RETRY_MS = 5 * 60_000L;

    private final AgentStore store;
    private final Map<String, Long> probeFailedAt = new ConcurrentHashMap<>();

    public Capabilities(AgentStore store) {
        this.store = store;
    }

    public boolean vision(ModelProfile profile, ModelProtocol protocol) {
        Boolean known = store.vision(profile.id());
        if (known != null) {
            return known;
        }
        Long failed = probeFailedAt.get(profile.id());
        if (failed != null && System.currentTimeMillis() - failed < PROBE_RETRY_MS) {
            return true;
        }
        try {
            boolean supported = VisionProbe.probe(protocol, profile);
            store.setVision(profile.id(), supported);
            probeFailedAt.remove(profile.id());
            return supported;
        } catch (AgentException e) {
            probeFailedAt.put(profile.id(), System.currentTimeMillis());
            System.err.println("WARN vision probe failed, assuming the model can see images and retrying the probe in "
                    + PROBE_RETRY_MS / 60_000 + " min: " + e.getMessage());
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
