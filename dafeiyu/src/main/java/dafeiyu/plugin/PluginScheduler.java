package dafeiyu.plugin;

import com.google.gson.JsonObject;
import dafeiyu.plugin.manifest.PluginManifest;

import java.time.LocalDateTime;

final class PluginScheduler {

    private final PluginHost host;

    PluginScheduler(PluginHost host) {
        this.host = host;
    }

    void arm(PluginSlot slot) {
        slot.nextFire.clear();
        PluginManifest m = slot.manifest;
        if (m == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (PluginManifest.Schedule s : m.schedules()) {
            LocalDateTime next = s.parsed() == null ? null : s.parsed().next(now);
            if (next != null) {
                slot.nextFire.put(s.id(), next);
            }
        }
    }

    void fireNow(PluginSlot slot, String scheduleId) {
        PluginManifest m = slot.manifest;
        if (m == null || !slot.running()) {
            throw new IllegalArgumentException("插件没在运行");
        }
        if (m.schedules().stream().noneMatch(s -> s.id().equals(scheduleId))) {
            throw new IllegalArgumentException("没有这个定时任务：" + scheduleId);
        }
        JsonObject params = new JsonObject();
        params.addProperty("id", scheduleId);
        params.addProperty("scheduledFor", LocalDateTime.now().toString());
        params.addProperty("manual", true);
        slot.process.notify("schedule/fire", params);
        host.pluginLog(slot, "info", "手动执行了一次定时任务「" + scheduleId + "」");
    }

    void fire(LocalDateTime now) {
        for (PluginSlot slot : host.sorted()) {
            PluginManifest m = slot.manifest;
            if (m == null || !slot.running()) {
                continue;
            }
            for (PluginManifest.Schedule s : m.schedules()) {
                LocalDateTime due = slot.nextFire.get(s.id());
                if (due == null || now.isBefore(due) || s.parsed() == null) {
                    continue;
                }
                LocalDateTime next = s.parsed().next(now);
                if (next == null) {
                    slot.nextFire.remove(s.id());
                } else {
                    slot.nextFire.put(s.id(), next);
                }
                JsonObject params = new JsonObject();
                params.addProperty("id", s.id());
                params.addProperty("scheduledFor", due.toString());
                slot.process.notify("schedule/fire", params);
                host.pluginLog(slot, "info", "定时任务「" + s.id() + "」触发了");
            }
        }
    }
}
