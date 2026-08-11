package com.mindcli.agent.profile;

import java.util.List;

public record AgentTaskRequirements(
        String stepId,
        List<String> requiredTools,
        String preferredAgent,
        String riskLevel
) {
    public AgentTaskRequirements {
        stepId = stepId == null ? "" : stepId.trim();
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        preferredAgent = preferredAgent == null ? "" : preferredAgent.trim();
        riskLevel = riskLevel == null || riskLevel.isBlank() ? "low" : riskLevel.trim().toLowerCase();
    }
}
