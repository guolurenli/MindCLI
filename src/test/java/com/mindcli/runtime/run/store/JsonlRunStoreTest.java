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
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlRunStoreTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void appendsEventsToJsonlAndReadsThemBack() {
        Path runsRoot = tempDir.resolve("runs");
        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", tempDir.toString());

        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE, Map.of(
                "iteration", "1",
                "toolCallCount", "0"
        )));

        List<AgentRunEvent> events = new JsonlRunStore(runsRoot).events(context.runId());

        assertEquals(2, events.size());
        assertEquals(context.runId(), events.get(0).runId());
        assertEquals(AgentRunEventType.RUN_STARTED, events.get(0).type());
        assertEquals("REACT", events.get(0).attributes().get("mode"));
        assertEquals(AgentRunEventType.LLM_RESPONSE, events.get(1).type());
        assertEquals("1", events.get(1).attributes().get("iteration"));
    }

    @Test
    void assignsSequenceAndEventIdsWhenAppendingEvents() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", tempDir.toString());

        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_FINISHED));

        List<AgentRunEvent> events = runStore.events(context.runId());
        assertEquals(1, events.get(0).seq());
        assertEquals(2, events.get(1).seq());
        assertFalse(events.get(0).eventId().isBlank());
        assertFalse(events.get(1).eventId().isBlank());
        assertNotEquals(events.get(0).eventId(), events.get(1).eventId());

        Path ledgerFile = runsRoot.resolve(context.runId()).resolve("run.jsonl");
        JsonNode firstLine = MAPPER.readTree(Files.readAllLines(ledgerFile).get(0));
        assertEquals(1, firstLine.path("seq").asLong());
        assertFalse(firstLine.path("eventId").asText().isBlank());
    }

    @Test
    void ignoresTrailingCorruptedLineWhenReading() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "plan it", tempDir.toString());

        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        Path ledgerFile = runsRoot.resolve(context.runId()).resolve("run.jsonl");
        Files.writeString(ledgerFile, "{not-json}\n", StandardOpenOption.APPEND);

        List<AgentRunEvent> events = assertDoesNotThrow(() -> new JsonlRunStore(runsRoot).events(context.runId()));

        assertEquals(1, events.size());
        assertEquals(AgentRunEventType.RUN_STARTED, events.get(0).type());
    }

    @Test
    void truncatesTrailingCorruptedLineBeforeAppendingNewEvents() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "plan it", tempDir.toString());

        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        Path ledgerFile = runsRoot.resolve(context.runId()).resolve("run.jsonl");
        Files.writeString(ledgerFile, "{not-json}", StandardOpenOption.APPEND);
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_FINISHED, Map.of("status", "SUCCESS")));

        List<AgentRunEvent> events = runStore.events(context.runId());

        assertEquals(List.of(AgentRunEventType.RUN_STARTED, AgentRunEventType.RUN_FINISHED),
                events.stream().map(AgentRunEvent::type).toList());
        assertEquals(2, events.get(1).seq());
    }

    @Test
    void loadsValidPrefixAndNextSequenceFromOneLedgerSnapshot() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "plan it", tempDir.toString());

        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        Path ledgerFile = runsRoot.resolve(context.runId()).resolve("run.jsonl");
        Files.writeString(ledgerFile, "{not-json}\n", StandardOpenOption.APPEND);

        JsonlRunStore.LoadedLedger loaded = JsonlRunStore.loadLedger(ledgerFile);

        assertEquals(1, loaded.events().size());
        assertEquals(2, loaded.nextSeq());
        assertTrue(loaded.corruptedTail());
    }

    @Test
    void materializesMetaAndStateFiles() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "team it", tempDir.toString());

        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        Path runDir = runsRoot.resolve(context.runId());
        JsonNode meta = MAPPER.readTree(Files.readString(runDir.resolve("run.meta.json")));
        JsonNode state = MAPPER.readTree(Files.readString(runDir.resolve("run.state.json")));

        assertEquals(context.runId(), meta.path("runId").asText());
        assertEquals("TEAM", meta.path("mode").asText());
        assertEquals("RESUMABLE", state.path("status").asText());
        assertEquals("RUN_CANCELLED", state.path("lastEventType").asText());
    }

    @Test
    void storesChildRunsUnderParentAndMaterializesChildSummaries() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        AgentRunContext parent = AgentRunContext.create(AgentMode.TEAM, "team it", tempDir.toString());
        AgentRunContext child = AgentRunContext.create(AgentMode.TEAM, "team it", tempDir.toString(), Map.of(
                "parentRunId", parent.runId(),
                "rootRunId", parent.runId(),
                "role", "worker",
                "stepId", "step_1",
                "attempt", "0",
                "profileName", "code-writer",
                "profileRole", "WORKER",
                "permissionMode", "WRITE_LIMITED",
                "selectedReason", "preferredAgent matched"));

        runStore.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(child, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(child, AgentRunEventType.RUN_FINISHED, Map.of("status", "SUCCESS")));

        Path childLedger = runsRoot.resolve(parent.runId())
                .resolve("children")
                .resolve(child.runId())
                .resolve("run.jsonl");
        assertEquals(true, Files.exists(childLedger));
        assertEquals(List.of(AgentRunEventType.RUN_STARTED, AgentRunEventType.RUN_FINISHED),
                runStore.events(child.runId()).stream().map(AgentRunEvent::type).toList());

        JsonNode parentState = MAPPER.readTree(Files.readString(runsRoot.resolve(parent.runId())
                .resolve("run.state.json")));
        JsonNode childSummary = parentState.path("childRuns").get(0);
        assertEquals(child.runId(), childSummary.path("runId").asText());
        assertEquals("worker", childSummary.path("role").asText());
        assertEquals("step_1", childSummary.path("stepId").asText());
        assertEquals("TERMINAL", childSummary.path("status").asText());
        assertEquals("code-writer", childSummary.path("profileName").asText());
        assertEquals("WORKER", childSummary.path("profileRole").asText());
        assertEquals("WRITE_LIMITED", childSummary.path("permissionMode").asText());
        assertEquals("preferredAgent matched", childSummary.path("selectedReason").asText());
    }

    @Test
    void rejectsUnsafeRunIdsBeforeTheyBecomePaths() {
        JsonlRunStore runStore = new JsonlRunStore(tempDir.resolve("runs"));

        assertThrows(IllegalArgumentException.class,
                () -> runStore.append(new AgentRunEvent("../escape",
                        AgentRunEventType.RUN_STARTED,
                        null,
                        Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> runStore.append(new AgentRunEvent("nested\\escape",
                        AgentRunEventType.RUN_STARTED,
                        null,
                        Map.of())));
    }
}
