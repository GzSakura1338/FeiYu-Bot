package dafeiyu.plugin;

import dafeiyu.plugin.manifest.Permission;
import dafeiyu.plugin.manifest.PluginManifest;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record PluginView(String id, Path folder, String origin, PluginManifest manifest, List<String> problems, List<String> warnings,
                         String status, String lastError, boolean enabled, List<PluginManifest.Tool> tools, long startupMillis, long pid,
                         long runningSince, Map<String, LocalDateTime> nextFire, Set<Permission> granted, Set<Permission> approved) {

    public String name() {
        return manifest == null ? id : manifest.name();
    }
}
