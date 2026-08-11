package com.mindcli.runtime.run;

import java.util.List;
import java.util.Map;

public record RunRecoveryPlan(
        String runId,
        RunStateStatus stateStatus,
        boolean resumable,
        boolean terminal,
        boolean manual,
        AgentRunEventType lastEventType,
        AgentRunEventType lastCompletedEventType,
        Map<String, String> lastCompletedAttributes,
        List<AgentRunEvent> events,
        String preRunSnapshotCommitId,
        String postRunSnapshotCommitId,
        String restoreHint
) {
    public RunRecoveryPlan {
        lastCompletedAttributes = lastCompletedAttributes == null ? Map.of() : Map.copyOf(lastCompletedAttributes);
        events = events == null ? List.of() : List.copyOf(events);
        preRunSnapshotCommitId = preRunSnapshotCommitId == null ? "" : preRunSnapshotCommitId;
        postRunSnapshotCommitId = postRunSnapshotCommitId == null ? "" : postRunSnapshotCommitId;
        restoreHint = restoreHint == null ? "" : restoreHint;
    }
}
