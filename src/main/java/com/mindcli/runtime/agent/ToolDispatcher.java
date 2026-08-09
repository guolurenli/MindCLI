package com.mindcli.runtime.agent;

import com.mindcli.llm.LlmClient;
import com.mindcli.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;

public final class ToolDispatcher {
    private final ToolBatchExecutor executor;

    public ToolDispatcher(ToolRegistry toolRegistry) {
        this(Objects.requireNonNull(toolRegistry, "toolRegistry")::executeTools);
    }

    ToolDispatcher(ToolBatchExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public List<ToolOutcome> dispatch(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<ToolRegistry.ToolInvocation> invocations = toolCalls.stream()
                .map(ToolDispatcher::toInvocation)
                .toList();
        try {
            List<ToolRegistry.ToolExecutionResult> results = executor.execute(invocations);
            if (results == null) {
                return failedOutcomes(toolCalls, "Tool registry returned null result list");
            }
            return results.stream()
                    .map(ToolOutcome::fromLegacy)
                    .toList();
        } catch (Exception e) {
            return failedOutcomes(toolCalls, errorMessage(e));
        }
    }

    private static ToolRegistry.ToolInvocation toInvocation(LlmClient.ToolCall toolCall) {
        LlmClient.ToolCall.Function function = toolCall.function();
        return new ToolRegistry.ToolInvocation(
                toolCall.id(),
                function == null ? "" : function.name(),
                function == null ? "" : function.arguments());
    }

    private static List<ToolOutcome> failedOutcomes(List<LlmClient.ToolCall> toolCalls, String message) {
        return toolCalls.stream()
                .map(toolCall -> ToolOutcome.failed(toolCall, message))
                .toList();
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
