package dafeiyu.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Clock {

    public static final long GAP_MS = 30 * 60_000L;
    private static final Pattern MARKER = Pattern.compile("【\\d{1,2}月\\d{1,2}日 [^】\\n]{1,20}】");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private Clock() {}

    public static String now() {
        return describe(LocalDateTime.now());
    }

    public static String marker(long millis) {
        return "【" + describe(local(millis)) + "】";
    }

    public static boolean isMarker(String text) {
        return text != null && MARKER.matcher(text).matches();
    }

    public static boolean gap(long last, long now) {
        return now - last >= GAP_MS;
    }

    public static String partOfDay(LocalTime t) {
        int h = t.getHour();
        if (h < 5) {
            return "凌晨";
        }
        if (h < 8) {
            return "早上";
        }
        if (h < 11) {
            return "上午";
        }
        if (h < 13) {
            return "中午";
        }
        if (h < 17) {
            return "下午";
        }
        if (h < 19) {
            return "傍晚";
        }
        return h < 23 ? "晚上" : "深夜";
    }

    public static String ago(long millis) {
        long minutes = (System.currentTimeMillis() - millis) / 60_000;
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        LocalDateTime then = local(millis);
        long days = ChronoUnit.DAYS.between(then.toLocalDate(), LocalDate.now());
        if (days <= 0) {
            return minutes / 60 + " 小时前";
        }
        String when = partOfDay(then.toLocalTime()) + " " + then.format(HOUR);
        if (days == 1) {
            return "昨天" + when;
        }
        if (days == 2) {
            return "前天" + when;
        }
        return days + " 天前（" + then.format(DATE) + "）";
    }

    public static String stamp(long millis) {
        LocalDateTime then = local(millis);
        return then.toLocalDate().equals(LocalDate.now()) ? then.format(HOUR) : then.format(DATE) + " " + then.format(HOUR);
    }

    public static String day(long millis) {
        return local(millis).format(DATE);
    }

    private static String describe(LocalDateTime t) {
        return t.format(DAY) + " " + partOfDay(t.toLocalTime()) + " " + t.format(HOUR);
    }

    private static LocalDateTime local(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}
