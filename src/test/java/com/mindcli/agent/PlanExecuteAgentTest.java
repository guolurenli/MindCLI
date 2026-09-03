package com.mindcli.agent;

import com.mindcli.platform.llm.GLMClient;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.capability.memory.LongTermMemory;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.agent.plan.ExecutionPlan;
import com.mindcli.agent.plan.Planner;
import com.mindcli.agent.plan.Task;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.loop.AgentTurnKernel;
import com.mindcli.runtime.run.recovery.PlanResumeState;
import com.mindcli.runtime.run.recovery.PlanTaskResumeState;
import com.mindcli.runtime.CancellationContext;
import com.mindcli.runtime.CancellationToken;
import com.mindcli.runtime.run.store.InMemoryRunStore;
import com.mindcli.runtime.run.dispatch.ToolDispatcher;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void planAgentUsesSharedSingleTurnKernelSeam() throws Exception {
        Field field = PlanExecuteAgent.class.getDeclaredField("turnKernel");
        assertEquals(AgentTurnKernel.class, field.getType());
    }

    @Test
    void shouldNotRepeatStreamedTaskOutputInFinalPlanSummary() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.streamed(new LlmClient.ChatResponse(
                        "assistant",
                        "当前目录包含 8 个目录和 8 个文件。",
                        null,
                        60,
                        20
                ))
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("列出当前目录的文件");

        assertEquals("✅ 计划执行完成！", result);
    }

    @Test
    void shouldReportBlockedDependenciesWhenPlanCannotProceed() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of());
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new Planner(llmClient) {
                    @Override
                    public ExecutionPlan createPlan(String goal) {
                        ExecutionPlan plan = new ExecutionPlan("plan-test", goal);
                        plan.addTask(new Task("task_1", "完成准备", Task.TaskType.ANALYSIS));
                        plan.addTask(new Task("task_2", "继续执行", Task.TaskType.ANALYSIS, List.of("task_missing")));
                        plan.computeExecutionOrder();
                        return plan;
                    }
                },
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("测试缺失依赖");

        assertTrue(result.contains("MISSING"), "blocked dependency reason should be surfaced: " + result);
    }

    @Test
    void shouldNotPrintEmptyTaskReasoningHeadingAndShouldUseOutputLabel() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.scripted(
                        listener -> {
                            listener.onReasoningDelta("  \n");
                            listener.onContentDelta("我来读取 pom.xml 文件。");
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "我来读取 pom.xml 文件。",
                                "  \n",
                                null,
                                60,
                                20
                        )
                )
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            agent.run("读取 pom.xml");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("任务思考 [task_1]"),
                "空白 reasoning 不应打印空的任务思考标题: " + rendered);
        assertTrue(rendered.contains("任务输出 [task_1]"));
        assertFalse(rendered.contains("任务结果 [task_1]"),
                "tool-call 前后的流式 content 不应被误标成任务结果: " + rendered);
    }

    @Test
    void planToolCallsWaitForSharedDispatcherLocks() throws Exception {
        CountDownLatch lockEntered = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch planToolStarted = new CountDownLatch(1);
        CountDownLatch toolCallResponseDelivered = new CountDownLatch(1);
        AtomicBoolean planToolStartedBeforeRelease = new AtomicBoolean(false);
        ToolDispatcher lockHolder = new ToolDispatcher(new LockHoldingToolRegistry(lockEntered, releaseLock));
        RecordingToolRegistry registry = new RecordingToolRegistry(
                planToolStarted,
                releaseLock,
                planToolStartedBeforeRelease);
        registry.setProjectPath(tempDir.toString());
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.scripted(listener -> toolCallResponseDelivered.countDown(), new LlmClient.ChatResponse(
                        "assistant",
                        "准备写入文件",
                        null,
                        List.of(new LlmClient.ToolCall(
                                "call_plan",
                                new LlmClient.ToolCall.Function("write_file",
                                        "{\"path\":\"shared.txt\",\"content\":\"plan\"}")
                        )),
                        10,
                        5
                )),
                StubResponse.streamed(new LlmClient.ChatResponse(
                        "assistant",
                        "写入完成",
                        null,
                        null,
                        10,
                        5
                ))
        ));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                registry,
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );
        AgentRunContext lockContext = AgentRunContext.create(AgentMode.PLAN, "lock", tempDir.toString());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> lockFuture = executor.submit(() -> lockHolder.dispatch(List.of(
                    new LlmClient.ToolCall("call_lock", new LlmClient.ToolCall.Function(
                            "write_file", "{\"path\":\"shared.txt\",\"content\":\"lock\"}"))
            ), lockContext));
            assertTrue(lockEntered.await(5, TimeUnit.SECONDS));

            Future<String> planFuture = executor.submit(() -> agent.run("写入 shared.txt"));

            assertTrue(toolCallResponseDelivered.await(10, TimeUnit.SECONDS));
            assertFalse(planToolStarted.await(250, TimeUnit.MILLISECONDS),
                    "Plan tool execution must wait for the shared dispatcher lock");
            releaseLock.countDown();

            assertEquals("✅ 计划执行完成！", planFuture.get(10, TimeUnit.SECONDS));
            lockFuture.get(5, TimeUnit.SECONDS);
            assertTrue(planToolStarted.await(5, TimeUnit.SECONDS));
            assertFalse(planToolStartedBeforeRelease.get(),
                    "Plan tool execution must not enter ToolRegistry before the shared lock is released");
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void planToolCallsRecordStructuredToolOutcomeEvents() {
        RecordingToolRegistry registry = new RecordingToolRegistry(new CountDownLatch(1));
        registry.setProjectPath(tempDir.toString());
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.plain(new LlmClient.ChatResponse(
                        "assistant",
                        "准备写入文件",
                        null,
                        List.of(new LlmClient.ToolCall(
                                "call_plan",
                                new LlmClient.ToolCall.Function("write_file",
                                        "{\"path\":\"out.txt\",\"content\":\"plan\"}")
                        )),
                        10,
                        5
                )),
                StubResponse.streamed(new LlmClient.ChatResponse(
                        "assistant",
                        "写入完成",
                        null,
                        null,
                        10,
                        5
                ))
        ));
        InMemoryRunStore runStore = new InMemoryRunStore();
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                registry,
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                runStore
        );
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "写入 out.txt", tempDir.toString());

        agent.run(context, runStore);

        AgentRunEvent outcome = runStore.events(context.runId()).stream()
                .filter(event -> event.type() == AgentRunEventType.TOOL_OUTCOME)
                .findFirst()
                .orElseThrow();
        assertEquals("COMPLETED", outcome.attributes().get("status"));
        assertEquals("write_file", outcome.attributes().get("toolName"));
        assertEquals("call_plan", outcome.attributes().get("toolId"));
        assertEquals("ALLOW", outcome.attributes().get("hookDecision"));
        assertEquals("task_1", outcome.attributes().get("taskId"));
        assertTrue(outcome.attributes().get("lockKeys").contains("FILE:"));
    }

    @Test
    void recordsPlanDefinitionAndTaskBoundaryCheckpoints() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.streamed(new LlmClient.ChatResponse(
                        "assistant", "done", null, null, 10, 5))));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", tempDir.toString());

        String result = agent.run(context, runStore);

        assertTrue(result.startsWith("✅"), result);
        assertEquals(List.of(
                        AgentRunEventType.PLAN_DEFINED,
                        AgentRunEventType.PLAN_TASK_CHECKPOINT,
                        AgentRunEventType.PLAN_TASK_CHECKPOINT),
                runStore.events(context.runId()).stream()
                        .map(AgentRunEvent::type)
                        .filter(type -> type == AgentRunEventType.PLAN_DEFINED
                                || type == AgentRunEventType.PLAN_TASK_CHECKPOINT)
                        .toList());
        assertEquals(List.of("RUNNING", "COMPLETED"),
                runStore.events(context.runId()).stream()
                        .filter(event -> event.type() == AgentRunEventType.PLAN_TASK_CHECKPOINT)
                        .map(event -> event.attributes().get("taskStatus"))
                        .toList());
    }

    @Test
    void recoveredPlanSkipsCompletedTaskWithoutPlannerOrReview() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger reviewCalls = new AtomicInteger();
        InMemoryRunStore runStore = new InMemoryRunStore();
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.streamed(new LlmClient.ChatResponse(
                        "assistant", "second done", null, null, 10, 5))));
        Planner planner = new Planner(llmClient) {
            @Override
            public ExecutionPlan createPlan(String goal) {
                plannerCalls.incrementAndGet();
                throw new AssertionError("recovery must not create a new plan");
            }
        };
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                planner,
                null,
                (goal, plan) -> {
                    reviewCalls.incrementAndGet();
                    return PlanExecuteAgent.PlanReviewDecision.execute();
                },
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", tempDir.toString());
        PlanResumeState state = new PlanResumeState(
                true,
                3,
                "plan-resume",
                "goal",
                "two tasks",
                List.of(
                        resumeTask("task_1", List.of(), "COMPLETED", "first done"),
                        resumeTask("task_2", List.of("task_1"), "PENDING", "")),
                "");

        String result = agent.runRecovered(context, runStore, state);

        assertTrue(result.startsWith("✅"), result);
        assertEquals(0, plannerCalls.get());
        assertEquals(0, reviewCalls.get());
        assertEquals(List.of("task_2"),
                runStore.events(context.runId()).stream()
                        .filter(event -> event.type() == AgentRunEventType.PLAN_TASK_CHECKPOINT)
                        .filter(event -> "RUNNING".equals(event.attributes().get("taskStatus")))
                        .map(event -> event.attributes().get("taskId"))
                        .toList());
        assertTrue(runStore.events(context.runId()).stream()
                .noneMatch(event -> event.type() == AgentRunEventType.PLAN_DEFINED));
    }

    @Test
    void cancellationInsideTaskDoesNotWriteCompletedCheckpoint() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        CancellationToken token = CancellationContext.startRun();
        try {
            StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                    StubResponse.scripted(listener -> token.cancel(), new LlmClient.ChatResponse(
                            "assistant", "cancelled response", null, null, 10, 5))));
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    llmClient,
                    new ToolRegistry(),
                    new StubPlanner(llmClient),
                    null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    runStore);
            AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", tempDir.toString());

            String result = agent.run(context, runStore);

            assertTrue(result.startsWith("⏹"), result);
            assertEquals(List.of("RUNNING"), runStore.events(context.runId()).stream()
                    .filter(event -> event.type() == AgentRunEventType.PLAN_TASK_CHECKPOINT)
                    .map(event -> event.attributes().get("taskStatus"))
                    .toList());
        } finally {
            CancellationContext.clear(token);
        }
    }

    @Test
    void nonCriticalSkipDegradationSkipsFailedTaskEvenWithDownstream() throws Exception {
        FailsFirstThenSucceedsClient llmClient = new FailsFirstThenSucceedsClient();
        AtomicInteger replanCalls = new AtomicInteger();
        Task optionalTask = new Task("task_1", "读取可选背景信息", Task.TaskType.ANALYSIS);
        optionalTask.setCritical(false);
        optionalTask.setDegradation("SKIP");
        optionalTask.setMaxRetries(0);
        Task downstreamTask = new Task("task_2", "基于已有信息继续总结", Task.TaskType.ANALYSIS, List.of("task_1"));
        Planner planner = new Planner(llmClient) {
            @Override
            public ExecutionPlan createPlan(String goal) {
                ExecutionPlan plan = new ExecutionPlan("plan-skip", goal);
                plan.addTask(optionalTask);
                plan.addTask(downstreamTask);
                plan.computeExecutionOrder();
                return plan;
            }

            @Override
            public ExecutionPlan replanSubtree(ExecutionPlan plan, Task failedTask, String failureReason) {
                replanCalls.incrementAndGet();
                return new ExecutionPlan("plan-replanned", plan.getGoal());
            }
        };
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                planner,
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );

        String result = agent.run("测试可跳过节点");

        assertEquals(Task.TaskStatus.SKIPPED, optionalTask.getStatus());
        assertEquals(Task.TaskStatus.COMPLETED, downstreamTask.getStatus());
        assertEquals(0, replanCalls.get());
        assertTrue(result.startsWith("✅"), result);
    }

    private record StubResponse(LlmClient.ChatResponse response, boolean streamContent,
                                java.util.function.Consumer<LlmClient.StreamListener> streamScript) {
        private static StubResponse plain(LlmClient.ChatResponse response) {
            return new StubResponse(response, false, null);
        }

        private static StubResponse streamed(LlmClient.ChatResponse response) {
            return new StubResponse(response, true, null);
        }

        private static StubResponse scripted(java.util.function.Consumer<LlmClient.StreamListener> streamScript,
                                             LlmClient.ChatResponse response) {
            return new StubResponse(response, false, streamScript);
        }
    }

    private static final class StubPlanner extends Planner {
        private StubPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-test", goal);
            plan.addTask(new Task("task_1", "读取测试文件", Task.TaskType.FILE_READ));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static PlanTaskResumeState resumeTask(String id, List<String> dependencies,
                                                   String status, String result) {
        return new PlanTaskResumeState(
                id,
                "execute " + id,
                "ANALYSIS",
                dependencies,
                true,
                0,
                "BLOCK",
                List.of(),
                List.of(),
                "",
                "low",
                status,
                result,
                "",
                0);
    }

    private static final class RecordingToolRegistry extends ToolRegistry {
        private final CountDownLatch toolStarted;
        private final CountDownLatch releaseLock;
        private final AtomicBoolean toolStartedBeforeRelease;

        private RecordingToolRegistry(CountDownLatch toolStarted) {
            this(toolStarted, null, null);
        }

        private RecordingToolRegistry(CountDownLatch toolStarted,
                                      CountDownLatch releaseLock,
                                      AtomicBoolean toolStartedBeforeRelease) {
            this.toolStarted = toolStarted;
            this.releaseLock = releaseLock;
            this.toolStartedBeforeRelease = toolStartedBeforeRelease;
        }

        @Override
        public String executeTool(String name, String argumentsJson) {
            if (releaseLock != null && releaseLock.getCount() > 0 && toolStartedBeforeRelease != null) {
                toolStartedBeforeRelease.set(true);
            }
            toolStarted.countDown();
            return "文件已写入: shared.txt";
        }
    }

    private static final class LockHoldingToolRegistry extends ToolRegistry {
        private final CountDownLatch lockEntered;
        private final CountDownLatch releaseLock;

        private LockHoldingToolRegistry(CountDownLatch lockEntered, CountDownLatch releaseLock) {
            this.lockEntered = lockEntered;
            this.releaseLock = releaseLock;
        }

        @Override
        public com.mindcli.capability.tool.ToolExecution executeToolExecution(String name, String argumentsJson) {
            lockEntered.countDown();
            try {
                releaseLock.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return com.mindcli.capability.tool.ToolExecution.completed(
                    com.mindcli.capability.tool.ToolOutput.text("lock released"), argumentsJson);
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<StubResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses.stream().map(StubResponse::plain).toList());
        }

        private StubGLMClient(Queue<StubResponse> responses) {
            super("test-key");
            this.responses = responses;
        }

        private static StubGLMClient streaming(List<StubResponse> responses) {
            return new StubGLMClient(new ArrayDeque<>(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            StubResponse stubResponse = responses.poll();
            if (stubResponse == null) {
                throw new IOException("缺少预设响应");
            }
            if (stubResponse.streamScript() != null) {
                stubResponse.streamScript().accept(listener);
            } else if (stubResponse.streamContent() && stubResponse.response().content() != null) {
                listener.onContentDelta(stubResponse.response().content());
            }
            return stubResponse.response();
        }
    }

    private static final class FailsFirstThenSucceedsClient extends GLMClient {
        private int calls;

        private FailsFirstThenSucceedsClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            calls++;
            if (calls == 1) {
                throw new IOException("fatal optional step");
            }
            listener.onContentDelta("继续执行完成");
            return new ChatResponse("assistant", "继续执行完成", null, null, 10, 5);
        }
    }
}
