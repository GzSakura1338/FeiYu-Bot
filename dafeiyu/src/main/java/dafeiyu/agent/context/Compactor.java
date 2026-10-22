package dafeiyu.agent.context;

import dafeiyu.agent.AgentException;
import dafeiyu.agent.AgentException.Kind;
import dafeiyu.agent.ChatMessage;
import dafeiyu.agent.ChatResult;
import dafeiyu.agent.Prompt;
import dafeiyu.agent.StreamListener;
import dafeiyu.agent.context.ConversationMemory.Conversation;
import dafeiyu.agent.model.ModelLimits.Limits;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.model.ModelRetry;
import dafeiyu.agent.protocol.ChatRequest;
import dafeiyu.agent.protocol.ModelProtocol;
import dafeiyu.agent.protocol.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class Compactor {

    public enum Trigger { AUTO, OVERFLOW, MANUAL }

    public record Target(String session, ModelProfile profile, ModelProtocol protocol, Limits limits, List<ToolSpec> tools, boolean mind) {

        int window() {
            return limits.context() > 0 ? limits.context() : Compaction.UNKNOWN_WINDOW;
        }

        int keep() {
            return mind ? Compaction.MIND_KEEP_TOKENS : Compaction.KEEP_TOKENS;
        }

        String nudge() {
            return mind ? Compaction.MIND_NUDGE : Compaction.NUDGE;
        }
    }

    private static final int SUMMARY_OUTPUT_MAX = Compaction.RESERVE_MIN;
    private static final int UNKNOWN_OUTPUT = 8_192;
    private static final int OUTPUT_MIN = 1_024;

    private final ConversationMemory memory;

    public Compactor(ConversationMemory memory) {
        this.memory = memory;
    }

    public Conversation compact(Trigger trigger, Target target, List<ChatMessage> head, Conversation conversation, long contextEstimate,
                         StreamListener listener, ModelRetry retry) throws AgentException {
        List<ChatMessage> turns = conversation.turns();
        boolean previous = conversation.hasCheckpoint();
        int start = Compaction.recentStart(turns, target.keep(), conversation.keptRecent());
        if (turns.isEmpty() && !previous) {
            throw new AgentException(Kind.LOCAL, "nothing to compact yet");
        }
        listener.onCompaction(trigger.name().toLowerCase(java.util.Locale.ROOT));

        long cap = Compaction.ceiling(target.window());
        long budget = trigger == Trigger.OVERFLOW ? Math.min(cap, (long) Math.floor(contextEstimate * Compaction.SHRINK_STEPS[0])) : cap;
        String prompt = Compaction.buildPrompt(previous, target.mind());
        long overhead = Compaction.estimate(prompt) + Compaction.estimate(target.nudge());
        long fit = (trigger == Trigger.OVERFLOW ? budget : Math.max(budget, Compaction.summaryRoom(target.window()))) - overhead;
        long aim = budget - overhead;

        boolean toolsOffered = !target.tools().isEmpty();
        List<ChatMessage> full = join(head, Prompt.history(conversation, true));
        List<ChatMessage> withoutTail = join(head, Prompt.history(
                new Conversation(conversation.summary(), conversation.recent(), turns.subList(0, Math.min(start, turns.size())), 0, ""), true));

        int rejections = 0;
        long rejected = -1;
        boolean asText = false;
        while (true) {
            List<ChatMessage> request = null;
            if (!asText) {
                if (Compaction.estimate(full) <= fit) {
                    request = full;
                } else if (Compaction.estimate(withoutTail) <= aim) {
                    request = withoutTail;
                }
            }
            if (request == null) {
                request = flattened(head, conversation, aim);
            }
            if (request == null && rejections == 0) {
                request = asText ? flattened(head, conversation, Long.MAX_VALUE) : withoutTail;
            }
            if (request == null) {
                throw new AgentException(Kind.LOCAL, "the conversation cannot be compacted further without losing the latest exchange");
            }
            try {
                String summary = summarize(target, Prompt.forRequest(request, toolsOffered), prompt);
                List<ChatMessage> kept = turns.subList(Math.min(start, turns.size()), turns.size());
                String recent = kept.stream().map(Compaction::recentText).filter(s -> !s.isEmpty()).collect(Collectors.joining("\n\n"));
                memory.checkpoint(target.session(), summary, recent);
                return memory.load(target.session());
            } catch (AgentException e) {
                boolean tooLarge = e.kind() == Kind.PAYLOAD_TOO_LARGE;
                if (tooLarge && (request == full || request == withoutTail)) {
                    asText = true;
                    continue;
                }
                if (tooLarge || e.isContextOverflow()) {
                    if (rejections >= Compaction.SHRINK_STEPS.length) {
                        throw e;
                    }
                    if (rejected < 0) {
                        rejected = Compaction.estimate(request);
                    }
                    aim = (long) Math.floor(rejected * Compaction.SHRINK_STEPS[rejections]);
                    fit = aim;
                    rejections++;
                    continue;
                }
                retry.backoff(e);
            }
        }
    }

    private String summarize(Target target, List<ChatMessage> request, String prompt) throws AgentException {
        List<ChatMessage> prompted = new ArrayList<>(request);
        prompted.add(ChatMessage.user(prompt));
        String reply = send(target, prompted);
        if (Compaction.filled(reply, target.mind())) {
            return reply;
        }
        prompted.add(ChatMessage.user(target.nudge()));
        String retry = send(target, prompted);
        if (Compaction.filled(retry, target.mind())) {
            return retry;
        }
        throw new AgentException(Kind.LOCAL, retry.isBlank()
                ? "compaction produced no summary"
                : "compaction summary did not match the required template");
    }

    private String send(Target target, List<ChatMessage> messages) throws AgentException {
        int output = Math.min(SUMMARY_OUTPUT_MAX, target.limits().output() > 0 ? target.limits().output() : UNKNOWN_OUTPUT);
        if (target.limits().context() > 0) {
            output = (int) Math.max(OUTPUT_MIN, Math.min(output, target.limits().context() - Compaction.estimate(messages)));
        }
        ChatRequest request = new ChatRequest(Prompt.mergeUsers(messages), target.tools(), true, target.session(), output, "", "compact");
        ChatResult result;
        try {
            result = target.protocol().stream(target.profile(), request, StreamListener.NONE);
        } catch (AgentException e) {
            if (e.kind() == Kind.EMPTY_REPLY) {
                return "";
            }
            throw e;
        }
        if (result.finishReason().equals("length") || result.finishReason().equals("max_tokens") || result.finishReason().equals("max_output_tokens")) {
            throw new AgentException(Kind.LOCAL, "compaction summary reached the output token limit");
        }
        return result.content().strip();
    }

    private static List<ChatMessage> flattened(List<ChatMessage> head, Conversation conversation, long aim) {
        List<ChatMessage> lead = new ArrayList<>(head);
        if (conversation.hasCheckpoint()) {
            lead.add(ChatMessage.user(Compaction.checkpointMessage(conversation.summary(), null)));
        }
        List<String> exchanges = Compaction.exchanges(conversation.turns(), conversation.recent());
        long room = aim - Compaction.estimate(lead) - Compaction.estimate(Compaction.omittedNote(exchanges.size()));
        List<Integer> sizes = exchanges.stream().map(e -> Compaction.estimate(e) + 1).toList();
        int from = Compaction.oldestToDrop(sizes, (int) Math.max(0, Math.min(Integer.MAX_VALUE, room)));
        List<String> kept = exchanges.subList(from, exchanges.size());
        if (kept.isEmpty()) {
            return null;
        }
        lead.add(ChatMessage.user(Compaction.omittedNote(exchanges.size() - kept.size()) + String.join("\n\n", kept)));
        return lead;
    }

    private static List<ChatMessage> join(List<ChatMessage> a, List<ChatMessage> b) {
        List<ChatMessage> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
