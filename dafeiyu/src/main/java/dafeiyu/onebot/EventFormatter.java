package dafeiyu.onebot;

import com.google.gson.JsonObject;

import static dafeiyu.util.Jsons.optObject;
import static dafeiyu.util.Jsons.optString;
import static dafeiyu.util.Texts.abbreviate;

final class EventFormatter {

    private static final int MAX_LENGTH = 200;

    private EventFormatter() {}

    static String describe(JsonObject event) {
        String postType = optString(event, "post_type");
        if (postType.equals("message")) {
            String where = optString(event, "message_type").equals("group")
                    ? "group " + optString(event, "group_id")
                    : "private";
            JsonObject sender = optObject(event, "sender");
            String name = sender == null ? "" : optString(sender, "nickname");
            String raw = optString(event, "raw_message");
            return "message [" + where + "] " + name + "(" + optString(event, "user_id") + "): "
                    + (raw.length() <= MAX_LENGTH ? raw : abbreviate(raw, MAX_LENGTH) + " (truncated in the log, " + raw.length() + " chars in total)");
        }
        return "event " + postType + " " + abbreviate(event.toString(), MAX_LENGTH);
    }
}
