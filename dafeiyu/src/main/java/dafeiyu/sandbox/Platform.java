package dafeiyu.sandbox;

import java.util.Locale;

public enum Platform {
    WINDOWS, MAC, LINUX, OTHER;

    private static final Platform CURRENT = detect(System.getProperty("os.name", ""));

    public static Platform current() {
        return CURRENT;
    }

    public static boolean windows() {
        return CURRENT == WINDOWS;
    }

    static Platform detect(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MAC;
        }
        return name.contains("linux") ? LINUX : OTHER;
    }
}
