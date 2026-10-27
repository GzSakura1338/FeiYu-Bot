package dafeiyu.agent.safety;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.protocol.ChatRequest;
import dafeiyu.agent.protocol.ModelProtocol;
import dafeiyu.agent.protocol.Protocols;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

public final class ContentGuard {

    public enum Category {
        SEXUAL("色情低俗"), POLITICAL("政治敏感"), ILLEGAL("违法违规"), RISKY("敏感内容");

        final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Verdict(Category category, String term, boolean byModel) {}

    static final List<String> DEFLECTIONS = List.of("这个话题我不聊哦，换一个嘛", "这个不聊啦，说点别的吧", "唔…这个我不接，换个话题好不好");
    private static final Pattern NOISE = Pattern.compile("[\\p{Z}\\p{P}\\p{S}\\p{Cf}\\s]+");
    private static final long REVIEW_TIMEOUT_MS = 20_000;
    private static final int MIN_REVIEW_CHARS = 5;
    private static final String REVIEW_PROMPT = String.join("\n", List.of(
            "你是 QQ 聊天机器人的内容审核员。判断下面这条机器人准备发出的消息发出去是否违规：",
            "- sexual：色情、露骨的性暗示、低俗擦边、描写性行为或身体隐私部位",
            "- political：提到或影射国家领导人和政治人物（不论褒贬，含谐音、外号），政治事件、政权和政治制度、领土主权、民族宗教争议、群体性事件、翻墙",
            "- illegal：宣扬或教唆现实中的暴力恐怖，现实中的赌博、毒品、诈骗、买卖枪支，教唆自残自杀，能照着做的违法犯罪方法",
            "普通聊天、撒娇斗嘴、学习工作、一般的历史文化和科学常识，以及拒绝、岔开敏感话题的话，都不算违规。",
            "游戏、小说、影视和它们的玩法设定也不算违规：比如 CS2、和平精英这类射击游戏里的买枪、开枪、爆头、恐怖分子和反恐阵营、C4 炸弹，"
                    + "狼人杀里的杀人、毒药，写这类游戏的代码、讲怎么玩，都是正常内容。只有说的是现实中真去做的事才算。",
            "",
            "对方刚说的话（只作参考）：",
            "%s",
            "",
            "机器人准备发出的消息：",
            "%s",
            "",
            "只输出一个 JSON：{\"block\": true 或 false, \"category\": \"sexual\" 或 \"political\" 或 \"illegal\" 或 \"none\"}"));

    private record Term(String text, Category category, boolean soft) {}

    private final List<Term> terms;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ContentGuard(Path extra) {
        List<Term> loaded = new ArrayList<>();
        try (InputStream in = ContentGuard.class.getResourceAsStream("/content-guard.txt")) {
            if (in != null) {
                parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), loaded);
            }
        } catch (IOException e) {
            throw new IllegalStateException("content-guard.txt is unreadable", e);
        }
        if (extra != null && Files.isRegularFile(extra)) {
            try {
                parse(Files.readString(extra, StandardCharsets.UTF_8), loaded);
            } catch (IOException ignored) {
            }
        }
        this.terms = List.copyOf(loaded);
    }

    public int size() {
        return terms.size();
    }

    public Verdict scan(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = normalize(text);
        for (Term term : terms) {
            if (!term.soft() && normalized.contains(term.text())) {
                return new Verdict(term.category(), term.text(), false);
            }
        }
        return null;
    }

    public boolean suspicious(String text) {
        String normalized = normalize(text);
        return terms.stream().anyMatch(t -> t.soft() && normalized.contains(t.text()));
    }

    public Verdict review(ModelProfile profile, String context, String reply) {
        Verdict local = scan(reply);
        if (local != null || profile == null || DEFLECTIONS.contains(reply)
                || (normalize(reply).length() < MIN_REVIEW_CHARS && !suspicious(reply))) {
            return local;
        }
        String recent = context == null ? "" : context.strip();
        recent = recent.length() > 400 ? "…" + recent.substring(recent.length() - 400) : recent;
        String prompt = REVIEW_PROMPT.formatted(recent, abbreviate(reply, 1500));
        ChatRequest request = new ChatRequest(List.of(ChatMessage.user(prompt)), List.of(), false, "guard", profile.maxTokens());
        Future<ChatResult> pending = executor.submit(() -> {
            ModelProtocol protocol = Protocols.get(profile.protocol());
            return protocol.stream(profile, request, StreamListener.NONE);
        });
        try {
            return verdict(pending.get(REVIEW_TIMEOUT_MS, TimeUnit.MILLISECONDS).content());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AgentException failure && failure.kind() == Kind.CONTENT_POLICY) {
                return new Verdict(Category.RISKY, "", true);
            }
            // 这条路径是 fail-open（放行）。不改成一概拦下，是因为中转站抖一下
            // 就让她彻底哑掉代价更大；但至少要留痕，让主人看得见审核正在失效。
            System.err.println("WARN content guard: model review failed, reply went out unreviewed: " + e.getCause());
            return null;
        } catch (TimeoutException e) {
            pending.cancel(true);
            System.err.println("WARN content guard: model review timed out, reply went out unreviewed");
            return null;
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            System.err.println("WARN content guard: model review interrupted, reply went out unreviewed");
            return null;
        }
    }

    public static String deflection() {
        return DEFLECTIONS.get(ThreadLocalRandom.current().nextInt(DEFLECTIONS.size()));
    }

    public static String normalize(String text) {
        return NOISE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static Verdict verdict(String reply) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonElement json = JsonParser.parseString(reply.substring(start, end + 1));
            if (!json.isJsonObject()) {
                return null;
            }
            JsonObject object = json.getAsJsonObject();
            if (!object.has("block") || !object.get("block").isJsonPrimitive() || !object.get("block").getAsBoolean()) {
                return null;
            }
            Category category = switch (optString(object, "category")) {
                case "sexual" -> Category.SEXUAL;
                case "political" -> Category.POLITICAL;
                case "illegal" -> Category.ILLEGAL;
                default -> Category.RISKY;
            };
            return new Verdict(category, "", true);
        } catch (JsonParseException | UnsupportedOperationException | IllegalStateException e) {
            return null;
        }
    }

    private static void parse(String content, List<Term> out) {
        Category category = null;
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                category = switch (line.substring(1, line.length() - 1).strip().toLowerCase(Locale.ROOT)) {
                    case "sexual" -> Category.SEXUAL;
                    case "political" -> Category.POLITICAL;
                    case "illegal" -> Category.ILLEGAL;
                    default -> Category.RISKY;
                };
                continue;
            }
            boolean soft = line.startsWith("?");
            String term = normalize(soft ? line.substring(1) : line);
            if (category != null && !term.isEmpty()) {
                out.add(new Term(term, category, soft));
            }
        }
    }
}
