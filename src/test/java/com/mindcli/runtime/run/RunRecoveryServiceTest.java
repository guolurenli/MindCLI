package com.mindcli.runtime.run;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRecoveryServiceTest {

    @Test
    void inspectsResumableRunFromRunStore() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        RunRecoveryPlan plan = new RunRecoveryService(runStore).inspect(context.runId());

        assertEquals(context.runId(), plan.runId());
        assertEquals(RunStateStatus.RESUMABLE, plan.stateStatus());
        assertTrue(plan.resumable());
        assertEquals(AgentRunEventType.LLM_RESPONSE, plan.lastCompletedEventType());
        assertEquals(List.of(AgentRunEventType.RUN_STARTED, AgentRunEventType.LLM_RESPONSE, AgentRunEventType.RUN_CANCELLED),
                plan.events().stream().map(AgentRunEvent::type).toList());
    }

    @Test
    void exposesSnapshotCheckpointsAndRestoreHint() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.SNAPSHOT_CREATED, java.util.Map.of(
                "snapshotPhase", "PRE_RUN",
                "snapshotCommitId", "commit-pre",
                "snapshotShortCommitId", "commit-pre")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        RunRecoveryPlan plan = new RunRecoveryService(runStore).inspect(context.runId());

        assertEquals("commit-pre", plan.preRunSnapshotCommitId());
        assertEquals("", plan.postRunSnapshotCommitId());
        assertTrue(plan.restoreHint().contains("pre-run snapshot"));
        assertTrue(plan.restoreHint().contains("commit-pre"));
    }
}
