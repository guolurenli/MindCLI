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
    private static final String NETWORK_POLICY_PREFIX = "❌ 网络访问被拒绝:";
    private static final String WECHAT_POLICY_PREFIX = "微信通道策略拒绝:";
    private static final String HITL_REJECT_PREFIX = "[HITL] 操作已被拒绝";
    private static final String HITL_SKIP_PREFIX = "[HITL] 操作已被跳过";

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

    public static ToolOutcome fromLegacy(ToolRegistry.ToolExecutionResult result) {
        if (result == null) {
            return new ToolOutcome("", "", "", ToolOutcomeStatus.FAILED,
                    "", 0, "Tool registry returned null result", "TOOL_REGISTRY_NULL", List.of(), Map.of());
        }
        String text = result.result() == null ? "" : result.result();
        ToolOutcomeStatus status = statusFromLegacy(result, text);
        String errorMessage = errorFromLegacy(status, text);
        Map<String, String> metadata = metadataFromLegacy(status, errorMessage);
        return new ToolOutcome(
                result.id(),
                result.name(),
                result.argumentsJson(),
                status,
                text,
                result.elapsedMillis(),
                errorMessage,
                errorCategory(status),
                result.imageParts(),
                metadata);
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

    private static ToolOutcomeStatus statusFromLegacy(ToolRegistry.ToolExecutionResult result, String text) {
        if (result.timedOut()) {
            return ToolOutcomeStatus.TIMED_OUT;
        }
        if (text.contains("用户取消")) {
            return ToolOutcomeStatus.CANCELLED;
        }
        if (startsWithPolicyPrefix(text)) {
            return ToolOutcomeStatus.DENIED_BY_POLICY;
        }
        if (text.startsWith(HITL_REJECT_PREFIX) || text.startsWith(HITL_SKIP_PREFIX)) {
            return ToolOutcomeStatus.DENIED_BY_USER;
        }
        if (text.startsWith(FAILED_PREFIX)) {
            return ToolOutcomeStatus.FAILED;
        }
        if (text.contains("partial: true")) {
            return ToolOutcomeStatus.PARTIAL;
        }
        return ToolOutcomeStatus.COMPLETED;
    }

    private static String errorFromLegacy(ToolOutcomeStatus status, String text) {
        if (status == ToolOutcomeStatus.COMPLETED || status == ToolOutcomeStatus.PARTIAL) {
            return "";
        }
        if (status == ToolOutcomeStatus.FAILED && text.startsWith(FAILED_PREFIX)) {
            return text.substring(FAILED_PREFIX.length()).trim();
        }
        if (status == ToolOutcomeStatus.DENIED_BY_POLICY) {
            String prefix = matchingPolicyPrefix(text);
            if (!prefix.isEmpty()) {
                return text.substring(prefix.length()).trim();
            }
        }
        if (status == ToolOutcomeStatus.DENIED_BY_USER) {
            if (text.startsWith(HITL_SKIP_PREFIX)) {
                return "用户跳过";
            }
            int colon = Math.max(text.lastIndexOf('：'), text.lastIndexOf(':'));
            if (colon >= 0 && colon + 1 < text.length()) {
                return text.substring(colon + 1).trim();
            }
        }
        return text;
    }

    private static boolean startsWithPolicyPrefix(String text) {
        return !matchingPolicyPrefix(text).isEmpty();
    }

    private static String matchingPolicyPrefix(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.startsWith(POLICY_PREFIX)) {
            return POLICY_PREFIX;
        }
        if (text.startsWith(NETWORK_POLICY_PREFIX)) {
            return NETWORK_POLICY_PREFIX;
        }
        if (text.startsWith(WECHAT_POLICY_PREFIX)) {
            return WECHAT_POLICY_PREFIX;
        }
        return "";
    }

    private static Map<String, String> metadataFromLegacy(ToolOutcomeStatus status, String errorMessage) {
        if (status != ToolOutcomeStatus.DENIED_BY_POLICY && status != ToolOutcomeStatus.DENIED_BY_USER) {
            return Map.of();
        }
        return Map.of("deniedReason", errorMessage);
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
