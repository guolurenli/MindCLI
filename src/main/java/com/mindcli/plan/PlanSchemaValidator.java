package com.mindcli.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PlanSchemaValidator {

    public PlanValidationResult validate(PlanSchema schema) {
        List<PlanIssue> issues = new ArrayList<>();
        if (schema == null) {
            issues.add(new PlanIssue("PLAN_SCHEMA_MISSING", "schema", PlanIssueSeverity.FATAL, "计划结构为空"));
            return PlanValidationResult.invalid(null, issues);
        }

        if (schema.summary() == null || schema.summary().isBlank()) {
            issues.add(new PlanIssue("PLAN_EMPTY_SUMMARY", "summary", PlanIssueSeverity.REPAIRABLE, "计划摘要为空"));
        }
        if (schema.tasks() == null || schema.tasks().isEmpty()) {
            issues.add(new PlanIssue("PLAN_EMPTY_TASKS", "tasks", PlanIssueSeverity.FATAL, "计划任务列表为空"));
            return PlanValidationResult.invalid(schema, issues);
        }

        Set<String> ids = new HashSet<>();
        for (PlanTaskSpec task : schema.tasks()) {
            if (task == null) {
                issues.add(new PlanIssue("PLAN_NULL_TASK", "tasks", PlanIssueSeverity.FATAL, "存在空任务节点"));
                continue;
            }
            if (task.id() == null || task.id().isBlank()) {
                issues.add(new PlanIssue("PLAN_EMPTY_TASK_ID", "tasks.id", PlanIssueSeverity.FATAL, "任务 ID 为空"));
            } else if (!ids.add(task.id())) {
                issues.add(new PlanIssue("PLAN_DUPLICATE_ID", "tasks.id", PlanIssueSeverity.FATAL, "任务 ID 重复: " + task.id()));
            }
            if (task.description() == null || task.description().isBlank()) {
                issues.add(new PlanIssue("PLAN_EMPTY_DESCRIPTION", "tasks.description", PlanIssueSeverity.REPAIRABLE,
                        "任务描述为空"));
            }
            if (task.type() == null) {
                issues.add(new PlanIssue("PLAN_UNKNOWN_TASK_TYPE", "tasks.type", PlanIssueSeverity.FATAL, "任务类型缺失"));
            }
            if (task.dependencies() != null) {
                for (String dep : task.dependencies()) {
                    if (dep == null || dep.isBlank()) {
                        issues.add(new PlanIssue("PLAN_EMPTY_DEPENDENCY", "tasks.dependencies",
                                PlanIssueSeverity.REPAIRABLE, "依赖项为空"));
                        continue;
                    }
                    if (task.id() != null && task.id().equals(dep)) {
                        issues.add(new PlanIssue("PLAN_SELF_DEPENDENCY", "tasks.dependencies",
                                PlanIssueSeverity.FATAL, "任务不能依赖自身: " + task.id()));
                    }
                }
            }
            if (task.maxRetries() < 0) {
                issues.add(new PlanIssue("PLAN_INVALID_RETRY_LIMIT", "tasks.maxRetries",
                        PlanIssueSeverity.REPAIRABLE, "maxRetries 不能小于 0"));
            }
            if (task.degradation() == null || task.degradation().isBlank()) {
                issues.add(new PlanIssue("PLAN_INVALID_DEGRADATION", "tasks.degradation",
                        PlanIssueSeverity.REPAIRABLE, "degradation 为空"));
            } else {
                String normalized = task.degradation().toUpperCase(Locale.ROOT);
                if (!Set.of("REPLAN", "BLOCK", "SKIP").contains(normalized)) {
                    issues.add(new PlanIssue("PLAN_INVALID_DEGRADATION", "tasks.degradation",
                            PlanIssueSeverity.REPAIRABLE, "degradation 非法: " + task.degradation()));
                }
            }
        }

        if (issues.stream().anyMatch(issue -> issue.severity() == PlanIssueSeverity.FATAL)) {
            return PlanValidationResult.invalid(schema, issues);
        }

        Map<String, PlanTaskSpec> taskMap = schema.tasks().stream()
                .filter(task -> task != null && task.id() != null)
                .collect(java.util.stream.Collectors.toMap(PlanTaskSpec::id, task -> task, (a, b) -> a));

        for (PlanTaskSpec task : schema.tasks()) {
            if (task == null || task.dependencies() == null) {
                continue;
            }
            for (String dep : task.dependencies()) {
                if (dep == null || dep.isBlank()) {
                    continue;
                }
                if (!taskMap.containsKey(dep)) {
                    issues.add(new PlanIssue("PLAN_DEPENDENCY_NOT_FOUND", "tasks.dependencies",
                            PlanIssueSeverity.REPAIRABLE, "依赖不存在: " + dep + " -> " + task.id()));
                }
            }
        }

        if (issues.stream().anyMatch(issue -> issue.severity() == PlanIssueSeverity.FATAL)) {
            return PlanValidationResult.invalid(schema, issues);
        }

        if (hasCycle(schema.tasks())) {
            issues.add(new PlanIssue("PLAN_CYCLE_DETECTED", "tasks.dependencies", PlanIssueSeverity.FATAL, "计划存在循环依赖"));
            return PlanValidationResult.invalid(schema, issues);
        }

        return issues.isEmpty() ? PlanValidationResult.valid(schema) : PlanValidationResult.invalid(schema, issues);
    }

    private boolean hasCycle(List<PlanTaskSpec> tasks) {
        Map<String, PlanTaskSpec> taskMap = tasks.stream()
                .filter(task -> task != null && task.id() != null)
                .collect(java.util.stream.Collectors.toMap(PlanTaskSpec::id, task -> task, (a, b) -> a));
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (PlanTaskSpec task : tasks) {
            if (task != null && task.id() != null) {
                if (dfs(task.id(), taskMap, visiting, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean dfs(String id, Map<String, PlanTaskSpec> taskMap, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) {
            return false;
        }
        if (!visiting.add(id)) {
            return true;
        }
        PlanTaskSpec task = taskMap.get(id);
        if (task != null && task.dependencies() != null) {
            for (String dep : task.dependencies()) {
                if (dep == null || dep.isBlank()) {
                    continue;
                }
                if (taskMap.containsKey(dep) && dfs(dep, taskMap, visiting, visited)) {
                    return true;
                }
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }
}
