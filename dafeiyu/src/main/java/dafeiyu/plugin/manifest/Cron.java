package dafeiyu.plugin.manifest;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;

public final class Cron {

    private static final int SEARCH_MINUTES = 366 * 24 * 60;

    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet days;
    private final BitSet months;
    private final BitSet weekdays;
    private final boolean anyDay;
    private final boolean anyWeekday;

    private Cron(BitSet minutes, BitSet hours, BitSet days, BitSet months, BitSet weekdays, boolean anyDay, boolean anyWeekday) {
        this.minutes = minutes;
        this.hours = hours;
        this.days = days;
        this.months = months;
        this.weekdays = weekdays;
        this.anyDay = anyDay;
        this.anyWeekday = anyWeekday;
    }

    public static Cron parse(String expression) {
        String[] f = expression.strip().split("\\s+");
        if (f.length != 5) {
            throw new IllegalArgumentException("要有 5 段：分 时 日 月 星期");
        }
        BitSet weekdays = field(f[4], 0, 7, "星期");
        if (weekdays.get(7)) {
            weekdays.set(0);
            weekdays.clear(7);
        }
        return new Cron(field(f[0], 0, 59, "分"), field(f[1], 0, 23, "时"), field(f[2], 1, 31, "日"), field(f[3], 1, 12, "月"), weekdays,
                f[2].equals("*"), f[4].equals("*"));
    }

    public boolean matches(LocalDateTime t) {
        if (!minutes.get(t.getMinute()) || !hours.get(t.getHour()) || !months.get(t.getMonthValue())) {
            return false;
        }
        boolean day = days.get(t.getDayOfMonth());
        boolean weekday = weekdays.get(t.getDayOfWeek().getValue() % 7);
        if (anyDay && anyWeekday) {
            return true;
        }
        if (anyDay) {
            return weekday;
        }
        if (anyWeekday) {
            return day;
        }
        return day || weekday;
    }

    public LocalDateTime next(LocalDateTime after) {
        LocalDateTime t = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        for (int i = 0; i < SEARCH_MINUTES; i++, t = t.plusMinutes(1)) {
            if (matches(t)) {
                return t;
            }
        }
        return null;
    }

    private static BitSet field(String text, int min, int max, String label) {
        BitSet set = new BitSet(max + 1);
        for (String part : text.split(",")) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException(label + "那一段有空项");
            }
            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                step = number(part.substring(slash + 1), label);
                if (step < 1) {
                    throw new IllegalArgumentException(label + "的步长要大于 0");
                }
                range = part.substring(0, slash);
            }
            int from;
            int to;
            if (range.equals("*")) {
                from = min;
                to = max;
            } else if (range.contains("-")) {
                String[] ends = range.split("-", 2);
                from = number(ends[0], label);
                to = number(ends[1], label);
            } else {
                from = number(range, label);
                to = slash >= 0 ? max : from;
            }
            if (from < min || to > max || from > to) {
                throw new IllegalArgumentException(label + "「" + part + "」超出范围 " + min + "-" + max);
            }
            for (int v = from; v <= to; v += step) {
                set.set(v);
            }
        }
        return set;
    }

    private static int number(String text, String label) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "「" + text + "」不是数字");
        }
    }
}
