package com.mindcli.agent;

import java.util.List;
import java.util.Locale;

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
            return false;
        }
        List<String> requiredTools = step.requiredTools() == null ? List.of() : step.requiredTools();
        if (requiredTools.stream().anyMatch(tool -> "write_file".equalsIgnoreCase(tool)
                || "create_project".equalsIgnoreCase(tool))) {
            return true;
        }
        boolean usesCommand = requiredTools.stream().anyMatch(tool -> "execute_command".equalsIgnoreCase(tool));
        if (usesCommand) {
            String riskLevel = step.riskLevel() == null ? "" : step.riskLevel().trim().toLowerCase(Locale.ROOT);
            return !"low".equals(riskLevel);
        }
        return false;
    }
}
