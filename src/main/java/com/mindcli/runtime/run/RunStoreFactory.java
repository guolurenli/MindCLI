package com.mindcli.runtime.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RunStoreFactory {
    private static final Logger log = LoggerFactory.getLogger(RunStoreFactory.class);
    private static final String RUNS_DIR_PROPERTY = "mindcli.runs.dir";
    private static final String RUNS_DIR_ENV = "MINDCLI_RUNS_DIR";
    private static final String DEFAULT_RUNS_DIR = Path.of(System.getProperty("user.home"), ".mindcli", "runs")
            .toString();

    private RunStoreFactory() {
    }

    public static RunStore create() {
        return create(defaultRunsRoot());
    }

    public static RunStore create(Path runsRoot) {
        try {
            Path configuredRoot = normalize(runsRoot);
            Files.createDirectories(configuredRoot);
            if (!Files.isDirectory(configuredRoot)) {
                throw new IllegalStateException("runs root is not a directory: " + configuredRoot);
            }
            return new JsonlRunStore(configuredRoot);
        } catch (Exception e) {
            log.warn("Falling back to in-memory run store: {}", e.getMessage());
            return new InMemoryRunStore();
        }
    }

    public static Path defaultRunsRoot() {
        String configured = System.getProperty(RUNS_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(RUNS_DIR_ENV);
        }
        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_RUNS_DIR;
        }
        return Path.of(configured);
    }

    private static Path normalize(Path path) {
        Path normalized = Objects.requireNonNull(path, "runsRoot").toAbsolutePath().normalize();
        if (Files.exists(normalized) && !Files.isDirectory(normalized)) {
            throw new IllegalStateException("runs root exists but is not a directory: " + normalized);
        }
        return normalized;
    }
}
