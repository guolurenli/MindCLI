package com.mindcli.runtime.agent;

import com.mindcli.llm.LlmClient;
import com.mindcli.tool.ToolRegistry;

import java.util.List;

public record ToolOutcome(
        String id,
        String name,
        String argumentsJson,
        ToolOutcomeStatus status,
        String text,
        long elapsedMillis,
        String errorMessage,
        List<LlmClient.ContentPart> imageParts
) {
    private static final String FAILED_PREFIX = "工具执行失败:";

    public ToolOutcome {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        argumentsJson = argumentsJson == null ? "" : argumentsJson;
        status = status == null ? ToolOutcomeStatus.FAILED : status;
        text = text == null ? "" : text;
        errorMessage = errorMessage == null ? "" : errorMessage;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
    }

    public static ToolOutcome fromLegacy(ToolRegistry.ToolExecutionResult result) {
        if (result == null) {
            return new ToolOutcome("", "", "", ToolOutcomeStatus.FAILED,
                    "", 0, "Tool registry returned null result", List.of());
        }
        String text = result.result() == null ? "" : result.result();
        ToolOutcomeStatus status = statusFromLegacy(result, text);
        String errorMessage = errorFromLegacy(status, text);
        return new ToolOutcome(
                result.id(),
                result.name(),
                result.argumentsJson(),
                status,
                text,
                result.elapsedMillis(),
                errorMessage,
                result.imageParts());
    }

    public static ToolOutcome failed(LlmClient.ToolCall toolCall, String message) {
        String error = message == null || message.isBlank() ? "Tool execution failed" : message;
        return new ToolOutcome(
                toolCall == null ? "" : toolCall.id(),
                toolCall == null || toolCall.function() == null ? "" : toolCall.function().name(),
                toolCall == null || toolCall.function() == null ? "" : toolCall.function().arguments(),
                ToolOutcomeStatus.FAILED,
                FAILED_PREFIX + " " + error,
                0,
                error,
                List.of());
    }

    public LlmClient.Message toToolMessage() {
        return LlmClient.Message.tool(id, text);
    }

    public boolean hasImageParts() {
        return imageParts != null && !imageParts.isEmpty();
    }

    private static ToolOutcomeStatus statusFromLegacy(ToolRegistry.ToolExecutionResult result, String text) {
        if (result.timedOut()) {
            return ToolOutcomeStatus.TIMED_OUT;
        }
        if (text.contains("用户取消")) {
            return ToolOutcomeStatus.CANCELLED;
        }
        if (text.startsWith(FAILED_PREFIX)) {
            return ToolOutcomeStatus.FAILED;
        }
        return ToolOutcomeStatus.COMPLETED;
    }

    private static String errorFromLegacy(ToolOutcomeStatus status, String text) {
        if (status == ToolOutcomeStatus.COMPLETED) {
            return "";
        }
        if (status == ToolOutcomeStatus.FAILED && text.startsWith(FAILED_PREFIX)) {
            return text.substring(FAILED_PREFIX.length()).trim();
        }
        return text;
    }
}
