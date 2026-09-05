package com.mindcli.runtime.run.store;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunStateProjectorTest {

    @Test
    void projectsTerminalStateFromEvents() {
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        List<AgentRunEvent> events = List.of(
                event(context, 1, AgentRunEventType.RUN_STARTED, Map.of()),
                event(context, 2, AgentRunEventType.LLM_RESPONSE, Map.of("iteration", "1")),
                event(context, 3, AgentRunEventType.RUN_FINISHED, Map.of("status", "SUCCESS"))
        );

        RunStateProjection projection = new RunStateProjector().project(events);

        assertEquals(RunStateStatus.TERMINAL, projection.status());
        assertEquals(AgentRunEventType.RUN_FINISHED, projection.lastEventType());
        assertTrue(projection.isTerminal());
    }

    @Test
    void projectsResumableStateWhenRunPausedMidLoop() {
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "team", "workspace");
        List<AgentRunEvent> events = List.of(
                event(context, 1, AgentRunEventType.RUN_STARTED, Map.of()),
                event(context, 2, AgentRunEventType.LLM_RESPONSE, Map.of("iteration", "1")),
                event(context, 3, AgentRunEventType.TOOL_OUTCOME, Map.of("toolId", "call_1")),
                event(context, 4, AgentRunEventType.RUN_CANCELLED, Map.of("status", "CANCELLED"))
        );

        RunStateProjection projection = new RunStateProjector().project(events);

        assertEquals(RunStateStatus.RESUMABLE, projection.status());
        assertEquals(AgentRunEventType.TOOL_OUTCOME, projection.lastCompletedEventType());
        assertEquals("call_1", projection.lastCompletedAttributes().get("toolId"));
        assertEquals(AgentRunEventType.RUN_CANCELLED, projection.lastEventType());
    }

    @Test
    void projectsBlockedFailureAsManualRecoveryState() {
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "team", "workspace");
        List<AgentRunEvent> events = List.of(
                event(context, 1, AgentRunEventType.RUN_STARTED, Map.of()),
                event(context, 2, AgentRunEventType.RUN_FAILED, Map.of("status", "BLOCKED"))
        );

        RunStateProjection projection = new RunStateProjector().project(events);

        assertEquals(RunStateStatus.MANUAL, projection.status());
        assertEquals(AgentRunEventType.RUN_FAILED, projection.lastCompletedEventType());
    }

    private static AgentRunEvent event(AgentRunContext context, long seq, AgentRunEventType type,
                                       Map<String, String> attributes) {
        return new AgentRunEvent(context.runId(), type, Instant.ofEpochSecond(seq), attributes);
    }
}
