package com.mindcli.eval;

import com.mindcli.agent.team.AgentOrchestrator;
import com.mindcli.capability.memory.LongTermMemory;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.runtime.CancellationContext;
import com.mindcli.runtime.CancellationToken;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunResult;
import com.mindcli.runtime.run.AgentRunStatus;
import com.mindcli.runtime.run.AgentRuntime;
import com.mindcli.runtime.run.mode.TeamModeAdapter;
import com.mindcli.runtime.run.recovery.TeamCheckpointCodec;
import com.mindcli.runtime.run.recovery.TeamResumeState;
import com.mindcli.runtime.run.recovery.TeamStepResumeState;
import com.mindcli.runtime.run.store.JsonlRunStore;
import com.mindcli.runtime.run.store.RunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamExactResumeEvalTest {

    @Test
    void resumesRemainingStepAfterStoreReopenWithoutRepeatingCompletedStep(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.writeString(workspace.resolve("marker.txt"), "marker", StandardCharsets.UTF_8);
        Path runsRoot = root.resolve("runs");
        String runId = "run-team-exact-resume";
        AgentRunContext context = new AgentRunContext(runId, AgentMode.TEAM,
                "read marker then write result", workspace.toString(), Instant.now(), Map.of());
        JsonlRunStore initialStore = new JsonlRunStore(runsRoot);
        CancellationToken token = CancellationContext.startRun();
        AgentRunResult first;
        try {
            RunStore cancelling = new CancelOnTeamCheckpointRunStore(initialStore, token, "step_1");
            ScriptedClient initialLlm = ScriptedClient.sequence(
                    response("""
                            {"summary":"two steps","steps":[
                              {"id":"read","description":"read marker","type":"FILE_READ","dependencies":[],
                               "requiredTools":["read_file"]},
                              {"id":"write","description":"write result","type":"FILE_WRITE","dependencies":["read"],
                               "requiredTools":["write_file"],"riskLevel":"medium"}
                            ]}
                            """),
                    toolResponse("read", "read_marker", "read_file", "{\"path\":\"marker.txt\"}"),
                    response("marker read"),
                    response("{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}"));
            first = new AgentRuntime(cancelling).run(context,
                    new TeamModeAdapter(teamAgent(initialLlm, cancelling, workspace)));
        } finally {
            CancellationContext.clear(token);
        }
        assertEquals(AgentRunStatus.CANCELLED, first.status());

        JsonlRunStore reopened = new JsonlRunStore(runsRoot);
        ScriptedClient resumeLlm = ScriptedClient.sequence(
                toolResponse("write", "write_result", "write_file",
                        "{\"path\":\"result.txt\",\"content\":\"done\"}"),
                response("result written"),
                response("{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}"));
        AgentRunResult resumed = new AgentRuntime(reopened).resume(runId,
                new TeamModeAdapter(teamAgent(resumeLlm, reopened, workspace)));

        assertEquals(AgentRunStatus.SUCCESS, resumed.status(), resumed.errorMessage());
        assertEquals(1, childToolCount(reopened, runId, "read_marker"));
        assertEquals(1, childToolCount(reopened, runId, "write_result"));
        assertEquals("done", Files.readString(workspace.resolve("result.txt")));
    }

    @Test
    void refusesResumeAfterSuccessfulWriteWithoutTerminalCheckpoint(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.writeString(workspace.resolve("result.txt"), "once");
        Path runsRoot = root.resolve("runs");
        JsonlRunStore store = new JsonlRunStore(runsRoot);
        AgentRunContext parent = context("team-write-ambiguous", workspace);
        TeamResumeState state = oneStepState("FILE_WRITE", List.of("write_file"));
        appendParentStartAndPlan(store, parent, state);
        AgentRunContext child = childContext(parent, "write-child", "step_1");
        appendCheckpoint(store, parent, List.of("step_1"), "RUNNING", "EXECUTING", child.runId(), "", "");
        appendCompleteChildTool(store, child, "write_once", "write_file",
                "{\"path\":\"result.txt\",\"content\":\"once\"}");
        store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_CANCELLED));

        JsonlRunStore reopened = new JsonlRunStore(runsRoot);
        AgentRunResult result = resumeWithNoLlm(reopened, parent, workspace);

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertEquals("once", Files.readString(workspace.resolve("result.txt")));
        assertEquals(1, childToolCount(reopened, parent.runId(), "write_once"));
        assertNoResumeMarker(reopened, parent.runId());
    }

    @Test
    void refusesResumeWhenReviewChildHasStarted(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.writeString(workspace.resolve("marker.txt"), "stable");
        Path runsRoot = root.resolve("runs");
        JsonlRunStore store = new JsonlRunStore(runsRoot);
        AgentRunContext parent = context("team-review-ambiguous", workspace);
        appendParentStartAndPlan(store, parent, oneStepState("ANALYSIS", List.of()));
        AgentRunContext child = childContext(parent, "review-child", "step_1");
        appendCheckpoint(store, parent, List.of("step_1"), "RUNNING", "REVIEWING", child.runId(), "candidate", "");
        store.append(AgentRunEvent.of(child, AgentRunEventType.RUN_STARTED, Map.of("phase", "review")));
        store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_CANCELLED));

        JsonlRunStore reopened = new JsonlRunStore(runsRoot);
        AgentRunResult result = resumeWithNoLlm(reopened, parent, workspace);

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertEquals("stable", Files.readString(workspace.resolve("marker.txt")));
        assertNoResumeMarker(reopened, parent.runId());
    }

    @Test
    void refusesResumeAtAwaitingMergeBoundary(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path runsRoot = root.resolve("runs");
        JsonlRunStore store = new JsonlRunStore(runsRoot);
        AgentRunContext parent = context("team-awaiting-merge", workspace);
        appendParentStartAndPlan(store, parent, oneStepState("FILE_WRITE", List.of("write_file")));
        appendCheckpoint(store, parent, List.of("step_1"), "RUNNING", "AWAITING_MERGE", "", "candidate", "");
        store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_CANCELLED));

        JsonlRunStore reopened = new JsonlRunStore(runsRoot);
        AgentRunResult result = resumeWithNoLlm(reopened, parent, workspace);

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertFalse(Files.exists(workspace.resolve("result.txt")));
        assertNoResumeMarker(reopened, parent.runId());
    }

    @Test
    void completedWriteCheckpointDoesNotRepeatWrite(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.writeString(workspace.resolve("result.txt"), "done");
        Path runsRoot = root.resolve("runs");
        JsonlRunStore store = new JsonlRunStore(runsRoot);
        AgentRunContext parent = context("team-write-complete", workspace);
        appendParentStartAndPlan(store, parent, oneStepState("FILE_WRITE", List.of("write_file")));
        AgentRunContext child = childContext(parent, "completed-write-child", "step_1");
        appendCheckpoint(store, parent, List.of("step_1"), "RUNNING", "EXECUTING", child.runId(), "", "");
        appendCompleteChildTool(store, child, "write_done", "write_file",
                "{\"path\":\"result.txt\",\"content\":\"done\"}");
        appendCheckpoint(store, parent, List.of("step_1"), "COMPLETED", "", "", "done", "");
        store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_CANCELLED));

        JsonlRunStore reopened = new JsonlRunStore(runsRoot);
        AgentRunResult result = resumeWithNoLlm(reopened, parent, workspace);

        assertEquals(AgentRunStatus.SUCCESS, result.status(), result.errorMessage());
        assertEquals("done", Files.readString(workspace.resolve("result.txt")));
        assertEquals(1, childToolCount(reopened, parent.runId(), "write_done"));
        assertEquals(1, resumeMarkerCount(reopened, parent.runId()));
    }

    @Test
    void atomicDuplicateCheckpointSkipsBothSteps(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Files.writeString(workspace.resolve("marker.txt"), "unchanged");
        Path runsRoot = root.resolve("runs");
        JsonlRunStore store = new JsonlRunStore(runsRoot);
        AgentRunContext parent = context("team-duplicates-complete", workspace);
        TeamStepResumeState first = resumeStep("step_1", "same", "ANALYSIS", List.of(), List.of());
        TeamStepResumeState second = resumeStep("step_2", "same", "ANALYSIS", List.of(), List.of());
        TeamResumeState state = new TeamResumeState(true, 1, 1, List.of(first, second), "");
        appendParentStartAndPlan(store, parent, state);
        appendCheckpoint(store, parent, List.of("step_1", "step_2"), "COMPLETED", "", "", "shared", "");
        store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_CANCELLED));

        JsonlRunStore reopened = new JsonlRunStore(runsRoot);
        AgentRunResult result = resumeWithNoLlm(reopened, parent, workspace);

        assertEquals(AgentRunStatus.SUCCESS, result.status(), result.errorMessage());
        assertEquals("unchanged", Files.readString(workspace.resolve("marker.txt")));
        assertEquals(1, resumeMarkerCount(reopened, parent.runId()));
        assertEquals(1, reopened.events(parent.runId()).stream()
                .filter(event -> event.type() == AgentRunEventType.TEAM_STEP_CHECKPOINT)
                .count());
    }

    @Test
    void refusesResumeWhenChildRequestHasNoOutcome(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path runsRoot = root.resolve("runs");
        JsonlRunStore store = new JsonlRunStore(runsRoot);
        AgentRunContext parent = context("team-incomplete-tool", workspace);
        appendParentStartAndPlan(store, parent, oneStepState("FILE_READ", List.of("read_file")));
        AgentRunContext child = childContext(parent, "incomplete-child", "step_1");
        appendCheckpoint(store, parent, List.of("step_1"), "RUNNING", "EXECUTING", child.runId(), "", "");
        String calls = "[{\"id\":\"read_missing\",\"function\":{\"name\":\"read_file\","
                + "\"arguments\":\"{\\\"path\\\":\\\"missing.txt\\\"}\"}}]";
        store.append(AgentRunEvent.of(child, AgentRunEventType.RUN_STARTED, Map.of("phase", "execute")));
        store.append(AgentRunEvent.of(child, AgentRunEventType.LLM_RESPONSE, Map.of(
                "recordKind", "turn", "iteration", "1", "toolCallCount", "1", "toolCallsJson", calls)));
        store.append(AgentRunEvent.of(child, AgentRunEventType.TOOL_CALL_REQUESTED, Map.of(
                "recordKind", "turn", "iteration", "1", "toolCallCount", "1",
                "toolIds", "read_missing", "toolNames", "read_file")));
        store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_CANCELLED));

        JsonlRunStore reopened = new JsonlRunStore(runsRoot);
        AgentRunResult result = resumeWithNoLlm(reopened, parent, workspace);

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertFalse(Files.exists(workspace.resolve("missing.txt")));
        assertEquals(0, childToolCount(reopened, parent.runId(), "read_missing"));
        assertNoResumeMarker(reopened, parent.runId());
    }

    private static AgentRunContext context(String runId, Path workspace) {
        return new AgentRunContext(runId, AgentMode.TEAM, "team task", workspace.toString(),
                Instant.now(), Map.of());
    }

    private static AgentRunContext childContext(AgentRunContext parent, String runId, String stepId) {
        return new AgentRunContext(runId, AgentMode.TEAM, parent.input(), parent.workspace(),
                Instant.now(), Map.of("parentRunId", parent.runId(), "rootRunId", parent.runId(),
                "stepId", stepId, "role", "worker"));
    }

    private static TeamResumeState oneStepState(String type, List<String> tools) {
        return new TeamResumeState(true, 1, 1,
                List.of(resumeStep("step_1", "step", type, List.of(), tools)), "");
    }

    private static TeamStepResumeState resumeStep(String id, String description, String type,
                                                  List<String> dependencies, List<String> tools) {
        return new TeamStepResumeState(id, description, type, dependencies, tools,
                "", "low", "PENDING", "", 0, "", "", List.of());
    }

    private static void appendParentStartAndPlan(RunStore store, AgentRunContext parent,
                                                  TeamResumeState state) {
        store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_STARTED,
                Map.of("input", parent.input())));
        store.append(AgentRunEvent.of(parent, AgentRunEventType.TEAM_PLAN_DEFINED, Map.of(
                "schemaVersion", "1", "planVersion", "1",
                "planJson", new TeamCheckpointCodec().encodePlan(state))));
    }

    private static void appendCheckpoint(RunStore store, AgentRunContext parent, List<String> stepIds,
                                         String status, String phase, String childRunId,
                                         String result, String error) {
        store.append(AgentRunEvent.of(parent, AgentRunEventType.TEAM_STEP_CHECKPOINT, Map.of(
                "schemaVersion", "1", "planVersion", "1",
                "stepIdsJson", new TeamCheckpointCodec().encodeStepIds(stepIds),
                "stepStatus", status, "phase", phase, "attempt", "0",
                "childRunId", childRunId, "result", result, "error", error)));
    }

    private static void appendCompleteChildTool(RunStore store, AgentRunContext child,
                                                String id, String name, String arguments) {
        String calls = "[{\"id\":\"" + id + "\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":"
                + com.mindcli.platform.serialization.JsonSupport.mapper().valueToTree(arguments) + "}}]";
        store.append(AgentRunEvent.of(child, AgentRunEventType.RUN_STARTED, Map.of("phase", "execute")));
        store.append(AgentRunEvent.of(child, AgentRunEventType.LLM_RESPONSE, Map.of(
                "recordKind", "turn", "iteration", "1", "toolCallCount", "1", "toolCallsJson", calls)));
        store.append(AgentRunEvent.of(child, AgentRunEventType.TOOL_CALL_REQUESTED, Map.of(
                "recordKind", "turn", "iteration", "1", "toolCallCount", "1",
                "toolIds", id, "toolNames", name)));
        store.append(AgentRunEvent.of(child, AgentRunEventType.TOOL_OUTCOME, Map.of(
                "toolId", id, "toolName", name, "argumentsJson", arguments, "status", "COMPLETED")));
        store.append(AgentRunEvent.of(child, AgentRunEventType.RUN_FINISHED,
                Map.of("phase", "execute", "status", "SUCCESS")));
    }

    private static AgentRunResult resumeWithNoLlm(JsonlRunStore store, AgentRunContext parent,
                                                  Path workspace) {
        return new AgentRuntime(store).resume(parent.runId(),
                new TeamModeAdapter(teamAgent(ScriptedClient.sequence(), store, workspace)));
    }

    private static long resumeMarkerCount(RunStore store, String runId) {
        return store.events(runId).stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_RESUMED)
                .count();
    }

    private static void assertNoResumeMarker(RunStore store, String runId) {
        assertEquals(0, resumeMarkerCount(store, runId));
    }

    private static AgentOrchestrator teamAgent(LlmClient llm, RunStore store, Path workspace) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());
        MemoryManager memory = new MemoryManager(llm, llm.maxContextWindow(),
                new LongTermMemory(workspace.resolve(".eval-memory").toFile()));
        return new AgentOrchestrator(llm, registry, memory,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), store);
    }

    private static long childToolCount(RunStore store, String parentRunId, String toolId) {
        return store.events(parentRunId).stream()
                .filter(event -> event.type() == AgentRunEventType.TEAM_STEP_CHECKPOINT)
                .map(event -> event.attributes().getOrDefault("childRunId", ""))
                .filter(id -> !id.isBlank()).distinct()
                .flatMap(id -> store.events(id).stream())
                .filter(event -> event.type() == AgentRunEventType.TOOL_OUTCOME)
                .filter(event -> toolId.equals(event.attributes().get("toolId")))
                .filter(event -> "COMPLETED".equals(event.attributes().get("status")))
                .count();
    }

    private static final class CancelOnTeamCheckpointRunStore implements RunStore {
        private final RunStore delegate;
        private final CancellationToken token;
        private final String stepId;

        private CancelOnTeamCheckpointRunStore(RunStore delegate, CancellationToken token, String stepId) {
            this.delegate = delegate;
            this.token = token;
            this.stepId = stepId;
        }

        @Override
        public void append(AgentRunEvent event) {
            delegate.append(event);
            if (event.type() == AgentRunEventType.TEAM_STEP_CHECKPOINT
                    && "COMPLETED".equals(event.attributes().get("stepStatus"))) {
                List<String> ids = new TeamCheckpointCodec()
                        .decodeStepIds(event.attributes().get("stepIdsJson"));
                if (ids.contains(stepId)) token.cancel();
            }
        }

        @Override
        public List<AgentRunEvent> events(String runId) {
            return delegate.events(runId);
        }
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 10, 5);
    }

    private static LlmClient.ChatResponse toolResponse(String content, String id, String name,
                                                       String argumentsJson) {
        return new LlmClient.ChatResponse("assistant", content,
                List.of(new LlmClient.ToolCall(id,
                        new LlmClient.ToolCall.Function(name, argumentsJson))), 10, 5);
    }

    private static final class ScriptedClient implements LlmClient {
        private final Queue<LlmClient.ChatResponse> responses;

        private ScriptedClient(List<LlmClient.ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        private static ScriptedClient sequence(LlmClient.ChatResponse... responses) {
            return new ScriptedClient(List.of(responses));
        }

        @Override
        public LlmClient.ChatResponse chat(List<LlmClient.Message> messages,
                                           List<LlmClient.Tool> tools) throws IOException {
            return chat(messages, tools, LlmClient.StreamListener.NO_OP);
        }

        @Override
        public synchronized LlmClient.ChatResponse chat(List<LlmClient.Message> messages,
                                                        List<LlmClient.Tool> tools,
                                                        LlmClient.StreamListener listener) throws IOException {
            LlmClient.ChatResponse response = responses.poll();
            if (response == null) throw new IOException("unexpected LLM call");
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }

        @Override
        public String getProviderName() {
            return "eval";
        }

        @Override
        public String getModelName() {
            return "eval-scripted";
        }
    }
}
