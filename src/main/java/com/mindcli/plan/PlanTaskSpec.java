package com.mindcli.plan;

import java.util.List;

public record PlanTaskSpec(
        String id,
        String description,
        Task.TaskType type,
        List<String> dependencies,
        boolean critical,
        int maxRetries,
        String degradation,
        List<String> expectedEvidence
) {
}
