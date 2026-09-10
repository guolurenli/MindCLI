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
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
    void serializesAppendsAcrossStoreInstancesWithTheSameRunsRoot() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        JsonlRunStore firstStore = new JsonlRunStore(runsRoot);
        JsonlRunStore secondStore = new JsonlRunStore(runsRoot);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", tempDir.toString());
        int perStore = 20;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> appendMany(firstStore, context, perStore, ready, start));
            Future<?> second = executor.submit(() -> appendMany(secondStore, context, perStore, ready, start));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        List<AgentRunEvent> events = firstStore.events(context.runId());
        assertEquals(perStore * 2, events.size());
        assertEquals(java.util.stream.LongStream.rangeClosed(1, perStore * 2L).boxed().toList(),
                events.stream().map(AgentRunEvent::seq).toList());
    }

    @Test
    void waitsForAnExternalLedgerLockBeforeAppending() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", tempDir.toString());
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        Path lockFile = runsRoot.resolve(context.runId()).resolve("run.jsonl.lock");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (java.nio.channels.FileLock ignored = channel.lock()) {
                Future<?> append = executor.submit(() -> runStore.append(
                        AgentRunEvent.of(context, AgentRunEventType.RUN_FINISHED)));
                assertThrows(TimeoutException.class,
                        () -> append.get(1, TimeUnit.SECONDS));
            }
            appendAndAwait(executor);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void differentRunsDoNotBlockOnEachOtherInsideOneStoreInstance() throws Exception {
        Path runsRoot = tempDir.resolve("per-run-lock-runs");
        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        AgentRunContext blockedContext = AgentRunContext.create(AgentMode.REACT, "blocked", tempDir.toString());
        AgentRunContext independentContext = AgentRunContext.create(AgentMode.REACT, "independent", tempDir.toString());
        runStore.append(AgentRunEvent.of(blockedContext, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(independentContext, AgentRunEventType.RUN_STARTED));

        Path blockedLockFile = runsRoot.resolve(blockedContext.runId()).resolve("run.jsonl.lock");
        CopyOnWriteArrayList<Thread> workers = new CopyOnWriteArrayList<>();
        AtomicInteger workerNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
            Thread worker = new Thread(task, "jsonl-lock-test-" + workerNumber.incrementAndGet());
            workers.add(worker);
            return worker;
        });
        try (FileChannel channel = FileChannel.open(blockedLockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             java.nio.channels.FileLock ignored = channel.lock()) {
            Future<?> blocked = executor.submit(() -> runStore.append(
                    AgentRunEvent.of(blockedContext, AgentRunEventType.RUN_FINISHED)));
            assertTrue(awaitThreadState(workers, Thread.State.TIMED_WAITING, 2, TimeUnit.SECONDS));
            Future<?> independent = executor.submit(() -> runStore.append(
                    AgentRunEvent.of(independentContext, AgentRunEventType.RUN_FINISHED)));

            independent.get(1, TimeUnit.SECONDS);
            assertThrows(TimeoutException.class, () -> blocked.get(200, TimeUnit.MILLISECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static boolean awaitThreadState(List<Thread> threads, Thread.State state,
                                            long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (threads.stream().anyMatch(thread -> thread.getState() == state)) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    @Test
    void serializesAppendsAcrossIndependentJvmProcesses() throws Exception {
        Path runsRoot = tempDir.resolve("cross-process-runs");
        String runId = "run_cross_process";
        int perProcess = 25;
        String javaName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", javaName).toString();
        String classPath = System.getProperty("java.class.path");
        Process first = new ProcessBuilder(javaBinary, "-cp", classPath,
                JsonlRunStoreProcessWriter.class.getName(), runsRoot.toString(), runId,
                Integer.toString(perProcess))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        Process second = new ProcessBuilder(javaBinary, "-cp", classPath,
                JsonlRunStoreProcessWriter.class.getName(), runsRoot.toString(), runId,
                Integer.toString(perProcess))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        assertTrue(first.waitFor(15, TimeUnit.SECONDS));
        assertTrue(second.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, first.exitValue());
        assertEquals(0, second.exitValue());

        List<AgentRunEvent> events = new JsonlRunStore(runsRoot).events(runId);
        assertEquals(perProcess * 2, events.size());
        assertEquals(java.util.stream.LongStream.rangeClosed(1, perProcess * 2L).boxed().toList(),
                events.stream().map(AgentRunEvent::seq).toList());
    }

    private static void appendAndAwait(ExecutorService executor) throws Exception {
        // The queued append must be able to finish after the external lock is released.
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static void appendMany(JsonlRunStore store,
                                   AgentRunContext context,
                                   int count,
                                   CountDownLatch ready,
                                   CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            for (int i = 0; i < count; i++) {
                store.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE,
                        Map.of("worker", Thread.currentThread().getName(), "index", Integer.toString(i))));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void listsOnlyTopLevelRunsWithLedgers() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        AgentRunContext firstParent = AgentRunContext.create(AgentMode.REACT, "first", tempDir.toString());
        AgentRunContext secondParent = AgentRunContext.create(AgentMode.PLAN, "second", tempDir.toString());
        AgentRunContext child = AgentRunContext.create(AgentMode.TEAM, "child", tempDir.toString(), Map.of(
                "parentRunId", firstParent.runId(),
                "rootRunId", firstParent.runId()));

        runStore.append(AgentRunEvent.of(firstParent, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(secondParent, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(child, AgentRunEventType.RUN_STARTED));
        Files.createDirectories(runsRoot.resolve("not-a-run"));

        assertEquals(Set.of(firstParent.runId(), secondParent.runId()),
                Set.copyOf(runStore.topLevelRunIds()));
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

    @Test
    void rejectsUnsafeRunIdsBeforeReadPathsAreResolved() {
        JsonlRunStore runStore = new JsonlRunStore(tempDir.resolve("runs"));

        assertThrows(IllegalArgumentException.class, () -> runStore.events("../escape"));
        assertThrows(IllegalArgumentException.class, () -> runStore.runDir("nested/escape"));
    }

    @Test
    void rejectsUnsafeParentRunIdBeforeChildPathIsResolved() {
        JsonlRunStore runStore = new JsonlRunStore(tempDir.resolve("runs"));
        AgentRunContext child = AgentRunContext.create(AgentMode.TEAM, "child", tempDir.toString(), Map.of(
                "parentRunId", "../escape",
                "rootRunId", "root_run"));

        assertThrows(IllegalArgumentException.class,
                () -> runStore.append(AgentRunEvent.of(child, AgentRunEventType.RUN_STARTED)));
    }

    @Test
    void rejectsRunDirectorySymlinkEscapingRunsRoot() throws Exception {
        Path runsRoot = tempDir.resolve("runs");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("run.jsonl"), "{}");
        Files.createDirectories(runsRoot);
        Path link = runsRoot.resolve("linked_run");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }

        JsonlRunStore runStore = new JsonlRunStore(runsRoot);
        assertThrows(IllegalArgumentException.class, () -> runStore.events("linked_run"));
    }
}
