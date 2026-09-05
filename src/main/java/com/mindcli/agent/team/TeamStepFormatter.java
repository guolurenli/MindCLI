package com.mindcli.agent.team;

import com.mindcli.agent.plan.DependencyGraph;

import java.util.List;

/** Pure formatting and dependency-diagnostic helpers for Team execution steps. */
final class TeamStepFormatter {
    private TeamStepFormatter() {
    }

    static List<DependencyGraph.BlockedNode<ExecutionStep>> blockedSteps(List<ExecutionStep> steps) {
        return DependencyGraph.of(steps, ExecutionStep::id, ExecutionStep::dependencies).blockedNodes(
                candidate -> candidate.status() == StepStatus.PENDING,
                dependencyId -> getStepStatus(dependencyId, steps).name(),
                state -> "COMPLETED".equals(state) || "SKIPPED".equals(state));
    }

    static String formatBlockedDependencies(DependencyGraph.BlockedNode<ExecutionStep> blocked,
                                             List<String> dependencies,
                                             List<ExecutionStep> steps) {
        if (blocked != null && !blocked.blockingDependencies().isEmpty()) {
            return String.join(", ", blocked.blockingDependencies().stream()
                    .map(dependency -> dependency.dependencyId() + "=" + dependency.state())
                    .toList());
        }
        return String.join(", ", dependencies.stream()
                .map(dependency -> dependency + "=" + getStepStatus(dependency, steps))
                .toList());
    }

    static String summarize(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        for (ExecutionStep step : steps) {
            String dependencies = step.dependencies().isEmpty() ? "无" : String.join(", ", step.dependencies());
            String icon = switch (step.status()) {
                case COMPLETED -> "✅";
                case FAILED -> "❌";
                case SKIPPED -> "⏭️";
                default -> "⏳";
            };
            result.append(String.format("  %s [%s] %s (依赖: %s)%n",
                    icon, step.id(), step.description(), dependencies));
        }
        return result.toString();
    }

    /** Builds the concise final status report; full worker output is streamed during execution. */
    static String finalResult(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean allDone = steps.stream().allMatch(step ->
                step.status() == StepStatus.COMPLETED || step.status() == StepStatus.SKIPPED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);
        boolean hasSkippedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.SKIPPED);

        if (allCompleted) {
            result.append("✅ 多 Agent 协作任务完成！\n\n");
        } else if (allDone && hasSkippedSteps) {
            result.append("⚠️ 多 Agent 协作任务完成（部分步骤已跳过）。\n\n");
        } else if (hasFailedSteps) {
            result.append("⚠️ 多 Agent 协作任务未完全完成，存在失败步骤。\n\n");
        } else {
            result.append("⚠️ 多 Agent 协作任务部分完成，仍有未执行步骤。\n\n");
        }
        result.append("📋 执行总结：\n");
        List<DependencyGraph.BlockedNode<ExecutionStep>> blocked = blockedSteps(steps);
        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            result.append(switch (step.status()) {
                case COMPLETED -> "✅ ";
                case FAILED -> "❌ ";
                case SKIPPED -> "⏭️ ";
                default -> "⏳ ";
            });
            result.append(step.description()).append("\n");
            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 120
                        ? step.result().substring(0, 120) + "..." : step.result();
                result.append("   结果：").append(preview).append("\n");
            } else if (step.status() == StepStatus.PENDING) {
                blocked.stream()
                        .filter(item -> item.node().id().equals(step.id()))
                        .findFirst()
                        .map(item -> formatBlockedDependencies(item, step.dependencies(), steps))
                        .filter(reason -> !reason.isBlank())
                        .ifPresent(reason -> result.append("   阻塞：").append(reason).append("\n"));
            }
        }
        return result.toString();
    }

    private static StepStatus getStepStatus(String stepId, List<ExecutionStep> steps) {
        for (ExecutionStep step : steps) {
            if (step.id().equals(stepId)) {
                return step.status();
            }
        }
        return StepStatus.PENDING;
    }
}
