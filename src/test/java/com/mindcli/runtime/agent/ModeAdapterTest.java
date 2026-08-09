package com.mindcli.runtime.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModeAdapterTest {

    @Test
    void reactAdapterWrapsLegacyStringResult() {
        ReActModeAdapter adapter = new ReActModeAdapter(input -> "react:" + input);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");

        AgentRunResult result = adapter.execute(context);

        assertEquals(AgentMode.REACT, adapter.mode());
        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals("react:hello", result.content());
    }

    @Test
    void planAdapterWrapsLegacyExceptionAsFailure() {
        PlanModeAdapter adapter = new PlanModeAdapter(input -> {
            throw new IllegalArgumentException("bad plan");
        });
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "hello", "workspace");

        AgentRunResult result = adapter.execute(context);

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("bad plan"));
    }

    @Test
    void teamAdapterUsesTeamMode() {
        TeamModeAdapter adapter = new TeamModeAdapter(input -> "team:" + input);
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "hello", "workspace");

        AgentRunResult result = adapter.execute(context);

        assertEquals(AgentMode.TEAM, adapter.mode());
        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals("team:hello", result.content());
    }
}
