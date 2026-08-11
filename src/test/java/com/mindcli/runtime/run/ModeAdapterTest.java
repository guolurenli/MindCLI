package com.mindcli.runtime.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void planAdapterPassesRuntimeContextAndRunStoreToRunner() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "hello", "workspace");
        final AgentRunContext[] seenContext = new AgentRunContext[1];
        final RunStore[] seenStore = new RunStore[1];
        PlanModeAdapter adapter = new PlanModeAdapter((runContext, store) -> {
            seenContext[0] = runContext;
            seenStore[0] = store;
            return "plan ok";
        });

        AgentRunResult result = adapter.execute(context, runStore);

        assertSame(context, seenContext[0]);
        assertSame(runStore, seenStore[0]);
        assertEquals(AgentRunStatus.SUCCESS, result.status());
    }

    @Test
    void teamAdapterPassesRuntimeContextAndRunStoreToRunner() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "hello", "workspace");
        final AgentRunContext[] seenContext = new AgentRunContext[1];
        final RunStore[] seenStore = new RunStore[1];
        TeamModeAdapter adapter = new TeamModeAdapter((runContext, store) -> {
            seenContext[0] = runContext;
            seenStore[0] = store;
            return "team ok";
        });

        AgentRunResult result = adapter.execute(context, runStore);

        assertSame(context, seenContext[0]);
        assertSame(runStore, seenStore[0]);
        assertEquals(AgentRunStatus.SUCCESS, result.status());
    }

    @Test
    void planAdapterConvertsFailureAndCancellationTextToStructuredStatus() {
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "hello", "workspace");

        AgentRunResult failed = new PlanModeAdapter((runContext, store) -> "❌ 执行失败: bad")
                .execute(context, new InMemoryRunStore());
        AgentRunResult cancelled = new PlanModeAdapter((runContext, store) -> "⏹️ 已取消当前计划执行。")
                .execute(context, new InMemoryRunStore());

        assertEquals(AgentRunStatus.FAILED, failed.status());
        assertTrue(failed.errorMessage().contains("bad"));
        assertEquals(AgentRunStatus.CANCELLED, cancelled.status());
    }

    @Test
    void planAndTeamAdaptersConvertWarningTextToBlockedStatus() {
        AgentRunContext planContext = AgentRunContext.create(AgentMode.PLAN, "hello", "workspace");
        AgentRunContext teamContext = AgentRunContext.create(AgentMode.TEAM, "hello", "workspace");

        AgentRunResult planBlocked = new PlanModeAdapter((runContext, store) -> "⚠️ 计划执行受阻: waiting")
                .execute(planContext, new InMemoryRunStore());
        AgentRunResult teamBlocked = new TeamModeAdapter((runContext, store) -> "⚠️ 多 Agent 任务受阻: waiting")
                .execute(teamContext, new InMemoryRunStore());

        assertEquals(AgentRunStatus.BLOCKED, planBlocked.status());
        assertTrue(planBlocked.errorMessage().contains("waiting"));
        assertEquals(AgentRunStatus.BLOCKED, teamBlocked.status());
        assertTrue(teamBlocked.errorMessage().contains("waiting"));
    }
}
