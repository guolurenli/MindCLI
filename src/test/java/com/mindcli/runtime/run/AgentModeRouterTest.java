package com.mindcli.runtime.run;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModeRouterTest {

    @Test
    void routesInputToRegisteredModeAdapter() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentModeRouter router = new AgentModeRouter(runtime, List.of(new ModeAdapter() {
            @Override
            public AgentMode mode() {
                return AgentMode.TEAM;
            }

            @Override
            public AgentRunResult execute(AgentRunContext context) {
                return AgentRunResult.success(context, context.mode() + ":" + context.input());
            }
        }), "workspace");

        AgentRunResult result = router.submit("ship it", AgentMode.TEAM);

        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals("TEAM:ship it", result.content());
        assertEquals("workspace", result.metadata().get("workspace"));
        assertEquals(3, runStore.events(result.runId()).size());
    }

    @Test
    void returnsFailureWhenModeIsNotRegistered() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentModeRouter router = new AgentModeRouter(runtime, List.of(), "workspace");

        AgentRunResult result = router.submit("plan it", AgentMode.PLAN);

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("Unsupported mode"));
        assertTrue(runStore.events(result.runId()).isEmpty());
    }
}
