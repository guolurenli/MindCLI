package com.mindcli.runtime.run.recovery;

import java.util.List;

/** Safety classification for retrying a resumable run. */
public record RunResumePlan(
        boolean allowed,
        boolean requiresConfirmation,
        String risk,
        String reason,
        List<String> toolNames) {
    public RunResumePlan {
        risk = risk == null ? "UNKNOWN" : risk;
        reason = reason == null ? "" : reason;
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    }
}
