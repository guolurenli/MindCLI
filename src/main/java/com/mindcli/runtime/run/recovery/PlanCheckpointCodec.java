package com.mindcli.runtime.run.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindcli.platform.serialization.JsonSupport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical codec for persisted Plan definitions. */
public final class PlanCheckpointCodec {
    private static final Set<String> TASK_TYPES = Set.of(
            "PLANNING", "FILE_READ", "FILE_WRITE", "COMMAND", "ANALYSIS", "VERIFICATION");
    private static final Set<String> TASK_STATUSES = Set.of(
            "PENDING", "RUNNING", "COMPLETED", "FAILED", "SKIPPED");

    public String encode(PlanResumeState state) {
        String validationError = validate(state);
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }
        try {
            ObjectNode root = JsonSupport.mapper().createObjectNode();
            root.put("planVersion", state.planVersion());
            root.put("planId", state.planId());
            root.put("goal", state.goal());
            root.put("summary", state.summary());
            ArrayNode tasks = root.putArray("tasks");
            for (PlanTaskResumeState task : state.tasks()) {
                ObjectNode node = tasks.addObject();
                node.put("id", task.id());
                node.put("description", task.description());
                node.put("type", task.type());
                writeStrings(node.putArray("dependencies"), task.dependencies());
                node.put("critical", task.critical());
                node.put("maxRetries", task.maxRetries());
                node.put("degradation", task.degradation());
                writeStrings(node.putArray("expectedEvidence"), task.expectedEvidence());
                writeStrings(node.putArray("requiredTools"), task.requiredTools());
                node.put("preferredAgent", task.preferredAgent());
                node.put("riskLevel", task.riskLevel());
                node.put("status", task.status());
                node.put("result", task.result());
                node.put("error", task.error());
                node.put("retryCount", task.retryCount());
            }
            return JsonSupport.mapper().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("Plan checkpoint 编码失败: " + message(e), e);
        }
    }

    public PlanResumeState decode(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return PlanResumeState.unavailable("Plan checkpoint 损坏: planJson 为空");
        }
        try {
            JsonNode root = JsonSupport.mapper().readTree(planJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("根节点必须是 object");
            }
            int version = requiredNonNegativeInt(root, "planVersion");
            if (version < 1) {
                throw new IllegalArgumentException("planVersion 必须大于 0");
            }
            String planId = requiredNonBlankText(root, "planId");
            String goal = requiredNonBlankText(root, "goal");
            String summary = requiredText(root, "summary");
            JsonNode taskNodes = root.get("tasks");
            if (taskNodes == null || !taskNodes.isArray()) {
                throw new IllegalArgumentException("tasks 必须是 array");
            }
            List<PlanTaskResumeState> tasks = new ArrayList<>();
            for (JsonNode node : taskNodes) {
                tasks.add(decodeTask(node));
            }
            PlanResumeState state = new PlanResumeState(
                    true, version, planId, goal, summary, tasks, "");
            String validationError = validate(state);
            if (validationError != null) {
                throw new IllegalArgumentException(validationError);
            }
            return state;
        } catch (Exception e) {
            return PlanResumeState.unavailable("Plan checkpoint 损坏: " + message(e));
        }
    }

    private static PlanTaskResumeState decodeTask(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("task 必须是 object");
        }
        JsonNode critical = node.get("critical");
        if (critical == null || !critical.isBoolean()) {
            throw new IllegalArgumentException("task.critical 必须是 boolean");
        }
        return new PlanTaskResumeState(
                requiredNonBlankText(node, "id"),
                requiredNonBlankText(node, "description"),
                requiredNonBlankText(node, "type"),
                requiredStringList(node, "dependencies"),
                critical.booleanValue(),
                requiredNonNegativeInt(node, "maxRetries"),
                requiredNonBlankText(node, "degradation"),
                requiredStringList(node, "expectedEvidence"),
                requiredStringList(node, "requiredTools"),
                requiredText(node, "preferredAgent"),
                requiredNonBlankText(node, "riskLevel"),
                requiredNonBlankText(node, "status"),
                requiredText(node, "result"),
                requiredText(node, "error"),
                requiredNonNegativeInt(node, "retryCount"));
    }

    private static String validate(PlanResumeState state) {
        if (state == null || !state.available()) {
            return "不可编码不可用的 Plan checkpoint";
        }
        if (state.planVersion() < 1) return "planVersion 必须大于 0";
        if (state.planId().isBlank()) return "planId 不能为空";
        if (state.goal().isBlank()) return "goal 不能为空";

        Map<String, PlanTaskResumeState> byId = new LinkedHashMap<>();
        for (PlanTaskResumeState task : state.tasks()) {
            if (task == null || task.id().isBlank()) return "task id 不能为空";
            if (byId.put(task.id(), task) != null) return "task id 重复: " + task.id();
            if (task.description().isBlank()) return "task description 不能为空: " + task.id();
            if (!TASK_TYPES.contains(task.type())) return "未知 task type: " + task.type();
            if (!TASK_STATUSES.contains(task.status())) return "未知 task status: " + task.status();
            if (task.maxRetries() < 0 || task.retryCount() < 0) return "task retry 不能为负数: " + task.id();
            if (task.degradation().isBlank()) return "task degradation 不能为空: " + task.id();
            if (task.riskLevel().isBlank()) return "task riskLevel 不能为空: " + task.id();
            if (hasBlank(task.dependencies()) || hasBlank(task.expectedEvidence()) || hasBlank(task.requiredTools())) {
                return "task list 字段不能包含空值: " + task.id();
            }
        }
        for (PlanTaskResumeState task : state.tasks()) {
            for (String dependency : task.dependencies()) {
                if (!byId.containsKey(dependency)) return "task 依赖不存在: " + dependency;
                if (dependency.equals(task.id())) return "Plan DAG 存在环: " + task.id();
            }
        }
        if (containsCycle(byId)) return "Plan DAG 存在环";
        return null;
    }

    private static boolean containsCycle(Map<String, PlanTaskResumeState> tasks) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (PlanTaskResumeState task : tasks.values()) {
            indegree.put(task.id(), task.dependencies().size());
            for (String dependency : task.dependencies()) {
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(task.id());
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, count) -> {
            if (count == 0) ready.add(id);
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited++;
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                int remaining = indegree.computeIfPresent(dependent, (ignored, count) -> count - 1);
                if (remaining == 0) ready.addLast(dependent);
            }
        }
        return visited != tasks.size();
    }

    private static boolean hasBlank(List<String> values) {
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || !unique.add(value)) return true;
        }
        return false;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " 必须是 string");
        }
        return value.textValue();
    }

    private static String requiredNonBlankText(JsonNode node, String field) {
        String value = requiredText(node, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value;
    }

    private static int requiredNonNegativeInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalArgumentException(field + " 必须是非负整数");
        }
        return value.intValue();
    }

    private static List<String> requiredStringList(JsonNode node, String field) {
        JsonNode values = node.get(field);
        if (values == null || !values.isArray()) {
            throw new IllegalArgumentException(field + " 必须是 string array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw new IllegalArgumentException(field + " 必须是 string array");
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static void writeStrings(ArrayNode target, List<String> values) {
        for (String value : values) target.add(value);
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }
}
