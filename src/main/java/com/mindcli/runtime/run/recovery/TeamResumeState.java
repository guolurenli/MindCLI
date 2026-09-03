package com.mindcli.runtime.run.recovery;

import java.util.List;

/** Immutable recovered Team plan and its latest parent checkpoints. */
public record TeamResumeState(
        boolean available,
        int schemaVersion,
        int planVersion,
        List<TeamStepResumeState> steps,
        String reason) {
    public TeamResumeState {
        steps = steps == null ? List.of() : List.copyOf(steps);
        reason = reason == null ? "" : reason;
    }

    public static TeamResumeState unavailable(String reason) {
        return new TeamResumeState(false, 0, 0, List.of(), reason);
    }
}
