package com.mindcli.app.cli.runtime;

import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.recovery.RunRecoveryPlan;
import com.mindcli.runtime.run.store.InMemoryRunStore;
import com.mindcli.runtime.run.store.JsonlRunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRecoverableRunDiscoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void discoversNewestRecoverableParentRunsWithinLimit() {
        JsonlRunStore store = new JsonlRunStore(tempDir.resolve("runs"));
        appendResumable(store, "run_old", AgentMode.REACT, Instant.parse("2026-01-01T00:00:00Z"));
        appendResumable(store, "run_middle", AgentMode.PLAN, Instant.parse("2026-01-02T00:00:00Z"));
        appendResumable(store, "run_new", AgentMode.TEAM, Instant.parse("2026-01-03T00:00:00Z"));
        appendResumable(store, "run_newest", AgentMode.REACT, Instant.parse("2026-01-04T00:00:00Z"));
        appendTerminal(store, "run_done", Instant.parse("2026-01-05T00:00:00Z"));
        appendMissingInput(store, "run_without_input", Instant.parse("2026-01-06T00:00:00Z"));
        appendChild(store, "run_newest", "child_run", Instant.parse("2026-01-07T00:00:00Z"));

        List<RunRecoveryPlan> plans = CliRecoverableRunDiscovery.discover(store, 3);

        assertEquals(List.of("run_newest", "run_new", "run_middle"),
                plans.stream().map(RunRecoveryPlan::runId).toList());
    }

    @Test
    void ignoresMalformedLedgersAndNonPersistentStores() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        Path brokenRun = runsRoot.resolve("broken_run");
        Files.createDirectories(brokenRun);
        Files.writeString(brokenRun.resolve("run.jsonl"), "{not-json}\n");

        assertTrue(CliRecoverableRunDiscovery.discover(new JsonlRunStore(runsRoot), 3).isEmpty());
        assertTrue(CliRecoverableRunDiscovery.discover(new InMemoryRunStore(), 3).isEmpty());
    }

    @Test
    void formatsOneConciseStartupNoticeWithoutTaskInput() {
        JsonlRunStore store = new JsonlRunStore(tempDir.resolve("runs"));
        appendResumable(store, "run_resume", AgentMode.REACT, Instant.parse("2026-01-01T00:00:00Z"));

        String notice = CliRecoverableRunDiscovery.startupNotice(store);

        assertTrue(notice.contains("run_resume"), notice);
        assertTrue(notice.contains("REACT"), notice);
        assertTrue(notice.contains("/run inspect"), notice);
        assertTrue(notice.contains("/run resume"), notice);
        assertTrue(!notice.contains("secret task input"), notice);
        assertTrue(!notice.contains("\n"), notice);
    }

    private void appendResumable(JsonlRunStore store, String runId, AgentMode mode, Instant timestamp) {
        store.append(event(runId, AgentRunEventType.RUN_STARTED, timestamp, Map.of(
                "mode", mode.name(),
                "workspace", tempDir.toString(),
                "input", "secret task input")));
        store.append(event(runId, AgentRunEventType.RUN_CANCELLED, timestamp.plusSeconds(1), Map.of()));
    }

    private void appendTerminal(JsonlRunStore store, String runId, Instant timestamp) {
        store.append(event(runId, AgentRunEventType.RUN_STARTED, timestamp, Map.of(
                "mode", AgentMode.REACT.name(),
                "workspace", tempDir.toString(),
                "input", "done")));
        store.append(event(runId, AgentRunEventType.RUN_FINISHED, timestamp.plusSeconds(1), Map.of()));
    }

    private void appendMissingInput(JsonlRunStore store, String runId, Instant timestamp) {
        store.append(event(runId, AgentRunEventType.RUN_STARTED, timestamp, Map.of(
                "mode", AgentMode.REACT.name(),
                "workspace", tempDir.toString())));
        store.append(event(runId, AgentRunEventType.RUN_CANCELLED, timestamp.plusSeconds(1), Map.of()));
    }

    private void appendChild(JsonlRunStore store, String parentRunId, String childRunId, Instant timestamp) {
        store.append(event(childRunId, AgentRunEventType.RUN_STARTED, timestamp, Map.of(
                "mode", AgentMode.TEAM.name(),
                "workspace", tempDir.toString(),
                "input", "child input",
                "parentRunId", parentRunId,
                "rootRunId", parentRunId)));
        store.append(event(childRunId, AgentRunEventType.RUN_CANCELLED, timestamp.plusSeconds(1), Map.of(
                "parentRunId", parentRunId,
                "rootRunId", parentRunId)));
    }

    private static AgentRunEvent event(String runId, AgentRunEventType type, Instant timestamp,
                                       Map<String, String> attributes) {
        return new AgentRunEvent(runId, type, timestamp, attributes);
    }
}
