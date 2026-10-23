package dafeiyu.agent.tool;

import java.util.HashMap;
import java.util.Map;

final class LineDiff {

    record Stats(int added, int removed) {

        static final Stats NONE = new Stats(0, 0);

        Stats plus(Stats other) {
            return new Stats(added + other.added, removed + other.removed);
        }

        String label() {
            return "+" + added + " -" + removed;
        }
    }

    private static final long MAX_CELLS = 4_000_000;

    private LineDiff() {}

    static Stats added(String text) {
        return new Stats(lines(text).length, 0);
    }

    static Stats removed(String text) {
        return new Stats(0, lines(text).length);
    }

    static Stats of(String before, String after) {
        String[] a = lines(before);
        String[] b = lines(after);
        int start = 0;
        while (start < a.length && start < b.length && a[start].equals(b[start])) {
            start++;
        }
        int endA = a.length;
        int endB = b.length;
        while (endA > start && endB > start && a[endA - 1].equals(b[endB - 1])) {
            endA--;
            endB--;
        }
        int n = endA - start;
        int m = endB - start;
        if (n == 0 || m == 0) {
            return new Stats(m, n);
        }
        int common = (long) n * m <= MAX_CELLS ? lcs(a, b, start, endA, endB) : shared(a, b, start, endA, endB);
        return new Stats(m - common, n - common);
    }

    private static String[] lines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        String[] parts = text.split("\r?\n", -1);
        if (parts[parts.length - 1].isEmpty()) {
            String[] trimmed = new String[parts.length - 1];
            System.arraycopy(parts, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return parts;
    }

    private static int lcs(String[] a, String[] b, int start, int endA, int endB) {
        int[] previous = new int[endB - start + 1];
        int[] current = new int[endB - start + 1];
        for (int i = start; i < endA; i++) {
            for (int j = start; j < endB; j++) {
                int k = j - start + 1;
                current[k] = a[i].equals(b[j]) ? previous[k - 1] + 1 : Math.max(previous[k], current[k - 1]);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[endB - start];
    }

    private static int shared(String[] a, String[] b, int start, int endA, int endB) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = start; i < endA; i++) {
            counts.merge(a[i], 1, Integer::sum);
        }
        int common = 0;
        for (int j = start; j < endB; j++) {
            Integer left = counts.get(b[j]);
            if (left != null && left > 0) {
                counts.put(b[j], left - 1);
                common++;
            }
        }
        return common;
    }
}
