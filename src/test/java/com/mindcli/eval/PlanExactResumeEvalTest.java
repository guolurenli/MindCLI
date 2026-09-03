package com.mindcli.eval;

import com.mindcli.agent.PlanExecuteAgent;
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
import com.mindcli.runtime.run.mode.PlanModeAdapter;
import com.mindcli.runtime.run.recovery.PlanCheckpointCodec;
import com.mindcli.runtime.run.recovery.PlanResumeState;
import com.mindcli.runtime.run.recovery.PlanTaskResumeState;
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

class PlanExactResumeEvalTest {

    @Test
    void resumesSecondTaskAfterStoreReopenWithoutRepeatingFirstWrite(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path runsRoot = root.resolve("runs");
        AgentRunContext context = new AgentRunContext(
                "run-plan-exact-resume",
                AgentMode.PLAN,
                "write first.txt and then second.txt",
                workspace.toString(),
                Instant.now(),
                Map.of());
        JsonlRunStore initialStore = new JsonlRunStore(runsRoot);
        CancellationToken token = CancellationContext.startRun();
        AgentRunResult cancelled;
        try {
            RunStore cancellingStore = new CancelOnCompletedCheckpointRunStore(initialStore, token, "task_1");
            ScriptedClient initialLlm = ScriptedClient.steps(
                    step(response("""
                            {"schemaVersion":2,"summary":"two dependent writes","tasks":[
                              {"id":"first","description":"write first.txt","type":"FILE_WRITE","dependencies":[]},
                              {"id":"second","description":"write second.txt","type":"FILE_WRITE","dependencies":["first"]}
                            ]}
                            """)),
                    step(toolResponse("writing first", "write_first", "write_file",
                            "{\"path\":\"first.txt\",\"content\":\"once\"}")),
                    step(response("first complete")));

            cancelled = new AgentRuntime(cancellingStore).run(
                    context,
                    new PlanModeAdapter(planAgent(initialLlm, cancellingStore, workspace)));
        } finally {
            CancellationContext.clear(token);
        }

        assertEquals(AgentRunStatus.CANCELLED, cancelled.status());
        assertEquals("once", Files.readString(workspace.resolve("first.txt"), StandardCharsets.UTF_8));

        JsonlRunStore reopenedStore = new JsonlRunStore(runsRoot);
        ScriptedClient resumeLlm = ScriptedClient.steps(
                step(toolResponse("writing second", "write_second", "write_file",
                        "{\"path\":\"second.txt\",\"content\":\"done\"}")),
                step(response("second complete")));

        AgentRunResult resumed = new AgentRuntime(reopenedStore).resume(
                context.runId(),
                new PlanModeAdapter(planAgent(resumeLlm, reopenedStore, workspace)));

        assertEquals(AgentRunStatus.SUCCESS, resumed.status(), resumed.errorMessage());
        assertEquals(context.runId(), resumed.runId());
        assertEquals("once", Files.readString(workspace.resolve("first.txt"), StandardCharsets.UTF_8));
        assertEquals("done", Files.readString(workspace.resolve("second.txt"), StandardCharsets.UTF_8));
        assertEquals(1, completedToolCount(reopenedStore, context.runId(), "write_first"));
        assertEquals(1, completedToolCount(reopenedStore, context.runId(), "write_second"));
        assertEquals(1, reopenedStore.events(context.runId()).stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_RESUMED)
                .count());
        assertEquals(List.of("task_2"), reopenedStore.events(context.runId()).stream()
                .filter(event -> event.type() == AgentRunEventType.PLAN_TASK_CHECKPOINT)
                .filter(event -> "RUNNING".equals(event.attributes().get("taskStatus")))
                .filter(event -> event.seq() > firstResumeSeq(reopenedStore, context.runId()))
                .map(event -> event.attributes().get("taskId"))
                .toList());
    }

    @Test
    void refusesAmbiguousSuccessfulWriteAfterRunningCheckpoint(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path runsRoot = root.resolve("runs");
        AgentRunContext context = new AgentRunContext(
                "run-plan-ambiguous-write",
                AgentMode.PLAN,
                "write a file",
                workspace.toString(),
                Instant.now(),
                Map.of());
        JsonlRunStore initialStore = new JsonlRunStore(runsRoot);
        PlanResumeState state = new PlanResumeState(
                true, 1, "plan-ambiguous", context.input(), "one write",
                List.of(resumeTask("task_1", "RUNNING")), "");
        initialStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                Map.of("input", context.input())));
        initialStore.append(AgentRunEvent.of(context, AgentRunEventType.PLAN_DEFINED, Map.of(
                "planVersion", "1", "reason", "INITIAL",
                "planJson", new PlanCheckpointCodec().encode(state))));
        initialStore.append(AgentRunEvent.of(context, AgentRunEventType.PLAN_TASK_CHECKPOINT, Map.of(
                "planVersion", "1", "taskId", "task_1", "taskStatus", "RUNNING",
                "result", "", "error", "", "retryCount", "0")));
        initialStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, Map.of(
                "taskId", "task_1", "toolId", "write_1", "toolName", "write_file",
                "status", "COMPLETED", "text", "written")));
        initialStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        JsonlRunStore reopenedStore = new JsonlRunStore(runsRoot);
        AgentRunResult result = new AgentRuntime(reopenedStore).resume(
                context.runId(),
                new PlanModeAdapter(planAgent(ScriptedClient.steps(), reopenedStore, workspace)));

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("副作用"), result.errorMessage());
        assertFalse(reopenedStore.events(context.runId()).stream()
                .anyMatch(event -> event.type() == AgentRunEventType.RUN_RESUMED));
    }

    private static PlanExecuteAgent planAgent(LlmClient llm, RunStore store, Path workspace) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());
        MemoryManager memoryManager = new MemoryManager(
                llm,
                llm.maxContextWindow(),
                new LongTermMemory(workspace.resolve(".eval-memory").toFile()));
        return new PlanExecuteAgent(
                llm,
                registry,
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                store);
    }

    private static PlanTaskResumeState resumeTask(String id, String status) {
        return new PlanTaskResumeState(
                id, "write file", "FILE_WRITE", List.of(), true, 0, "BLOCK",
                List.of(), List.of("write_file"), "", "high", status, "", "", 0);
    }

    private static long completedToolCount(RunStore store, String runId, String toolId) {
        return store.events(runId).stream()
                .filter(event -> event.type() == AgentRunEventType.TOOL_OUTCOME)
                .filter(event -> toolId.equals(event.attributes().get("toolId")))
                .filter(event -> "COMPLETED".equals(event.attributes().get("status")))
                .count();
    }

    private static long firstResumeSeq(RunStore store, String runId) {
        return store.events(runId).stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_RESUMED)
                .mapToLong(AgentRunEvent::seq)
                .findFirst()
                .orElseThrow();
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 10, 5);
    }

    private static LlmClient.ChatResponse toolResponse(String content, String id, String name,
                                                       String argumentsJson) {
        return new LlmClient.ChatResponse(
                "assistant",
                content,
                List.of(new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, argumentsJson))),
                10,
                5);
    }

    private static Step step(LlmClient.ChatResponse response) {
        return new Step(response, () -> { });
    }

    private static Step step(LlmClient.ChatResponse response, Runnable afterResponse) {
        return new Step(response, afterResponse);
    }

    private record Step(LlmClient.ChatResponse response, Runnable afterResponse) {
    }

    private static final class CancelOnCompletedCheckpointRunStore implements RunStore {
        private final RunStore delegate;
        private final CancellationToken token;
        private final String taskId;

        private CancelOnCompletedCheckpointRunStore(RunStore delegate, CancellationToken token, String taskId) {
            this.delegate = delegate;
            this.token = token;
            this.taskId = taskId;
        }

        @Override
        public void append(AgentRunEvent event) {
            delegate.append(event);
            if (event.type() == AgentRunEventType.PLAN_TASK_CHECKPOINT
                    && taskId.equals(event.attributes().get("taskId"))
                    && "COMPLETED".equals(event.attributes().get("taskStatus"))) {
                token.cancel();
            }
        }

        @Override
        public List<AgentRunEvent> events(String runId) {
            return delegate.events(runId);
        }
    }

    private static final class ScriptedClient implements LlmClient {
        private final Queue<Step> steps;

        private ScriptedClient(List<Step> steps) {
            this.steps = new ArrayDeque<>(steps);
        }

        private static ScriptedClient steps(Step... steps) {
            return new ScriptedClient(List.of(steps));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools,
                                              StreamListener listener) throws IOException {
            Step step = steps.poll();
            if (step == null) throw new IOException("unexpected LLM call");
            ChatResponse response = step.response();
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            step.afterResponse().run();
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
