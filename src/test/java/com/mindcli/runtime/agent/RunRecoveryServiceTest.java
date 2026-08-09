package com.mindcli.runtime.agent;

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
}
