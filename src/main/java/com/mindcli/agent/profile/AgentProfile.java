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
        String contextMode,
        String developerInstructions,
        String approvalPolicy
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
        developerInstructions = developerInstructions == null ? "" : developerInstructions.trim();
        approvalPolicy = blankToDefault(approvalPolicy, "on-request").toLowerCase(Locale.ROOT);
    }

    public static AgentProfile builtinExplorer(String name) {
        return new AgentProfile(name, AgentRole.EXPLORER, AgentRole.EXPLORER.getDescription(),
                List.of("@read"), List.of(), List.of(),
                "auto", 1, "READ_ONLY", "PARENT_SUMMARY", "balanced", "", "on-request");
    }

    public static AgentProfile builtinWorker(String name) {
        return new AgentProfile(name, AgentRole.WORKER, AgentRole.WORKER.getDescription(),
                List.of("*"), List.of(), List.of(),
                "auto", 1, "LEGACY_COMPAT", "PARENT_SUMMARY", "balanced", "", "on-request");
    }

    public static AgentProfile worker(String name, List<String> tools, int maxConcurrency) {
        return new AgentProfile(name, AgentRole.WORKER, "", tools, List.of(), List.of(),
                "auto", maxConcurrency, "CUSTOM", "PARENT_SUMMARY", "balanced", "", "on-request");
    }

    /**
     * 自定义子代理工厂：将 Codex 的 sandbox_mode 映射为内部的 tools + permissionMode。
     */
    public static AgentProfile custom(String name, String description, String developerInstructions,
                                      String sandboxMode, String approvalPolicy, String model) {
        String mode = sandboxMode == null ? "workspace-write" : sandboxMode.trim().toLowerCase(Locale.ROOT);
        List<String> tools;
        String permissionMode;
        switch (mode) {
            case "read-only" -> {
                tools = List.of("@read");
                permissionMode = "READ_ONLY";
            }
            case "danger-full-access" -> {
                tools = List.of("*");
                permissionMode = "DANGER_FULL_ACCESS";
            }
            default -> { // workspace-write
                tools = List.of("*");
                permissionMode = "LEGACY_COMPAT";
            }
        }
        return new AgentProfile(name, AgentRole.CUSTOM, description, tools, List.of(), List.of(),
                model, 1, permissionMode, "PARENT_SUMMARY", "balanced",
                developerInstructions, approvalPolicy);
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
