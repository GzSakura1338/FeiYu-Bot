package dafeiyu.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dafeiyu.agent.ChatAgent;
import dafeiyu.agent.model.ModelProfile;
import dafeiyu.agent.persona.Persona;
import dafeiyu.agent.usage.UsageStore;
import dafeiyu.onebot.OneBotService;
import dafeiyu.onebot.OneBotStatus;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

final class UsageHandler {

    private static final int WEEKS = 53;
    private static final int YEARS = 7;
    private static final Map<String, String> PURPOSES = Map.of("chat", "聊天回复", "mind", "内心", "compact", "压缩上下文", "guard", "内容审核",
            "sticker", "表情包识图", "reflect", "整理记忆", "probe", "能力检测", "subagent", "子 agent");

    private final ChatAgent agent;
    private final UsageStore usage;
    private final QqDirectory directory;
    private final OneBotService onebot;

    UsageHandler(ChatAgent agent, UsageStore usage, QqDirectory directory, OneBotService onebot) {
        this.agent = agent;
        this.usage = usage;
        this.directory = directory;
        this.onebot = onebot;
    }

    void handle(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            HttpResponses.json(ex, 405, "{\"error\":\"不支持的请求方法\"}");
            return;
        }
        if (ex.getRequestURI().getPath().endsWith("/range")) {
            LocalDate from;
            LocalDate to;
            try {
                from = LocalDate.parse(query(ex, "from"));
                to = query(ex, "to").isEmpty() ? from : LocalDate.parse(query(ex, "to"));
            } catch (java.time.format.DateTimeParseException e) {
                HttpResponses.json(ex, 400, "{\"error\":\"日期要写成 2026-10-28 这样\"}");
                return;
            }
            if (to.isBefore(from) || to.isAfter(from.plusDays(400))) {
                HttpResponses.json(ex, 400, "{\"error\":\"日期范围不对\"}");
                return;
            }
            HttpResponses.json(ex, range(from, to).toString());
            return;
        }
        HttpResponses.json(ex, body().toString());
    }

    private JsonObject range(LocalDate from, LocalDate to) {
        String where = UsageStore.between(from, to);
        UsageStore.Totals totals = usage.totals(where);
        JsonObject body = new JsonObject();
        body.addProperty("from", from.toString());
        body.addProperty("to", to.toString());
        JsonObject stats = new JsonObject();
        stats.addProperty("tokens", totals.tokens());
        stats.addProperty("prompt", totals.prompt());
        stats.addProperty("completion", totals.completion());
        stats.addProperty("cached", totals.cached());
        stats.addProperty("calls", totals.calls());
        stats.addProperty("longestMs", totals.longestMs());
        stats.addProperty("avgMs", totals.avgMs());
        body.add("stats", stats);
        breakdown(body, where);
        return body;
    }

    private void breakdown(JsonObject body, String where) {
        String and = where.isEmpty() ? "" : " AND " + where;
        body.add("purposes", shares(usage.by("purpose", where, 10), s -> PURPOSES.getOrDefault(s, s)));
        body.add("efforts", shares(usage.by("effort", "purpose IN ('chat', 'mind')" + and, 10), s -> s.isEmpty() ? "default" : s));
        body.add("models", shares(usage.by("model", where, 5), s -> s));
        QqDirectory.Names names = directory.names();
        body.add("sessions", shares(usage.by("session", "session <> ''" + and, 6), s -> s.startsWith("mind:") ? "内心"
                : s.startsWith("web:") ? "网页测试对话" : s.startsWith("task:") ? "后台任务 #" + s.substring(5) : names.of(s)));
    }

    private JsonObject body() {
        JsonObject body = new JsonObject();
        Persona persona = agent.personas().active();
        ModelProfile model = agent.store().active();
        OneBotStatus status = onebot.status();
        JsonObject profile = new JsonObject();
        profile.addProperty("name", persona == null ? "大肥鱼" : persona.name());
        profile.addProperty("avatar", persona == null ? "" : persona.avatar());
        profile.addProperty("qq", status.selfId() == null ? "" : status.selfId());
        profile.addProperty("nickname", status.nickname() == null ? "" : status.nickname());
        profile.addProperty("model", model == null ? "" : model.model());
        body.add("profile", profile);

        LocalDate today = LocalDate.now();
        LocalDate start = today.minusWeeks(WEEKS - 1).with(java.time.DayOfWeek.MONDAY);
        JsonArray days = new JsonArray();
        for (UsageStore.Day d : usage.days(LocalDate.of(today.getYear() - YEARS + 1, 1, 1).toString())) {
            JsonObject day = new JsonObject();
            day.addProperty("day", d.day());
            day.addProperty("tokens", d.tokens());
            day.addProperty("calls", d.calls());
            days.add(day);
        }
        body.addProperty("today", today.toString());
        body.addProperty("start", start.toString());
        body.add("days", days);

        UsageStore.Totals totals = usage.totals();
        UsageStore.Day peak = usage.peak();
        int[] streaks = streaks(usage.activeDays(), today);
        JsonObject stats = new JsonObject();
        stats.addProperty("lifetime", totals.tokens());
        stats.addProperty("prompt", totals.prompt());
        stats.addProperty("completion", totals.completion());
        stats.addProperty("cached", totals.cached());
        stats.addProperty("calls", totals.calls());
        stats.addProperty("longestMs", totals.longestMs());
        stats.addProperty("avgMs", totals.avgMs());
        stats.addProperty("since", totals.firstDay());
        stats.addProperty("peak", peak == null ? 0 : peak.tokens());
        stats.addProperty("peakDay", peak == null ? "" : peak.day());
        stats.addProperty("currentStreak", streaks[0]);
        stats.addProperty("longestStreak", streaks[1]);
        body.add("stats", stats);

        breakdown(body, "");
        return body;
    }

    private static String query(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith(key + "=")) {
                    return java.net.URLDecoder.decode(pair.substring(key.length() + 1), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    private static JsonArray shares(List<UsageStore.Share> list, java.util.function.Function<String, String> label) {
        JsonArray out = new JsonArray();
        for (UsageStore.Share s : list) {
            JsonObject item = new JsonObject();
            item.addProperty("key", s.key());
            item.addProperty("label", label.apply(s.key() == null ? "" : s.key()));
            item.addProperty("tokens", s.tokens());
            item.addProperty("calls", s.calls());
            out.add(item);
        }
        return out;
    }

    private static int[] streaks(List<String> days, LocalDate today) {
        int longest = 0;
        int run = 0;
        LocalDate previous = null;
        for (String raw : days) {
            LocalDate day = LocalDate.parse(raw);
            run = previous != null && previous.plusDays(1).equals(day) ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = day;
        }
        int current = previous != null && (previous.equals(today) || previous.equals(today.minusDays(1))) ? run : 0;
        return new int[] {current, longest};
    }
}
