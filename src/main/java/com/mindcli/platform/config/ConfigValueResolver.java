package com.mindcli.platform.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Resolves configuration values through one documented precedence chain. */
public final class ConfigValueResolver {
    private final Function<String, String> propertyLookup;
    private final Function<String, String> environmentLookup;
    private final Map<String, String> projectDotEnv;
    private final Map<String, String> userDotEnv;

    public ConfigValueResolver(Path projectDir, Path userHome) {
        this(projectDir, userHome, System::getProperty, System::getenv);
    }

    ConfigValueResolver(Path projectDir,
                        Path userHome,
                        Function<String, String> propertyLookup,
                        Function<String, String> environmentLookup) {
        Path project = Objects.requireNonNull(projectDir, "projectDir").toAbsolutePath().normalize();
        Path home = Objects.requireNonNull(userHome, "userHome").toAbsolutePath().normalize();
        this.propertyLookup = Objects.requireNonNull(propertyLookup, "propertyLookup");
        this.environmentLookup = Objects.requireNonNull(environmentLookup, "environmentLookup");
        this.projectDotEnv = readDotEnv(project.resolve(".env"));
        this.userDotEnv = readDotEnv(home.resolve(".env"));
    }

    public String resolve(String key, String defaultValue) {
        return resolve(key, key, defaultValue);
    }

    public String resolve(String propertyKey, String environmentKey, String defaultValue) {
        String value = nonBlank(propertyLookup.apply(propertyKey));
        if (value != null) {
            return value;
        }
        value = nonBlank(environmentLookup.apply(environmentKey));
        if (value != null) {
            return value;
        }
        value = nonBlank(projectDotEnv.get(environmentKey));
        if (value != null) {
            return value;
        }
        value = nonBlank(userDotEnv.get(environmentKey));
        return value == null ? defaultValue : value;
    }

    private static Map<String, String> readDotEnv(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String rawLine : Files.readAllLines(file)) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = stripOptionalQuotes(line.substring(separator + 1).trim());
                if (!key.isEmpty() && !value.isBlank()) {
                    values.put(key, value);
                }
            }
        } catch (IOException ignored) {
            return Map.of();
        }
        return Map.copyOf(values);
    }

    private static String stripOptionalQuotes(String value) {
        if (value != null && value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value == null ? "" : value;
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
