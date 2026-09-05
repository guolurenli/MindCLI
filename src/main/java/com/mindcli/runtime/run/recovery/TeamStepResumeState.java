package com.mindcli.runtime.run.recovery;

import java.util.List;

/** Immutable Team step definition and latest checkpoint. */
public record TeamStepResumeState(
        String id,
        String description,
        String type,
        List<String> dependencies,
        List<String> requiredTools,
        String preferredAgent,
        String riskLevel,
        String status,
        String phase,
        int attempt,
        String result,
        String error,
        List<String> childRunIds) {
    public TeamStepResumeState {
        id = id == null ? "" : id;
        description = description == null ? "" : description;
        type = type == null ? "" : type;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        preferredAgent = preferredAgent == null ? "" : preferredAgent;
        riskLevel = riskLevel == null ? "" : riskLevel;
        status = status == null ? "" : status;
        phase = phase == null ? "" : phase;
        result = result == null ? "" : result;
        error = error == null ? "" : error;
        childRunIds = childRunIds == null ? List.of() : List.copyOf(childRunIds);
    }
}
