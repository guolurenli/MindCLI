package com.mindcli.runtime.run.recovery;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import java.util.List;
import java.util.Map;

public record RunRecoveryPlan(
        String runId,
        AgentMode mode,
        String workspace,
        String originalInput,
        RunStateStatus stateStatus,
        boolean resumable,
        boolean resumeAvailable,
        RunResumePlan resumePlan,
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
        workspace = workspace == null ? "" : workspace;
        originalInput = originalInput == null ? "" : originalInput;
        resumePlan = resumePlan == null ? new RunResumePlan(false, true, "UNKNOWN", "未生成恢复计划", List.of()) : resumePlan;
    }
}
