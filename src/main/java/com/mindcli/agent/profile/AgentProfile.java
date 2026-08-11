package com.mindcli.agent.profile;

import com.mindcli.agent.AgentRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record AgentProfile(
        String name,
        AgentRole role,
        String description,
        List<String> tools,
        List<String> deniedTools,
        List<String> commandAllowlist,
        String model,
        int maxConcurrency,
        String permissionMode,
        String memoryScope,
        String contextMode
) {
    public AgentProfile {
        name = normalizeRequired(name, "name");
        role = Objects.requireNonNull(role, "role");
        description = description == null ? "" : description.trim();
        tools = immutableTrimmed(tools);
        deniedTools = immutableTrimmed(deniedTools);
        commandAllowlist = immutableTrimmed(commandAllowlist);
        model = blankToDefault(model, "auto");
        maxConcurrency = Math.max(1, maxConcurrency);
        permissionMode = blankToDefault(permissionMode, "LEGACY_COMPAT").toUpperCase(Locale.ROOT);
        memoryScope = blankToDefault(memoryScope, "PARENT_SUMMARY").toUpperCase(Locale.ROOT);
        contextMode = blankToDefault(contextMode, "balanced").toLowerCase(Locale.ROOT);
    }

    public static AgentProfile legacy(String name, AgentRole role) {
        List<String> tools = switch (role) {
            case PLANNER -> List.of();
            case WORKER -> List.of("*");
            case REVIEWER -> List.of("read_file", "list_dir", "glob_files", "grep_code", "execute_command");
        };
        String permissionMode = role == AgentRole.WORKER ? "LEGACY_COMPAT" : "READ_ONLY";
        return new AgentProfile(name, role, role.getDescription(), tools, List.of(),
                defaultCommandAllowlist(role), "auto", 1, permissionMode, "PARENT_SUMMARY", "balanced");
    }

    public static AgentProfile worker(String name, List<String> tools, int maxConcurrency) {
        return new AgentProfile(name, AgentRole.WORKER, "", tools, List.of(), List.of(),
                "auto", maxConcurrency, "CUSTOM", "PARENT_SUMMARY", "balanced");
    }

    public boolean allowsTool(String toolName) {
        return AgentToolPolicy.toolAllowed(this, toolName);
    }

    int privilegeScore() {
        if (tools.isEmpty()) {
            return 0;
        }
        if (tools.contains("*")) {
            return 100;
        }
        int score = 0;
        for (String tool : tools) {
            if ("execute_command".equals(tool)) {
                score += 50;
            } else if ("write_file".equals(tool) || "create_project".equals(tool) || "@write".equals(tool)) {
                score += 30;
            } else {
                score += 10;
            }
        }
        return score;
    }

    private static List<String> defaultCommandAllowlist(AgentRole role) {
        if (role != AgentRole.REVIEWER) {
            return List.of();
        }
        return List.of("git status --short", "git diff --stat");
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static List<String> immutableTrimmed(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }
}
