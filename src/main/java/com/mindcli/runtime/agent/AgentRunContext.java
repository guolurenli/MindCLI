package com.mindcli.runtime.agent;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AgentRunContext(
        String runId,
        AgentMode mode,
        String input,
        String workspace,
        Instant startedAt,
        Map<String, String> metadata
) {
    public AgentRunContext {
        runId = normalizeRunId(runId);
        mode = Objects.requireNonNull(mode, "mode");
        input = input == null ? "" : input;
        workspace = normalizeWorkspace(workspace);
        startedAt = startedAt == null ? Instant.now() : startedAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentRunContext create(AgentMode mode, String input, String workspace) {
        return create(mode, input, workspace, Map.of());
    }

    public static AgentRunContext create(AgentMode mode, String input, String workspace,
                                         Map<String, String> metadata) {
        return new AgentRunContext(null, mode, input, workspace, Instant.now(), metadata);
    }

    private static String normalizeRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return "run_" + UUID.randomUUID();
        }
        return runId;
    }

    private static String normalizeWorkspace(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            return System.getProperty("user.dir", "");
        }
        return workspace;
    }
}
