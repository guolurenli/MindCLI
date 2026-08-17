package com.mindcli.agent;

import com.mindcli.agent.plan.DependencyGraph;
import com.mindcli.platform.security.WriteScopeRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 团队模式的「调度决策」层（纯逻辑、无状态、零外部依赖）。
 *
 * <p>只吃 {@link List}<{@link ExecutionStep}>，吐出下一波可执行工作
 * （{@link ScheduleWave}）：依赖就绪 -> 指纹去重 -> 读写分区 -> 串行原因。
 * 不碰 Agent 池、LLM、IO。</p>
 */
final class TeamScheduler {

    /**
     * 增量计算「下一波」可执行工作。
     *
     * <p>每次调用基于传入 {@code steps} 的当前状态（含 SKIPPED/FAILED 传播），
     * 返回空波表示无更多可执行工作。与 {@code AgentOrchestrator} 主循环逐轮
     * 重算可执行集的行为一致。</p>
     */
    ScheduleWave nextWave(List<ExecutionStep> steps) {
        List<ExecutionStep> executable = getExecutableSteps(steps);
        if (executable.isEmpty()) {
            return new ScheduleWave(List.of(), List.of(), Map.of());
        }
        List<StepExecutionGroup> groups = collapseExecutableGroups(executable);
        if (groups.isEmpty()) {
            return new ScheduleWave(List.of(), List.of(), Map.of());
        }

        Map<String, String> serialReasons = mutatingSerialReasons(groups);
        List<StepExecutionGroup> readOnly = new ArrayList<>();
        List<StepExecutionGroup> mutating = new ArrayList<>();
        for (StepExecutionGroup group : groups) {
            if (group.mutating()) {
                mutating.add(group);
            } else {
                readOnly.add(group);
            }
        }
        return new ScheduleWave(readOnly, mutating, serialReasons);
    }

    /**
     * 获取当前可执行的步骤（依赖已全部完成）。
     */
    private List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        DependencyGraph<ExecutionStep> graph = DependencyGraph.of(
                steps,
                ExecutionStep::id,
                ExecutionStep::dependencies);
        return graph.readyNodes(
                step -> step.status() == StepStatus.PENDING,
                dependencyId -> {
                    StepStatus status = statusMap.get(dependencyId);
                    // COMPLETED（正常）和 SKIPPED（显式降级）可放行；
                    // FAILED 表示依赖结果不可用，必须阻断下游步骤。
                    return status == StepStatus.COMPLETED || status == StepStatus.SKIPPED;
                });
    }

    private List<StepExecutionGroup> collapseExecutableGroups(List<ExecutionStep> executable) {
        if (executable == null || executable.isEmpty()) {
            return List.of();
        }
        Map<String, List<ExecutionStep>> groups = new LinkedHashMap<>();
        Map<String, Boolean> mutatingByFingerprint = new LinkedHashMap<>();
        for (ExecutionStep step : executable) {
            if (step == null) {
                continue;
            }
            String fingerprint = stepFingerprint(step);
            groups.computeIfAbsent(fingerprint, ignored -> new ArrayList<>()).add(step);
            mutatingByFingerprint.putIfAbsent(fingerprint, TeamStepClassifier.isMutating(step));
        }

        List<StepExecutionGroup> collapsed = new ArrayList<>();
        for (Map.Entry<String, List<ExecutionStep>> entry : groups.entrySet()) {
            List<ExecutionStep> groupSteps = entry.getValue();
            if (groupSteps.isEmpty()) {
                continue;
            }
            ExecutionStep leader = groupSteps.get(0);
            List<ExecutionStep> duplicates = groupSteps.size() <= 1
                    ? List.of()
                    : new ArrayList<>(groupSteps.subList(1, groupSteps.size()));
            collapsed.add(new StepExecutionGroup(
                    entry.getKey(),
                    leader,
                    duplicates,
                    mutatingByFingerprint.getOrDefault(entry.getKey(), false)));
        }
        return List.copyOf(collapsed);
    }

    private Map<String, String> mutatingSerialReasons(List<StepExecutionGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }
        List<StepExecutionGroup> mutatingGroups = groups.stream()
                .filter(StepExecutionGroup::mutating)
                .toList();
        if (mutatingGroups.isEmpty()) {
            return Map.of();
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        for (StepExecutionGroup group : mutatingGroups) {
            List<String> scope = WriteScopeRules.normalizeScopes(group.leader().writeScope());
            if (scope.isEmpty()) {
                reasons.put(group.leader().id(), "写入范围未声明，按顺序执行以避免并发冲突");
                continue;
            }
            for (StepExecutionGroup other : mutatingGroups) {
                if (group == other) {
                    continue;
                }
                List<String> otherScope = WriteScopeRules.normalizeScopes(other.leader().writeScope());
                if (otherScope.isEmpty()) {
                    continue;
                }
                if (WriteScopeRules.overlaps(scope, otherScope)) {
                    reasons.put(group.leader().id(), "写入范围重叠，按顺序执行："
                            + WriteScopeRules.formatScopes(scope) + " 与 " + other.leader().id()
                            + " 的 " + WriteScopeRules.formatScopes(otherScope));
                    break;
                }
            }
        }
        return reasons;
    }

    private String stepFingerprint(ExecutionStep step) {
        if (step == null) {
            return "";
        }
        return String.join("|",
                normalizeFingerprintPart(step.type()),
                normalizeFingerprintPart(step.description()),
                joinSorted(step.requiredTools()),
                joinSorted(step.writeScope()),
                normalizeFingerprintPart(step.preferredAgent()),
                normalizeFingerprintPart(step.riskLevel()),
                joinSorted(step.dependencies()));
    }

    private static String normalizeFingerprintPart(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String joinSorted(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(value -> value.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                .sorted()
                .distinct()
                .collect(Collectors.joining(","));
    }
}
