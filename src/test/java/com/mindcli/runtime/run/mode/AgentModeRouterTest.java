package com.mindcli.runtime.run.mode;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

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
