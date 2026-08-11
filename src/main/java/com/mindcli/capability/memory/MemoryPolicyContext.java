package com.mindcli.capability.memory;

public record MemoryPolicyContext(
        String projectKey,
        String scope,
        boolean externalContextUsed,
        boolean autoExtractEnabled,
        String source,
        String originRunId,
        String agentRole
) {
    public MemoryPolicyContext {
        projectKey = projectKey == null ? "" : projectKey;
        scope = scope == null || scope.isBlank() ? "project" : scope.trim().toLowerCase();
        source = source == null ? "" : source;
        originRunId = originRunId == null ? "" : originRunId;
        agentRole = agentRole == null ? "" : agentRole;
    }

    public static MemoryPolicyContext manual(String projectKey, String scope) {
        return new MemoryPolicyContext(projectKey, scope, false, false, "manual", "", "");
    }

    public static MemoryPolicyContext extracted(String projectKey, String scope) {
        return new MemoryPolicyContext(projectKey, scope, false, true, "extractor", "", "");
    }
}
