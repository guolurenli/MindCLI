package com.mindcli.plan;

import java.util.*;

/**
 * 任务节点 - 表示一个可执行的任务单元
 */
public class Task {
    private final String id;
    private final String description;
    private final TaskType type;
    private volatile TaskStatus status;
    private volatile String result;
    private volatile String error;
    private final List<String> dependencies;  // 依赖的其他任务ID
    private final List<String> dependents;    // 依赖此任务的其他任务ID
    private volatile long startTime;
    private volatile long endTime;
    private volatile int maxRetries = 3;
    private volatile int retryCount = 0;
    // 是否为关键路径任务（默认 true，LLM 可在计划 JSON 中指定 critical: false）
    private volatile boolean critical = true;
    private volatile String degradation = "REPLAN";
    private volatile List<String> expectedEvidence = new ArrayList<>();

    public enum TaskType {
        PLANNING,      // 规划任务
        FILE_READ,     // 读取文件
        FILE_WRITE,    // 写入文件
        COMMAND,       // 执行命令
        ANALYSIS,      // 分析结果
        VERIFICATION   // 验证结果
    }

    public enum TaskStatus {
        PENDING,       // 等待执行
        RUNNING,       // 执行中
        COMPLETED,     // 已完成
        FAILED,        // 失败
        SKIPPED        // 跳过
    }

    public Task(String id, String description, TaskType type) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();
    }

    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, type);
        this.dependencies.addAll(dependencies);
    }

    // Getters
    public String getId() { return id; }
    public String getDescription() { return description; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public List<String> getDependencies() { return new ArrayList<>(dependencies); }
    public List<String> getDependents() { return new ArrayList<>(dependents); }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public String getDegradation() { return degradation; }
    public List<String> getExpectedEvidence() { return new ArrayList<>(expectedEvidence); }

    // Setters
    public void setStatus(TaskStatus status) { this.status = status; }
    public void setResult(String result) { this.result = result; }
    public void setError(String error) { this.error = error; }

    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped() {
        this.status = TaskStatus.SKIPPED;
        this.endTime = System.currentTimeMillis();
    }

    /** 重置为 PENDING 状态（用于瞬态错误重试） */
    public void resetToPending() {
        this.status = TaskStatus.PENDING;
    }

    /**
     * 获取执行耗时（毫秒）
     */
    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    // -- 重试相关 --
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getRetryCount() { return retryCount; }
    public void incrementRetry() { retryCount++; }
    public void setDegradation(String degradation) {
        this.degradation = (degradation == null || degradation.isBlank()) ? "REPLAN" : degradation;
    }
    public void setExpectedEvidence(List<String> expectedEvidence) {
        this.expectedEvidence = expectedEvidence == null ? new ArrayList<>() : new ArrayList<>(expectedEvidence);
    }

    /**
     * 判断异常是否可重试（网络超时、限流、连接失败等瞬态错误）。
     */
    public boolean shouldRetry(Exception error) {
        if (retryCount >= maxRetries) return false;
        if (error == null) return false;
        return com.mindcli.llm.LlmRetryPolicy.isRetryable(error);
    }

    // -- 关键路径相关 --
    public boolean isCritical() { return critical; }
    public void setCritical(boolean critical) { this.critical = critical; }

    /**
     * 判断当前任务是否在关键路径上：有下游依赖且存在叶子任务间接依赖本任务。
     */
    public boolean isOnCriticalPath(Map<String, Task> allTasks) {
        if (dependents.isEmpty()) return false;
        Set<String> reachableLeaves = new HashSet<>();
        collectLeafTasks(this.id, allTasks, new HashSet<>(), reachableLeaves);
        return !reachableLeaves.isEmpty();
    }

    private void collectLeafTasks(String fromId, Map<String, Task> allTasks,
                                   Set<String> visited, Set<String> leaves) {
        if (!visited.add(fromId)) return;
        Task task = allTasks.get(fromId);
        if (task == null) return;
        if (task.dependents.isEmpty()) {
            leaves.add(fromId);
            return;
        }
        for (String depId : task.dependents) {
            collectLeafTasks(depId, allTasks, visited, leaves);
        }
    }

    /**
     * 是否可以执行。COMPLETED（正常）或 SKIPPED（降级）均满足依赖；
     * FAILED 不满足，下游需等待重试或重规划。
     */
    public boolean isExecutable(Map<String, Task> allTasks) {
        if (status != TaskStatus.PENDING) return false;
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null) return false;
            if (dep.getStatus() != TaskStatus.COMPLETED
                && dep.getStatus() != TaskStatus.SKIPPED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("Task[%s: %s] (%s)", id, description, status);
    }
}
