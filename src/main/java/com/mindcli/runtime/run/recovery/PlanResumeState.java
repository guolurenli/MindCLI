package com.mindcli.runtime.run.recovery;

import java.util.List;

/** Immutable Plan checkpoint projection used across the runtime/agent boundary. */
public record PlanResumeState(
        boolean available,
        int planVersion,
        String planId,
        String goal,
        String summary,
        List<PlanTaskResumeState> tasks,
        String reason) {
    public PlanResumeState {
        planId = planId == null ? "" : planId;
        goal = goal == null ? "" : goal;
        summary = summary == null ? "" : summary;
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        reason = reason == null ? "" : reason;
    }

    public static PlanResumeState unavailable(String reason) {
        return new PlanResumeState(false, 0, "", "", "", List.of(), reason);
    }
}
