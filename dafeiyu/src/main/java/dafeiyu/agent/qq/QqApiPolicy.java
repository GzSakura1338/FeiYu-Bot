package dafeiyu.agent.qq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dafeiyu.db.Database;
import dafeiyu.db.Settings;
import dafeiyu.util.Jsons;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class QqApiPolicy {

    public enum Access { READ, WRITE }

    public record Config(boolean enabled, Map<String, Boolean> overrides, Map<String, Access> access) {

        public Config {
            overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
            access = access == null ? Map.of() : Map.copyOf(access);
        }
    }

    private static final String ENABLED_KEY = "agent.qqapi.enabled";
    private static final String OVERRIDES_KEY = "agent.qqapi.overrides";
    private static final String ACCESS_KEY = "agent.qqapi.access";
    private static final Pattern READ = Pattern.compile("^(get_|fetch_|can_|check_|nc_get_|_get_)");
    private static final Set<String> ALSO_READ = Set.of("translate_en2zh", "ocr_image", "ArkSharePeer", "ArkShareGroup", "send_ark_share",
            "send_group_ark_share", "get_mini_app_ark");
    private static final String CREDENTIAL = "能拿到 QQ 的登录凭证或密钥，泄露出去别人就能登这个号";
    private static final String ACCOUNT = "会让机器人下线、重启，或者改掉账号本身的资料";
    private static final String INTERNAL = "NapCat 内部或调试用的接口";
    private static final String STREAM = "流式接口，要分段收发，这里收不了";
    private static final Map<String, String> LOCKED = Map.ofEntries(
            Map.entry("get_cookies", CREDENTIAL), Map.entry("get_csrf_token", CREDENTIAL), Map.entry("get_credentials", CREDENTIAL),
            Map.entry("get_clientkey", CREDENTIAL), Map.entry("get_rkey", CREDENTIAL), Map.entry("get_rkey_server", CREDENTIAL),
            Map.entry("nc_get_rkey", CREDENTIAL),
            Map.entry("bot_exit", ACCOUNT), Map.entry("set_restart", ACCOUNT), Map.entry("clean_cache", ACCOUNT), Map.entry("_set_model_show", ACCOUNT),
            Map.entry("set_qq_profile", ACCOUNT), Map.entry("set_qq_avatar", ACCOUNT),
            Map.entry("set_group_leave", "会退出群，自己是群主时会直接解散群"),
            Map.entry("send_packet", INTERNAL), Map.entry(".handle_quick_operation", INTERNAL), Map.entry(".ocr_image", INTERNAL),
            Map.entry("test_auto_register_01", INTERNAL), Map.entry("test_auto_register_02", INTERNAL),
            Map.entry("upload_file_stream", STREAM), Map.entry("download_file_stream", STREAM), Map.entry("download_file_image_stream", STREAM),
            Map.entry("download_file_record_stream", STREAM), Map.entry("test_download_stream", STREAM), Map.entry("clean_stream_temp_file", STREAM));

    private final Settings settings;

    public QqApiPolicy(Database db) {
        this.settings = new Settings(db);
    }

    public static Access defaultAccess(String name) {
        return READ.matcher(name).find() || ALSO_READ.contains(name) ? Access.READ : Access.WRITE;
    }

    public static boolean locked(String name) {
        return LOCKED.containsKey(name);
    }

    public static String lockReason(String name) {
        return LOCKED.getOrDefault(name, "");
    }

    public static boolean defaultAllowed(String name) {
        return !locked(name);
    }

    public synchronized Config config() {
        Map<String, Boolean> overrides = new LinkedHashMap<>();
        JsonObject saved = Jsons.parseObject(settings.get(OVERRIDES_KEY));
        if (saved != null) {
            for (Map.Entry<String, JsonElement> e : saved.entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isBoolean()) {
                    overrides.put(e.getKey(), e.getValue().getAsBoolean());
                }
            }
        }
        Map<String, Access> access = new LinkedHashMap<>();
        JsonObject kinds = Jsons.parseObject(settings.get(ACCESS_KEY));
        if (kinds != null) {
            for (Map.Entry<String, JsonElement> e : kinds.entrySet()) {
                String value = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "";
                if (value.equals(Access.READ.name()) || value.equals(Access.WRITE.name())) {
                    access.put(e.getKey(), Access.valueOf(value));
                }
            }
        }
        return new Config(!"false".equals(settings.get(ENABLED_KEY)), overrides, access);
    }

    public synchronized Config update(boolean enabled, Map<String, Boolean> changes, Map<String, Access> accessChanges) {
        Config current = config();
        JsonObject overrides = new JsonObject();
        current.overrides().forEach(overrides::addProperty);
        changes.forEach((name, on) -> {
            if (on == defaultAllowed(name)) {
                overrides.remove(name);
            } else {
                overrides.addProperty(name, on);
            }
        });
        JsonObject access = new JsonObject();
        current.access().forEach((name, kind) -> access.addProperty(name, kind.name()));
        accessChanges.forEach((name, kind) -> {
            if (kind == defaultAccess(name)) {
                access.remove(name);
            } else {
                access.addProperty(name, kind.name());
            }
        });
        settings.put(ENABLED_KEY, String.valueOf(enabled));
        settings.put(OVERRIDES_KEY, overrides.toString());
        settings.put(ACCESS_KEY, access.toString());
        return config();
    }

    public Access access(String name) {
        return config().access().getOrDefault(name, defaultAccess(name));
    }

    public boolean allowed(String name) {
        Config config = config();
        return config.enabled() && config.overrides().getOrDefault(name, defaultAllowed(name));
    }
}
