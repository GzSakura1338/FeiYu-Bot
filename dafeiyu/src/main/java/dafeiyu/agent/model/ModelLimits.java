package dafeiyu.agent.model;

import java.util.Comparator;
import java.util.List;

public final class ModelLimits {

    public record Limits(int context, int output) {}

    private record Entry(String prefix, int context, int output) {}

    private static final List<Entry> TABLE = List.of(
            new Entry("deepseek-v4", 1_000_000, 384_000),
            new Entry("deepseek-v3", 128_000, 32_000),
            new Entry("deepseek-chat", 128_000, 8_192),
            new Entry("deepseek-reasoner", 128_000, 64_000),
            new Entry("deepseek-r1", 128_000, 32_000),
            new Entry("gpt-5", 400_000, 128_000),
            new Entry("gpt-4.1", 1_047_576, 32_768),
            new Entry("gpt-4o", 128_000, 16_384),
            new Entry("o1", 200_000, 100_000),
            new Entry("o3", 200_000, 100_000),
            new Entry("o4-mini", 200_000, 100_000),
            new Entry("claude-fable-5", 1_000_000, 128_000),
            new Entry("claude-opus-5", 1_000_000, 128_000),
            new Entry("claude-sonnet-5", 1_000_000, 128_000),
            new Entry("claude-opus-4", 200_000, 32_000),
            new Entry("claude-sonnet-4", 200_000, 64_000),
            new Entry("claude-haiku-4", 200_000, 64_000),
            new Entry("claude-3", 200_000, 8_192),
            new Entry("gemini-3", 1_048_576, 65_536),
            new Entry("gemini-2.5", 1_048_576, 65_536),
            new Entry("qwen3", 262_144, 32_768),
            new Entry("qwen-max", 32_768, 8_192),
            new Entry("qwen-plus", 131_072, 32_768),
            new Entry("glm-5", 200_000, 128_000),
            new Entry("glm-4.6", 200_000, 128_000),
            new Entry("glm-4.5", 128_000, 96_000),
            new Entry("kimi-k2", 262_144, 65_536),
            new Entry("doubao", 256_000, 32_000),
            new Entry("minimax", 204_800, 131_072),
            new Entry("grok-4", 256_000, 30_000),
            new Entry("mistral-large", 131_072, 16_384),
            new Entry("llama-4", 128_000, 8_192));

    private ModelLimits() {}

    public static Limits lookup(String model) {
        String id = model == null ? "" : model.toLowerCase();
        String name = id.substring(id.lastIndexOf('/') + 1);
        return TABLE.stream()
                .filter(e -> name.startsWith(e.prefix()))
                .max(Comparator.comparingInt(e -> e.prefix().length()))
                .map(e -> new Limits(e.context(), e.output()))
                .orElse(new Limits(0, 0));
    }

    public static Limits resolve(ModelProfile profile) {
        Limits known = lookup(profile.model());
        int context = profile.contextWindow() != null && profile.contextWindow() > 0 ? profile.contextWindow() : known.context();
        int output = profile.maxTokens() > 0 ? profile.maxTokens() : known.output();
        return new Limits(context, output);
    }
}
