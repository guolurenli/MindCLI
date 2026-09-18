package com.mindcli.agent.team;

import com.mindcli.runtime.run.dispatch.ToolResourceClassifier;

import java.util.List;

/**
 * 写入型步骤判定（package-private 策略 helper）。
 *
 * <p>判定规则是调度/执行策略，不是 step 数据模型的固有属性，故不放 {@link ExecutionStep}，
 * 而是集中到此处，规则变化只改一个地方。</p>
 */
final class TeamStepClassifier {
    private TeamStepClassifier() {
    }

    static boolean isMutating(ExecutionStep step) {
        if (step == null) {
            return true;
        }
        List<String> requiredTools = step.requiredTools() == null ? List.of() : step.requiredTools();
        if (!requiredTools.isEmpty()) {
            return requiredTools.stream().anyMatch(
                    tool -> !ToolResourceClassifier.isExplicitReadOnlyToolName(tool));
        }
        return "FILE_WRITE".equalsIgnoreCase(step.type())
                || "CREATE_PROJECT".equalsIgnoreCase(step.type())
                || "COMMAND".equalsIgnoreCase(step.type());
    }
}
