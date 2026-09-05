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

import com.mindcli.agent.Agent;
import com.mindcli.agent.team.AgentOrchestrator;
import com.mindcli.agent.PlanExecuteAgent;
import com.mindcli.capability.memory.LongTermMemory;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.snapshot.SnapshotService;
import com.mindcli.capability.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsSuccessLifecycleEventsInOrder() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");

        AgentRunResult result = runtime.run(context, adapterReturning(AgentMode.REACT,
                AgentRunResult.success(context, "done")));

        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals("done", result.content());
        assertEquals("hello", runStore.events(context.runId()).get(0).attributes().get("input"));
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FINISHED);
    }

    @Test
    void recordsFailureWhenAdapterReturnsFailure() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "plan it", "workspace");

        AgentRunResult result = runtime.run(context, adapterReturning(AgentMode.PLAN,
                AgentRunResult.failed(context, "no plan")));

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertEquals("no plan", result.errorMessage());
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FAILED);
    }

    @Test
    void persistsOriginalInputAndResumesInterruptedRunWithoutNewRunId() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "resume me", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        AgentRunResult result = runtime.resume(context.runId(), adapterReturning(AgentMode.REACT,
                AgentRunResult.success(context, "resumed")));

        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals("resumed", result.content());
        assertEquals(context.runId(), result.runId());
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.RUN_CANCELLED,
                AgentRunEventType.RUN_RESUMED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FINISHED);
    }

    @Test
    void reactResumeReusesCompletedToolResultWithoutDuplicatingUserMessage() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        ScriptedClient client = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "final from checkpoint", null, 10, 3)));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(client, registry, runStore);
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = new AgentRunContext("run-react-resume", AgentMode.REACT, "inspect file",
                tempDir.toString(), java.time.Instant.now(), java.util.Map.of());
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE, java.util.Map.of(
                "content", "", "reasoningContent", "", "toolCallCount", "1",
                "toolCallsJson", "[{\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, java.util.Map.of(
                "toolId", "call_1", "toolName", "read_file", "argumentsJson", "{\"path\":\"a.txt\"}",
                "text", "file text", "status", "COMPLETED")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        AgentRunResult result = runtime.resume(context.runId(), new ReActModeAdapter(agent));

        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals("final from checkpoint", result.content());
        assertEquals(List.of("system", "user", "assistant", "tool"),
                client.lastMessages.stream().map(LlmClient.Message::role).toList());
        assertEquals(1, client.lastMessages.stream().filter(message -> "user".equals(message.role())).count());
    }

    @Test
    void doesNotAppendResumeMarkerWhenReactCheckpointCannotBeReconstructed() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = new AgentRunContext("run-react-incomplete", AgentMode.REACT, "inspect file",
                tempDir.toString(), java.time.Instant.now(), java.util.Map.of());
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE,
                java.util.Map.of("content", "", "reasoningContent", "", "toolCallCount", "1",
                        "toolCallsJson", "[{\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new ScriptedClient(List.of()), registry, runStore);

        AgentRunResult result = runtime.resume(context.runId(), new ReActModeAdapter(agent));

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertTrue(runStore.events(context.runId()).stream()
                .noneMatch(event -> event.type() == AgentRunEventType.RUN_RESUMED));
    }

    @Test
    void planResumeUsesRecordedDagWithoutReplanning() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = new AgentRunContext(
                "run-plan-resume",
                AgentMode.PLAN,
                "finish plan",
                tempDir.toString(),
                java.time.Instant.now(),
                java.util.Map.of());
        PlanResumeState state = new PlanResumeState(
                true,
                1,
                "plan-1",
                context.input(),
                "recorded",
                List.of(new PlanTaskResumeState(
                        "task_1", "finish remaining work", "ANALYSIS", List.of(), true,
                        0, "BLOCK", List.of(), List.of(), "", "low",
                        "PENDING", "", "", 0)),
                "");
        appendPlanLedger(runStore, context, state);
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        PlanExecuteAgent agent = new PlanExecuteAgent(
                new ScriptedClient(List.of(
                        new LlmClient.ChatResponse("assistant", "remaining done", null, 10, 3))),
                registry,
                null,
                (goal, plan) -> {
                    throw new AssertionError("recovery must not review the recorded plan");
                },
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                runStore);

        AgentRunResult result = new AgentRuntime(runStore).resume(
                context.runId(), new PlanModeAdapter(agent));

        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals(context.runId(), result.runId());
        assertEquals(1, runStore.events(context.runId()).stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_RESUMED)
                .count());
        assertEquals(List.of("task_1"), runStore.events(context.runId()).stream()
                .filter(event -> event.type() == AgentRunEventType.PLAN_TASK_CHECKPOINT)
                .filter(event -> "RUNNING".equals(event.attributes().get("taskStatus")))
                .map(event -> event.attributes().get("taskId"))
                .toList());
    }

    @Test
    void planResumeRejectsLegacyLedgerBeforeAppendingResumeMarker() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = new AgentRunContext(
                "run-plan-legacy",
                AgentMode.PLAN,
                "finish plan",
                tempDir.toString(),
                java.time.Instant.now(),
                java.util.Map.of());
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        AgentRunResult result = new AgentRuntime(runStore).resume(
                context.runId(), adapterReturning(AgentMode.PLAN,
                        AgentRunResult.success(context, "must not execute")));

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("精确恢复 checkpoint"), result.errorMessage());
        assertTrue(runStore.events(context.runId()).stream()
                .noneMatch(event -> event.type() == AgentRunEventType.RUN_RESUMED));
    }

    @Test
    void recordsSnapshotCheckpointsWhenSnapshotServiceIsConfigured() throws Exception {
        InMemoryRunStore runStore = new InMemoryRunStore();
        try (SnapshotService snapshotService = SnapshotService.forProject(tempDir)) {
            AgentRuntime runtime = new AgentRuntime(runStore, snapshotService);
            AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", tempDir.toString());

            AgentRunResult result = runtime.run(context, adapterReturning(AgentMode.REACT,
                    AgentRunResult.success(context, "done")));
            snapshotService.awaitIdle();

            assertEquals(AgentRunStatus.SUCCESS, result.status());
            List<AgentRunEvent> events = runStore.events(context.runId());
            assertEventTypes(events,
                    AgentRunEventType.SNAPSHOT_CREATED,
                    AgentRunEventType.RUN_STARTED,
                    AgentRunEventType.MODE_SELECTED,
                    AgentRunEventType.RUN_FINISHED,
                    AgentRunEventType.SNAPSHOT_CREATED);
            assertEquals("PRE_RUN", events.get(0).attributes().get("snapshotPhase"));
            assertEquals("POST_RUN", events.get(4).attributes().get("snapshotPhase"));
            assertEquals("SUCCESS", events.get(4).attributes().get("status"));
            assertFalseBlank(events.get(0).attributes().get("snapshotCommitId"));
            assertFalseBlank(events.get(4).attributes().get("snapshotCommitId"));
        }
    }

    @Test
    void reactAdapterRecordsLoopEventsInRuntimeRunStore() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", tempDir.toString());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "done", null, 10, 3)
        )), registry);

        AgentRunResult result = runtime.run(context, new ReActModeAdapter(agent));

        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals("done", result.content());
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.MEMORY_CONTEXT_BUILT,
                AgentRunEventType.LLM_RESPONSE,
                AgentRunEventType.RUN_FINISHED);
    }

    @Test
    void agentRunStringRecordsLifecycleAndLoopEventsInItsDefaultRunStore() throws Exception {
        RecordingRunStore runStore = new RecordingRunStore();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "done", null, 10, 3)
        )), registry, runStore);

        String result = agent.run("hello");
        registry.getSnapshotService().awaitIdle();

        assertEquals("done", result);
        assertEventTypes(runStore.allEvents(),
                AgentRunEventType.SNAPSHOT_CREATED,
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.MEMORY_CONTEXT_BUILT,
                AgentRunEventType.LLM_RESPONSE,
                AgentRunEventType.RUN_FINISHED,
                AgentRunEventType.SNAPSHOT_CREATED);
    }

    @Test
    void reactAdapterReportsLlmFailureAsRuntimeFailure() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", tempDir.toString());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new ScriptedClient(new IOException("llm down")), registry);

        AgentRunResult result = runtime.run(context, new ReActModeAdapter(agent));

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("llm down"));
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.MEMORY_CONTEXT_BUILT,
                AgentRunEventType.RUN_FAILED);
    }

    @Test
    void convertsAdapterExceptionToFailedResultAndRunFailedEvent() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "team task", "workspace");

        AgentRunResult result = runtime.run(context, new ModeAdapter() {
            @Override
            public AgentMode mode() {
                return AgentMode.TEAM;
            }

            @Override
            public AgentRunResult execute(AgentRunContext context) {
                throw new IllegalStateException("boom");
            }
        });

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("boom"));
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FAILED);
    }

    @Test
    void resumesTeamThroughRecoveredAdapterWithoutDuplicateResumeMarker() {
        InMemoryRunStore store = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "resume team", tempDir.toString());
        TeamResumeState state = new TeamResumeState(true, 1, 1, List.of(
                new TeamStepResumeState("step_1", "done", "ANALYSIS", List.of(), List.of(),
                        "", "low", "COMPLETED", "", 0, "result", "", List.of())), "");
        appendTeamLedger(store, context, state, "COMPLETED", "", "");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        LlmClient llm = new ScriptedClient(List.of());
        MemoryManager memory = new MemoryManager(llm, llm.maxContextWindow(),
                new LongTermMemory(tempDir.resolve("memory").toFile()));
        TeamModeAdapter adapter = new TeamModeAdapter(new AgentOrchestrator(
                llm, registry, memory,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), store));

        AgentRunResult result = new AgentRuntime(store).resume(context.runId(), adapter);

        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals(1, store.events(context.runId()).stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_RESUMED).count());
    }

    @Test
    void doesNotAppendRunResumedWhenTeamStateIsUnsafe() {
        InMemoryRunStore store = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "resume team", tempDir.toString());
        TeamResumeState state = new TeamResumeState(true, 1, 1, List.of(
                new TeamStepResumeState("step_1", "write", "FILE_WRITE", List.of(),
                        List.of("write_file"), "worker", "high", "PENDING", "", 0, "", "", List.of())), "");
        appendTeamLedger(store, context, state, "RUNNING", "AWAITING_MERGE", "");
        TeamModeAdapter adapter = new TeamModeAdapter(
                (ContextualLegacyAgentRunner) (runContext, runStore) -> "unused");

        AgentRunResult result = new AgentRuntime(store).resume(context.runId(), adapter);

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertTrue(store.events(context.runId()).stream()
                .noneMatch(event -> event.type() == AgentRunEventType.RUN_RESUMED));
    }

    private static ModeAdapter adapterReturning(AgentMode mode, AgentRunResult result) {
        return new ModeAdapter() {
            @Override
            public AgentMode mode() {
                return mode;
            }

            @Override
            public AgentRunResult execute(AgentRunContext context) {
                return result;
            }
        };
    }

    private static void appendPlanLedger(InMemoryRunStore store, AgentRunContext context,
                                         PlanResumeState state) {
        store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        store.append(AgentRunEvent.of(context, AgentRunEventType.PLAN_DEFINED, java.util.Map.of(
                "planVersion", Integer.toString(state.planVersion()),
                "reason", "INITIAL",
                "planJson", new PlanCheckpointCodec().encode(state))));
        store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));
    }

    private static void appendTeamLedger(InMemoryRunStore store, AgentRunContext context,
                                         TeamResumeState state, String status,
                                         String phase, String childRunId) {
        TeamCheckpointCodec codec = new TeamCheckpointCodec();
        store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        store.append(AgentRunEvent.of(context, AgentRunEventType.TEAM_PLAN_DEFINED, java.util.Map.of(
                "schemaVersion", "1", "planVersion", "1", "planJson", codec.encodePlan(state))));
        store.append(AgentRunEvent.of(context, AgentRunEventType.TEAM_STEP_CHECKPOINT, java.util.Map.of(
                "schemaVersion", "1", "planVersion", "1",
                "stepIdsJson", codec.encodeStepIds(List.of("step_1")),
                "stepStatus", status, "phase", phase, "attempt", "0",
                "childRunId", childRunId, "result", "result", "error", "")));
        store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));
    }

    private static void assertEventTypes(List<AgentRunEvent> events, AgentRunEventType... expected) {
        assertEquals(expected.length, events.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], events.get(i).type());
        }
    }

    private static void assertFalseBlank(String value) {
        assertTrue(value != null && !value.isBlank());
    }

    private static final class RecordingRunStore implements RunStore {
        private final List<AgentRunEvent> events = new ArrayList<>();

        @Override
        public void append(AgentRunEvent event) {
            events.add(event);
        }

        @Override
        public List<AgentRunEvent> events(String runId) {
            return events.stream()
                    .filter(event -> event.runId().equals(runId))
                    .toList();
        }

        private List<AgentRunEvent> allEvents() {
            return List.copyOf(events);
        }
    }

    private static final class ScriptedClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final IOException failure;
        private List<Message> lastMessages = List.of();

        private ScriptedClient(List<ChatResponse> responses) {
            this.responses.addAll(responses);
            this.failure = null;
        }

        private ScriptedClient(IOException failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            lastMessages = List.copyOf(messages);
            if (failure != null) {
                throw failure;
            }
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("missing response");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
