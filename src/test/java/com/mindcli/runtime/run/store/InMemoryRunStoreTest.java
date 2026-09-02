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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRunStoreTest {

    @Test
    void appendsEventsPerRunInOrder() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext run1 = AgentRunContext.create(AgentMode.REACT, "one", "workspace");
        AgentRunContext run2 = AgentRunContext.create(AgentMode.PLAN, "two", "workspace");

        runStore.append(AgentRunEvent.of(run1, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(run2, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(run1, AgentRunEventType.RUN_FINISHED));

        List<AgentRunEvent> run1Events = runStore.events(run1.runId());
        assertEquals(2, run1Events.size());
        assertEquals(AgentRunEventType.RUN_STARTED, run1Events.get(0).type());
        assertEquals(AgentRunEventType.RUN_FINISHED, run1Events.get(1).type());

        List<AgentRunEvent> run2Events = runStore.events(run2.runId());
        assertEquals(1, run2Events.size());
        assertEquals(AgentRunEventType.RUN_STARTED, run2Events.get(0).type());
    }

    @Test
    void assignsSequenceAndEventIdsPerRun() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext run1 = AgentRunContext.create(AgentMode.REACT, "one", "workspace");
        AgentRunContext run2 = AgentRunContext.create(AgentMode.PLAN, "two", "workspace");

        runStore.append(AgentRunEvent.of(run1, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(run1, AgentRunEventType.RUN_FINISHED));
        runStore.append(AgentRunEvent.of(run2, AgentRunEventType.RUN_STARTED));

        List<AgentRunEvent> run1Events = runStore.events(run1.runId());
        assertEquals(1, run1Events.get(0).seq());
        assertEquals(2, run1Events.get(1).seq());
        assertFalse(run1Events.get(0).eventId().isBlank());
        assertFalse(run1Events.get(1).eventId().isBlank());
        assertNotEquals(run1Events.get(0).eventId(), run1Events.get(1).eventId());

        assertEquals(1, runStore.events(run2.runId()).get(0).seq());
    }

    @Test
    void returnsImmutableSnapshot() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));

        List<AgentRunEvent> events = runStore.events(context.runId());

        assertThrows(UnsupportedOperationException.class,
                () -> events.add(AgentRunEvent.of(context, AgentRunEventType.RUN_FINISHED)));
        assertEquals(1, runStore.events(context.runId()).size());
    }

    @Test
    void unknownRunReturnsEmptyList() {
        InMemoryRunStore runStore = new InMemoryRunStore();

        assertTrue(runStore.events("missing").isEmpty());
    }
}
