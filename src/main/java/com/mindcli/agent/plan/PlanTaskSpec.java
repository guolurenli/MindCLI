package com.mindcli.agent.plan;

import java.util.List;

public record PlanTaskSpec(
        String id,
        String description,
        Task.TaskType type,
        List<String> dependencies,
        boolean critical,
        int maxRetries,
        String degradation,
        List<String> expectedEvidence,
        List<String> requiredTools,
        String preferredAgent,
        String riskLevel
) {
    public PlanTaskSpec(String id,
                        String description,
                        Task.TaskType type,
                        List<String> dependencies,
                        boolean critical,
                        int maxRetries,
                        String degradation,
                        List<String> expectedEvidence) {
        this(id, description, type, dependencies, critical, maxRetries, degradation,
                expectedEvidence, null, "", "");
    }

    public PlanTaskSpec {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        requiredTools = requiredTools == null || requiredTools.isEmpty()
                ? inferRequiredTools(type)
                : List.copyOf(requiredTools);
        preferredAgent = preferredAgent == null ? "" : preferredAgent.trim();
        riskLevel = riskLevel == null || riskLevel.isBlank() ? inferRiskLevel(type) : riskLevel.trim().toLowerCase();
    }

    private static List<String> inferRequiredTools(Task.TaskType type) {
        if (type == null) {
            return List.of();
        }
        return switch (type) {
            case FILE_READ -> List.of("read_file");
            case FILE_WRITE -> List.of("read_file", "write_file");
            case COMMAND -> List.of("execute_command");
            case VERIFICATION -> List.of("read_file", "grep_code", "execute_command");
            case PLANNING, ANALYSIS -> List.of();
        };
    }

    private static String inferRiskLevel(Task.TaskType type) {
        if (type == null) {
            return "low";
        }
        return switch (type) {
            case FILE_WRITE, COMMAND -> "medium";
            default -> "low";
        };
    }
}
