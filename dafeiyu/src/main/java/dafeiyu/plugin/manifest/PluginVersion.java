package dafeiyu.plugin.manifest;

public record PluginVersion(int major, int minor, int patch, String pre) implements Comparable<PluginVersion> {

    public static PluginVersion parse(String text) {
        String core = text.strip();
        String pre = "";
        int cut = indexOfAny(core, '-', '+');
        if (cut >= 0) {
            pre = core.charAt(cut) == '-' ? core.substring(cut + 1) : "";
            core = core.substring(0, cut);
        }
        String[] parts = core.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("版本号要写成 1.2.3：" + text);
        }
        try {
            return new PluginVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), pre);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("版本号要写成 1.2.3：" + text);
        }
    }

    @Override
    public int compareTo(PluginVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        if (c == 0) {
            c = Integer.compare(patch, o.patch);
        }
        if (c == 0 && !pre.equals(o.pre)) {
            c = pre.isEmpty() ? 1 : o.pre.isEmpty() ? -1 : pre.compareTo(o.pre);
        }
        return c;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (pre.isEmpty() ? "" : "-" + pre);
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }
}
