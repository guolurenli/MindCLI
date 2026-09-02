package com.mindcli.app.cli;

import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunResult;
import com.mindcli.runtime.run.store.InMemoryRunStore;
import com.mindcli.runtime.run.ModeAdapter;
import com.mindcli.runtime.run.store.RunStore;
import com.mindcli.runtime.run.session.SessionContext;
import com.mindcli.platform.snapshot.SnapshotService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void reactCliEntrypointRunsThroughRuntimeLedger() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext[] seenContext = new AgentRunContext[1];

        String content = Main.runReactModeWithRuntime(
                "do react",
                "workspace",
                runStore,
                null,
                adapterReturning(AgentMode.REACT, runStore, seenContext, "react ok"));

        assertEquals("react ok", content);
        assertEquals(AgentMode.REACT, seenContext[0].mode());
        assertEquals("do react", seenContext[0].input());
        assertEventTypes(runStore.events(seenContext[0].runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FINISHED);
    }

    @Test
    void runtimeHelperPublishesResultToSharedSessionContext() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        SessionContext sessionContext = new SessionContext();

        String content = Main.runModeWithRuntime(
                AgentMode.PLAN,
                "do plan",
                "workspace",
                runStore,
                null,
                adapterReturning(AgentMode.PLAN, runStore, new AgentRunContext[1], "plan result"),
                sessionContext);

        assertEquals("plan result", content);
        assertTrue(sessionContext.promptContext(1_000).contains("plan result"));
    }

    @Test
    void runtimeHelperUsesRouterModeLookup() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AtomicBoolean executed = new AtomicBoolean(false);

        String content = Main.runModeWithRuntime(
                AgentMode.PLAN,
                "do plan",
                "workspace",
                runStore,
                null,
                new ModeAdapter() {
                    @Override
                    public AgentMode mode() {
                        return AgentMode.TEAM;
                    }

                    @Override
                    public AgentRunResult execute(AgentRunContext context) {
                        executed.set(true);
                        return AgentRunResult.success(context, "team ok");
                    }
                });

        assertEquals("Unsupported mode: PLAN", content);
        assertFalse(executed.get());
        assertTrue(runStore.events("missing").isEmpty());
    }

    @Test
    void agentTaskDoesNotUseOuterTurnSnapshotWrapper() throws Exception {
        RecordingSnapshotService snapshotService = new RecordingSnapshotService();

        String content = Main.runAgentTask("react", "do react", snapshotService, () -> "react ok");

        assertEquals("react ok", content);
        assertFalse(snapshotService.runTurnCalled.get());
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

    private static final class RecordingSnapshotService extends SnapshotService {
        private final AtomicBoolean runTurnCalled = new AtomicBoolean(false);

        private RecordingSnapshotService() {
            super(null);
        }

        @Override
        public <T> T runTurn(String mode, String input, ThrowingSupplier<T> supplier) throws Exception {
            runTurnCalled.set(true);
            return supplier.get();
        }
    }
}
