package com.mindcli.capability.tool;

/** Result of executing one tool. Parallelism, locking and timeout remain runtime concerns. */
public record ToolExecution(
        ToolOutput output,
        ToolExecutionStatus status,
        String effectiveArgumentsJson,
        String errorMessage,
        String errorCategory
) {
    public ToolExecution {
        output = output == null ? ToolOutput.text("") : output;
        status = status == null ? ToolExecutionStatus.FAILED : status;
        effectiveArgumentsJson = effectiveArgumentsJson == null ? "" : effectiveArgumentsJson;
        errorMessage = errorMessage == null ? "" : errorMessage;
        errorCategory = errorCategory == null ? "" : errorCategory;
    }

    public static ToolExecution completed(ToolOutput output, String argumentsJson) {
        return new ToolExecution(output, ToolExecutionStatus.COMPLETED, argumentsJson, "", "");
    }

    public static ToolExecution partial(ToolOutput output, String argumentsJson) {
        return new ToolExecution(output, ToolExecutionStatus.PARTIAL, argumentsJson, "", "PARTIAL");
    }

    public static ToolExecution deniedByPolicy(String text, String argumentsJson, String reason) {
        return new ToolExecution(ToolOutput.text(text), ToolExecutionStatus.DENIED_BY_POLICY,
                argumentsJson, reason, "POLICY_DENIED");
    }

    public static ToolExecution deniedByUser(String text, String argumentsJson, String reason) {
        return new ToolExecution(ToolOutput.text(text), ToolExecutionStatus.DENIED_BY_USER,
                argumentsJson, reason, "USER_DENIED");
    }

    public static ToolExecution cancelled(String text, String argumentsJson, String reason) {
        return new ToolExecution(ToolOutput.text(text), ToolExecutionStatus.CANCELLED,
                argumentsJson, reason, "CANCELLED");
    }

    public static ToolExecution failed(ToolOutput output, String argumentsJson,
                                       String reason, String category) {
        return new ToolExecution(output, ToolExecutionStatus.FAILED,
                argumentsJson, reason, category == null || category.isBlank() ? "TOOL_FAILED" : category);
    }
}
