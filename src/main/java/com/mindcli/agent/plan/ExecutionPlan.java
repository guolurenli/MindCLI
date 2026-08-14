package com.mindcli.agent.plan;

import java.util.*;

/**
 * 执行计划 - 包含一组有依赖关系的任务
 */
public class ExecutionPlan {
    private final String id;
    private final String goal;                    // 计划目标
    private final Map<String, Task> tasks;        // 所有任务
    private final List<String> executionOrder;    // 执行顺序（拓扑排序后）
    private PlanStatus status;
    private String summary;                       // 计划摘要
    private long startTime;
    private long endTime;

    public enum PlanStatus {
        CREATED,      // 刚创建
        RUNNING,      // 执行中
        COMPLETED,    // 全部完成
        FAILED,       // 有任务失败
        CANCELLED     // 被取消
    }

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
        this.tasks = new LinkedHashMap<>();  // 保持插入顺序
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
    }

    // Getters
    public String getId() { return id; }
    public String getGoal() { return goal; }
    public PlanStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    public void setSummary(String summary) { this.summary = summary; }
    public void setStatus(PlanStatus status) { this.status = status; }

    /**
     * 添加任务
     */
    public void addTask(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        if (task.getId() == null || task.getId().isBlank()) {
            throw new IllegalArgumentException("task id 不能为空");
        }
        if (tasks.containsKey(task.getId())) {
            throw new IllegalArgumentException("task id 重复: " + task.getId());
        }
        tasks.put(task.getId(), task);
        // 更新依赖关系
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                dep.addDependent(task.getId());
            }
        }
    }

    /**
     * 获取任务
     */
    public Task getTask(String id) {
        return tasks.get(id);
    }

    /**
     * 获取所有任务
     */
    public Collection<Task> getAllTasks() {
        return tasks.values();
    }

    /**
     * 获取任务 Map（用于 isOnCriticalPath 等需要按 ID 查询的场景）。
     */
    public Map<String, Task> getAllTasksMap() {
        return new LinkedHashMap<>(tasks);
    }

    /**
     * 获取根任务（没有依赖的任务）
     */
    public List<Task> getRootTasks() {
        return tasks.values().stream()
                .filter(t -> t.getDependencies().isEmpty())
                .toList();
    }

    /**
     * 获取可执行的任务（依赖都已完成）
     */
    public List<Task> getExecutableTasks() {
        return dependencyGraph().readyNodes(
                task -> task.getStatus() == Task.TaskStatus.PENDING,
                dependencyId -> {
                    Task dependency = tasks.get(dependencyId);
                    return dependency != null
                            && (dependency.getStatus() == Task.TaskStatus.COMPLETED
                            || dependency.getStatus() == Task.TaskStatus.SKIPPED);
                });
    }

    public List<DependencyGraph.BlockedNode<Task>> getBlockedTasks() {
        DependencyGraph<Task> graph = dependencyGraph();
        return graph.blockedNodes(
                task -> task.getStatus() == Task.TaskStatus.PENDING,
                dependencyId -> {
                    Task dependency = tasks.get(dependencyId);
                    if (dependency == null) {
                        return "MISSING";
                    }
                    return dependency.getStatus().name();
                },
                state -> "COMPLETED".equals(state) || "SKIPPED".equals(state));
    }

    /**
     * 计算拓扑排序执行顺序
     */
    public boolean computeExecutionOrder() {
        executionOrder.clear();
        DependencyGraph<Task> graph = dependencyGraph();
        if (!graph.isValid()) {
            return false;
        }
        graph.topologicalOrder().stream()
                .map(Task::getId)
                .forEach(executionOrder::add);
        return true;
    }

    /**
     * 获取执行顺序
     */
    public List<String> getExecutionOrder() {
        if (executionOrder.isEmpty()) {
            computeExecutionOrder();
        }
        return new ArrayList<>(executionOrder);
    }

    /**
     * 获取执行进度
     */
    public double getProgress() {
        if (tasks.isEmpty()) return 1.0;
        long completed = tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .count();
        return (double) completed / tasks.size();
    }

    /**
     * 是否全部完成
     */
    public boolean isAllCompleted() {
        return tasks.values().stream()
                .allMatch(t -> t.getStatus() == Task.TaskStatus.COMPLETED
                        || t.getStatus() == Task.TaskStatus.SKIPPED);
    }

    /**
     * 是否有失败任务
     */
    public boolean hasFailed() {
        return tasks.values().stream()
                .anyMatch(t -> t.getStatus() == Task.TaskStatus.FAILED);
    }

    /**
     * 标记开始执行
     */
    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * 标记完成
     */
    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 标记失败
     */
    public void markFailed() {
        this.status = PlanStatus.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 获取总耗时
     */
    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    /**
     * 可视化计划
     */
    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║  执行计划: %-46s║%n", goal.length() > 46 ? goal.substring(0, 43) + "..." : goal));
        sb.append("╠══════════════════════════════════════════════════════════╣\n");

        List<String> order = getExecutionOrder();
        for (int i = 0; i < order.size(); i++) {
            String taskId = order.get(i);
            Task task = tasks.get(taskId);
            String statusIcon = getStatusIcon(task.getStatus());
            String deps = task.getDependencies().isEmpty() ? "无" :
                    String.join(",", task.getDependencies());

            sb.append(String.format("║  %d. %s %-20s ", i + 1, statusIcon, task.getId()));
            sb.append(String.format("[%-10s] 依赖: %-15s║%n",
                    task.getType(), deps));
            String desc = task.getDescription().length() > 50
                    ? task.getDescription().substring(0, 47) + "..."
                    : task.getDescription();
            sb.append(String.format("║     %-53s║%n", desc));
        }

        sb.append("╚══════════════════════════════════════════════════════════╝\n");
        sb.append(String.format("   进度: %.0f%% | 状态: %s%n",
                getProgress() * 100, status));

        return sb.toString();
    }

    /**
     * 默认折叠展示，避免完整 DAG 占满终端。
     */
    public String summarize() {
        List<List<Task>> batches = getExecutionBatches();
        List<Task> readyTasks = getExecutableTasks();
        StringBuilder sb = new StringBuilder();
        sb.append("📋 计划摘要\n");
        sb.append("   - 目标: ").append(compactGoal(goal, 48)).append('\n');
        sb.append("   - 任务数: ").append(tasks.size())
                .append(" | 并行批次: ").append(batches.size())
                .append(" | 当前可执行: ").append(readyTasks.size())
                .append(" | 状态: ").append(status).append('\n');

        if (!batches.isEmpty()) {
            sb.append("   - 首批执行: ").append(formatTaskList(batches.get(0), 5)).append('\n');
            if (batches.size() > 1) {
                sb.append("   - 最终收敛: ")
                        .append(formatTaskList(batches.get(batches.size() - 1), 5))
                        .append('\n');
            }
        }

        return sb.toString();
    }

    public List<List<Task>> getExecutionBatches() {
        return dependencyGraph().executionBatches();
    }

    private DependencyGraph<Task> dependencyGraph() {
        return DependencyGraph.of(tasks.values(), Task::getId, Task::getDependencies);
    }

    private String compactGoal(String rawGoal, int maxLength) {
        String singleLineGoal = rawGoal
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .replaceAll(" {2,}", " ");
        if (singleLineGoal.length() <= maxLength) {
            return singleLineGoal;
        }
        return singleLineGoal.substring(0, maxLength - 3) + "...";
    }

    private String formatTaskList(List<Task> batch, int limit) {
        if (batch.isEmpty()) {
            return "无";
        }

        List<String> taskIds = batch.stream()
                .map(Task::getId)
                .toList();

        if (taskIds.size() <= limit) {
            return String.join(", ", taskIds);
        }

        return String.join(", ", taskIds.subList(0, limit)) + " 等 " + taskIds.size() + " 个任务";
    }

    private String getStatusIcon(Task.TaskStatus status) {
        return switch (status) {
            case PENDING -> "⏳";
            case RUNNING -> "▶️";
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭️";
        };
    }

    /**
     * 将子树计划中的新任务合并到当前计划。
     * 已完成的 COMPLETED 任务保留不动，子树中的新任务替换原有同区域任务。
     */
    public void mergeSubtree(ExecutionPlan subtree) {
        if (subtree == null) {
            return;
        }
        // 移除当前计划中 PENDING/FAILED 状态的任务（将被子树替换）
        List<String> toRemove = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (t.getStatus() == Task.TaskStatus.PENDING
                || t.getStatus() == Task.TaskStatus.FAILED) {
                toRemove.add(t.getId());
            }
        }
        for (String id : toRemove) {
            tasks.remove(id);
        }

        // 将子树中的任务重编号并加入
        int nextIdx = tasks.size() + 1;
        Map<String, String> idRemap = new HashMap<>();
        for (Task t : subtree.getAllTasks()) {
            String newId = "task_" + nextIdx++;
            idRemap.put(t.getId(), newId);
            Task newTask = new Task(newId, t.getDescription(), t.getType());
            tasks.put(newId, newTask);
        }

        // 重建子树的依赖关系
        for (Task t : subtree.getAllTasks()) {
            Task newTask = tasks.get(idRemap.get(t.getId()));
            if (newTask == null) continue;
            for (String depId : t.getDependencies()) {
                String newDepId = idRemap.get(depId);
                if (newDepId != null && tasks.containsKey(newDepId)) {
                    newTask.addDependency(newDepId);
                    Task dep = tasks.get(newDepId);
                    if (dep != null) dep.addDependent(newTask.getId());
                }
            }
        }

        // 重新计算执行顺序
        computeExecutionOrder();
    }

    @Override
    public String toString() {
        return String.format("ExecutionPlan[%s: %s] (%d tasks, %s)",
                id, goal, tasks.size(), status);
    }
}
