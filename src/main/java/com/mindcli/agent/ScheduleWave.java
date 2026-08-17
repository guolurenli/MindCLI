package com.mindcli.agent;

import java.util.List;
import java.util.Map;

/**
 * 调度器单次计算产出的一波工作（package-private 内部模型）。
 *
 * <p>{@code readOnly} 分组可并行执行；{@code mutating} 分组是否 worktree 并行，
 * 由 {@link AgentOrchestrator} 依据 {@code serialReasons} 决定。{@code serialReasons}
 * 是本波 mutating 的整体串行 veto：只要本波任一 mutating group 有串行原因，
 * 整波 mutating 回退串行。</p>
 */
record ScheduleWave(
        List<StepExecutionGroup> readOnly,
        List<StepExecutionGroup> mutating,
        Map<String, String> serialReasons
) {
    boolean hasWork() {
        return !readOnly.isEmpty() || !mutating.isEmpty();
    }
}
