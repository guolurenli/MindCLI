package com.mindcli.agent;

import java.util.List;

/**
 * 团队模式中的单个执行步骤（package-private 内部执行模型）。
 *
 * <p>由 {@link AgentOrchestrator} 解析计划生成，供调度（{@link TeamScheduler}）
 * 与执行（{@link AgentOrchestrator}）共享。写入型判定等调度策略不放这里，
 * 见 {@link TeamStepClassifier}。</p>
 */
record ExecutionStep(String id, String description, String type,
                     List<String> dependencies, List<String> requiredTools,
                     String preferredAgent, String riskLevel,
                     List<String> writeScope,
                     String result, StepStatus status) {
    static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
        return pending(id, description, type, dependencies, List.of(), "", "", List.of());
    }

    static ExecutionStep pending(String id, String description, String type, List<String> dependencies,
                                 List<String> requiredTools, String preferredAgent, String riskLevel) {
        return pending(id, description, type, dependencies, requiredTools, preferredAgent, riskLevel, List.of());
    }

    static ExecutionStep pending(String id, String description, String type, List<String> dependencies,
                                 List<String> requiredTools, String preferredAgent, String riskLevel,
                                 List<String> writeScope) {
        return new ExecutionStep(id, description, type, dependencies,
                requiredTools == null ? List.of() : List.copyOf(requiredTools),
                preferredAgent == null ? "" : preferredAgent,
                riskLevel == null || riskLevel.isBlank() ? "low" : riskLevel,
                writeScope == null ? List.of() : List.copyOf(writeScope),
                null, StepStatus.PENDING);
    }

    ExecutionStep withResult(String result) {
        return new ExecutionStep(id, description, type, dependencies, requiredTools,
                preferredAgent, riskLevel, writeScope, result, StepStatus.COMPLETED);
    }

    ExecutionStep withFailed(String result) {
        return new ExecutionStep(id, description, type, dependencies, requiredTools,
                preferredAgent, riskLevel, writeScope, result, StepStatus.FAILED);
    }

    ExecutionStep withSkipped(String reason) {
        return new ExecutionStep(id, description, type, dependencies,
                requiredTools, preferredAgent, riskLevel, writeScope,
                reason != null ? reason : "步骤被跳过", StepStatus.SKIPPED);
    }

    ExecutionStep started() {
        return new ExecutionStep(id, description, type, dependencies, requiredTools,
                preferredAgent, riskLevel, writeScope, result, StepStatus.RUNNING);
    }
}
