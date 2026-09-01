package com.mindcli.runtime.run;

import com.mindcli.platform.llm.LlmClient;
import com.mindcli.capability.tool.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ToolOutcome(
        String id,
        String name,
        String argumentsJson,
        ToolOutcomeStatus status,
        String text,
        long elapsedMillis,
        String errorMessage,
        String errorCategory,
        List<LlmClient.ContentPart> imageParts,
        Map<String, String> metadata
) {
    private static final String FAILED_PREFIX = "工具执行失败:";
    private static final String POLICY_PREFIX = "🛡️ 策略拒绝:";

    public ToolOutcome(String id,
                       String name,
                       String argumentsJson,
                       ToolOutcomeStatus status,
                       String text,
                       long elapsedMillis,
                       String errorMessage,
                       List<LlmClient.ContentPart> imageParts) {
        this(id, name, argumentsJson, status, text, elapsedMillis, errorMessage, "", imageParts, Map.of());
    }

    public ToolOutcome {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        argumentsJson = argumentsJson == null ? "" : argumentsJson;
        status = status == null ? ToolOutcomeStatus.FAILED : status;
        text = text == null ? "" : text;
        errorMessage = errorMessage == null ? "" : errorMessage;
        errorCategory = errorCategory == null ? "" : errorCategory;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
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
                errorCategory(ToolOutcomeStatus.FAILED),
                List.of(),
                Map.of());
    }

    public static ToolOutcome failed(ToolRegistry.ToolInvocation invocation, String message,
                                     Map<String, String> metadata) {
        String error = message == null || message.isBlank() ? "Tool execution failed" : message;
        return new ToolOutcome(
                invocation == null ? "" : invocation.id(),
                invocation == null ? "" : invocation.name(),
                invocation == null ? "" : invocation.argumentsJson(),
                ToolOutcomeStatus.FAILED,
                FAILED_PREFIX + " " + error,
                0,
                error,
                errorCategory(ToolOutcomeStatus.FAILED),
                List.of(),
                metadata);
    }

    public static ToolOutcome denied(ToolRegistry.ToolInvocation invocation, ToolOutcomeStatus status,
                                     String reason, Map<String, String> metadata) {
        ToolOutcomeStatus effectiveStatus = status == ToolOutcomeStatus.DENIED_BY_USER
                ? ToolOutcomeStatus.DENIED_BY_USER
                : ToolOutcomeStatus.DENIED_BY_POLICY;
        String effectiveReason = reason == null || reason.isBlank() ? "Tool use denied" : reason;
        String text = effectiveStatus == ToolOutcomeStatus.DENIED_BY_USER
                ? "[HITL] 操作已被拒绝：" + effectiveReason
                : POLICY_PREFIX + " " + effectiveReason;
        Map<String, String> merged = new LinkedHashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        merged.put("deniedReason", effectiveReason);
        return new ToolOutcome(
                invocation == null ? "" : invocation.id(),
                invocation == null ? "" : invocation.name(),
                invocation == null ? "" : invocation.argumentsJson(),
                effectiveStatus,
                text,
                0,
                effectiveReason,
                errorCategory(effectiveStatus),
                List.of(),
                merged);
    }

    public LlmClient.Message toToolMessage() {
        return LlmClient.Message.tool(id, text);
    }

    public boolean hasImageParts() {
        return imageParts != null && !imageParts.isEmpty();
    }

    public ToolOutcome withMetadata(Map<String, String> extraMetadata) {
        if (extraMetadata == null || extraMetadata.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(metadata);
        merged.putAll(extraMetadata);
        return new ToolOutcome(id, name, argumentsJson, status, text, elapsedMillis,
                errorMessage, errorCategory, imageParts, merged);
    }

    public ToolOutcome withArgumentsJson(String effectiveArgumentsJson) {
        return new ToolOutcome(id, name, effectiveArgumentsJson, status, text, elapsedMillis,
                errorMessage, errorCategory, imageParts, metadata);
    }

    private static String errorCategory(ToolOutcomeStatus status) {
        return switch (status) {
            case DENIED_BY_POLICY -> "POLICY_DENIED";
            case DENIED_BY_USER -> "USER_DENIED";
            case TIMED_OUT -> "TIMEOUT";
            case CANCELLED -> "CANCELLED";
            case FAILED -> "TOOL_FAILED";
            case PARTIAL -> "PARTIAL";
            case COMPLETED -> "";
        };
    }
}
