package dafeiyu.agent.model;

import dafeiyu.agent.protocol.Protocols;

public record ModelProfile(
        String id,
        String name,
        String protocol,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Integer maxTokens,
        Integer contextWindow) {

    public ModelProfile {
        name = name == null ? "" : name.trim();
        protocol = protocol == null ? "" : protocol.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
        maxTokens = maxTokens == null ? 0 : maxTokens;
        contextWindow = contextWindow == null || contextWindow <= 0 ? null : contextWindow;
    }

    public ModelProfile withId(String newId) {
        return new ModelProfile(newId, name, protocol, baseUrl, apiKey, model, temperature, maxTokens, contextWindow);
    }

    public ModelProfile withApiKey(String key) {
        return new ModelProfile(id, name, protocol, baseUrl, key, model, temperature, maxTokens, contextWindow);
    }

    public String validate() {
        if (name.isEmpty()) return "名称不能为空";
        if (name.length() > 30) return "名称不能超过 30 个字";
        if (!Protocols.exists(protocol)) return "请选择协议";
        if (!baseUrl.matches("https?://\\S+")) return "Base URL 要以 http:// 或 https:// 开头";
        if (model.isEmpty()) return "模型不能为空";
        if (temperature != null && (temperature.isNaN() || temperature < 0 || temperature > 2)) return "温度必须在 0～2 之间";
        if (maxTokens < 0 || maxTokens > 1_000_000) return "最大输出必须在 0～1000000 之间";
        if (contextWindow != null && (contextWindow < 4_096 || contextWindow > 10_000_000)) return "上下文窗口必须在 4096～10000000 之间";
        return null;
    }
}
