package dafeiyu.agent.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ThinkBlocks {

    private static final Pattern BLOCK = Pattern.compile("(?ims)^[ \\t]*<(think|thinking|thought|reasoning)>(.*?)(?:</\\1>|\\z)");
    private static final Pattern STRAY_CLOSE = Pattern.compile("(?im)^[ \\t]*</(?:think|thinking)>[ \\t]*$");

    record Split(String thinking, String answer) {}

    private ThinkBlocks() {}

    static Split split(String content) {
        List<String> thoughts = new ArrayList<>();
        Matcher block = BLOCK.matcher(content);
        StringBuilder answer = new StringBuilder();
        while (block.find()) {
            thoughts.add(block.group(2).strip());
            block.appendReplacement(answer, "");
        }
        block.appendTail(answer);
        String rest = answer.toString();
        if (thoughts.isEmpty()) {
            Matcher stray = STRAY_CLOSE.matcher(rest);
            if (stray.find()) {
                thoughts.add(rest.substring(0, stray.start()).strip());
                rest = rest.substring(stray.end());
            }
        }
        String thinking = String.join("\n\n", thoughts.stream().filter(t -> !t.isEmpty()).toList());
        return new Split(thinking, rest.strip());
    }
}
