package dafeiyu.util;

import java.util.HashSet;
import java.util.Set;

public final class Texts {

    private Texts() {}

    public static String abbreviate(String s, int maxLength) {
        return s.length() > maxLength ? s.substring(0, maxLength) + "…" : s;
    }

    public static Set<String> bigrams(String text) {
        String s = text.toLowerCase().replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]", "");
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    public static double overlap(Set<String> part, Set<String> whole) {
        if (part.isEmpty() || whole.isEmpty()) {
            return 0;
        }
        Set<String> both = new HashSet<>(part);
        both.retainAll(whole);
        return (double) both.size() / part.size();
    }
}
