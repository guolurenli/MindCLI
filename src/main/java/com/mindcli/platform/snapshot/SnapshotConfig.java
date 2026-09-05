package com.mindcli.platform.snapshot;

import com.mindcli.platform.config.ConfigValueResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record SnapshotConfig(
        boolean enabled,
        Path snapshotsRoot,
        int maxSnapshots,
        List<String> excludes
) {
    private static final List<String> DEFAULT_EXCLUDES = List.of(
            ".git",
            ".mindcli/snapshots",
            "target",
            "node_modules",
            "dist",
            ".idea",
            "*.class",
            "*.jar"
    );

    public static SnapshotConfig fromEnvironment() {
        ConfigValueResolver config = ConfigValueResolver.current();
        boolean enabled = config.resolveBoolean("mindcli.snapshot.enabled", "MINDCLI_SNAPSHOT_ENABLED", true);
        Path root = Path.of(config.resolve("mindcli.snapshot.dir", "MINDCLI_SNAPSHOT_DIR",
                Path.of(System.getProperty("user.home"), ".mindcli", "snapshots").toString()));
        int max = config.resolveInt("mindcli.snapshot.max", "MINDCLI_SNAPSHOT_MAX", 50);
        List<String> excludes = mergeExcludes(config.resolve("mindcli.snapshot.excludes", "MINDCLI_SNAPSHOT_EXCLUDES", ""));
        return new SnapshotConfig(enabled, root, Math.max(1, max), excludes);
    }

    public SnapshotConfig withEnabled(boolean enabled) {
        return new SnapshotConfig(enabled, snapshotsRoot, maxSnapshots, excludes);
    }

    private static List<String> mergeExcludes(String configured) {
        Set<String> merged = new LinkedHashSet<>(DEFAULT_EXCLUDES);
        if (configured != null && !configured.isBlank()) {
            for (String item : configured.split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    merged.add(trimmed);
                }
            }
        }
        return new ArrayList<>(merged);
    }
}
