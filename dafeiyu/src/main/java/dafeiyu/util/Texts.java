package dafeiyu.util;

public final class Texts {

    private Texts() {}

    public static String abbreviate(String s, int maxLength) {
        return s.length() > maxLength ? s.substring(0, maxLength) + "…" : s;
    }
}
