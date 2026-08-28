package com.mindcli.agent;

import java.util.List;

/**
 * 调度器单次计算产出的一波工作（package-private 内部模型）。
 *
 * <p>{@code readOnly} 分组可并行执行；{@code mutating} 分组是否 worktree 并行，
 * 由 {@link AgentOrchestrator} 决定。</p>
 */
record ScheduleWave(
        List<StepExecutionGroup> readOnly,
        List<StepExecutionGroup> mutating
) {
    boolean hasWork() {
        return !readOnly.isEmpty() || !mutating.isEmpty();
    }
}
