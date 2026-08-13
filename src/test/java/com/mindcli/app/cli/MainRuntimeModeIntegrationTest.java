package com.mindcli.app.cli;

import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunResult;
import com.mindcli.runtime.run.InMemoryRunStore;
import com.mindcli.runtime.run.ModeAdapter;
import com.mindcli.runtime.run.RunStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MainRuntimeModeIntegrationTest {

    @Test
    void planCliEntrypointRunsThroughRuntimeLedger() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext[] seenContext = new AgentRunContext[1];

        String content = Main.runModeWithRuntime(
                AgentMode.PLAN,
                "do plan",
                "workspace",
                runStore,
                null,
                adapterReturning(AgentMode.PLAN, runStore, seenContext, "plan ok"));

        assertEquals("plan ok", content);
        assertEquals(AgentMode.PLAN, seenContext[0].mode());
        assertEquals("do plan", seenContext[0].input());
        assertEquals("workspace", seenContext[0].workspace());
        assertEventTypes(runStore.events(seenContext[0].runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FINISHED);
    }

    @Test
    void teamCliEntrypointRunsThroughRuntimeLedger() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext[] seenContext = new AgentRunContext[1];

        String content = Main.runModeWithRuntime(
                AgentMode.TEAM,
                "do team",
                "workspace",
                runStore,
                null,
                adapterReturning(AgentMode.TEAM, runStore, seenContext, "team ok"));

        assertEquals("team ok", content);
        assertEquals(AgentMode.TEAM, seenContext[0].mode());
        assertEquals("do team", seenContext[0].input());
        assertEventTypes(runStore.events(seenContext[0].runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FINISHED);
    }

    private static ModeAdapter adapterReturning(AgentMode mode, RunStore expectedRunStore,
                                                AgentRunContext[] seenContext, String content) {
        return new ModeAdapter() {
            @Override
            public AgentMode mode() {
                return mode;
            }

            @Override
            public AgentRunResult execute(AgentRunContext context) {
                throw new AssertionError("CLI runtime entrypoint must pass the shared RunStore");
            }

            @Override
            public AgentRunResult execute(AgentRunContext context, RunStore runStore) {
                assertSame(expectedRunStore, runStore);
                seenContext[0] = context;
                return AgentRunResult.success(context, content);
            }
        };
    }

    private static void assertEventTypes(List<AgentRunEvent> events, AgentRunEventType... expected) {
        assertEquals(expected.length, events.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], events.get(i).type());
        }
    }
}
