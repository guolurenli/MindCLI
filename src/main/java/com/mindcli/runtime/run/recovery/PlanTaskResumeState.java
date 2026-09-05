package com.mindcli.runtime.run.recovery;

import java.util.List;

/** Immutable task definition and latest task-boundary state reconstructed from the run ledger. */
public record PlanTaskResumeState(
        String id,
        String description,
        String type,
        List<String> dependencies,
        boolean critical,
        int maxRetries,
        String degradation,
        List<String> expectedEvidence,
        List<String> requiredTools,
        String preferredAgent,
        String riskLevel,
        String status,
        String result,
        String error,
        int retryCount) {
    public PlanTaskResumeState {
        id = id == null ? "" : id;
        description = description == null ? "" : description;
        type = type == null ? "" : type;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        degradation = degradation == null ? "" : degradation;
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        preferredAgent = preferredAgent == null ? "" : preferredAgent;
        riskLevel = riskLevel == null ? "" : riskLevel;
        status = status == null ? "" : status;
        result = result == null ? "" : result;
        error = error == null ? "" : error;
    }
}
