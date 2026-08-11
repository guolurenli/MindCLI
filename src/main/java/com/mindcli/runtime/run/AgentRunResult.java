package com.mindcli.runtime.run;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record AgentRunResult(
        String runId,
        AgentMode mode,
        AgentRunStatus status,
        String content,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Map<String, String> metadata
) {
    public AgentRunResult {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        mode = Objects.requireNonNull(mode, "mode");
        status = Objects.requireNonNull(status, "status");
        content = content == null ? "" : content;
        errorMessage = errorMessage == null ? "" : errorMessage;
        startedAt = startedAt == null ? Instant.now() : startedAt;
        finishedAt = finishedAt == null ? Instant.now() : finishedAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentRunResult success(AgentRunContext context, String content) {
        return of(context, AgentRunStatus.SUCCESS, content, "");
    }

    public static AgentRunResult failed(AgentRunContext context, String errorMessage) {
        return of(context, AgentRunStatus.FAILED, "", errorMessage);
    }

    public static AgentRunResult blocked(AgentRunContext context, String errorMessage) {
        return of(context, AgentRunStatus.BLOCKED, "", errorMessage);
    }

    public static AgentRunResult cancelled(AgentRunContext context, String content) {
        return of(context, AgentRunStatus.CANCELLED, content, "");
    }

    public static AgentRunResult budgetExhausted(AgentRunContext context, String errorMessage) {
        return of(context, AgentRunStatus.BUDGET_EXHAUSTED, "", errorMessage);
    }

    public static AgentRunResult of(AgentRunContext context, AgentRunStatus status,
                                    String content, String errorMessage) {
        Objects.requireNonNull(context, "context");
        return new AgentRunResult(
                context.runId(),
                context.mode(),
                status,
                content,
                errorMessage,
                context.startedAt(),
                Instant.now(),
                metadataFrom(context));
    }

    public boolean isSuccess() {
        return status == AgentRunStatus.SUCCESS;
    }

    private static Map<String, String> metadataFrom(AgentRunContext context) {
        Map<String, String> metadata = new LinkedHashMap<>(context.metadata());
        metadata.put("workspace", context.workspace());
        return metadata;
    }
}
