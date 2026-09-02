package com.mindcli.agent.team;

import com.mindcli.agent.AgentMessage;
import com.mindcli.agent.AgentRole;
import com.mindcli.agent.profile.AgentProfile;
import com.mindcli.platform.llm.GLMClient;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.store.RunStore;
import com.mindcli.runtime.run.dispatch.ToolDispatcher;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldEnableToolsForBuiltinExplorerAndWorker() throws Exception {
        assertTrue(invokeShouldUseTools(new SubAgent(AgentProfile.builtinExplorer("explorer#1"),
                new GLMClient("test-key"), new ToolRegistry())));
        assertTrue(invokeShouldUseTools(new SubAgent(AgentProfile.builtinWorker("worker#1"),
                new GLMClient("test-key"), new ToolRegistry())));
    }

    @Test
    void profilePolicyDenialsAreRecordedWithSubAgentMetadata() {
        MultiCallStreamClient llm = new MultiCallStreamClient(List.of(
                new CallScript(
                        listener -> listener.onContentDelta("尝试写入"),
                        new LlmClient.ChatResponse(
                                "assistant",
                                "尝试写入",
                                null,
                                List.of(new LlmClient.ToolCall(
                                        "call_denied",
                                        new LlmClient.ToolCall.Function("write_file",
                                                "{\"path\":\"blocked.txt\",\"content\":\"x\"}")
                                )),
                                10,
                                5
                        )
                ),
                new CallScript(
                        listener -> listener.onContentDelta("无法写入，权限不足"),
                        new LlmClient.ChatResponse(
                                "assistant",
                                "无法写入，权限不足",
                                null,
                                null,
                                10,
                                5
                        )
                )
        ));
        AgentProfile reader = new AgentProfile(
                "code-reader",
                AgentRole.WORKER,
                "只读分析",
                List.of("read_file"),
                List.of(),
                List.of(),
                "auto",
                1,
                "READ_ONLY",
                "PARENT_SUMMARY",
                "balanced",
                "",
                "on-request");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        SubAgent worker = new SubAgent(reader, llm, registry);
        RecordingRunStore runStore = new RecordingRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "task", tempDir.toString());

        worker.executeWithRunContext(
                AgentMessage.task("orchestrator", "写入 blocked.txt"),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                context,
                runStore);

        AgentRunEvent outcome = runStore.allEvents().stream()
                .filter(event -> event.type() == AgentRunEventType.TOOL_OUTCOME)
                .findFirst()
                .orElseThrow();
        assertEquals("DENIED_BY_POLICY", outcome.attributes().get("status"));
        assertEquals("code-reader", outcome.attributes().get("profileName"));
        assertEquals("READ_ONLY", outcome.attributes().get("permissionMode"));
        assertEquals("DENY", outcome.attributes().get("policyDecision"));
    }

    @Test
    void reviewBlocksMutatingToolsAtProgramLevel() {
        List<String> dispatched = new java.util.ArrayList<>();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public com.mindcli.capability.tool.ToolExecution executeToolExecution(
                    String name, String argumentsJson) {
                dispatched.add(name);
                return com.mindcli.capability.tool.ToolExecution.completed(
                        com.mindcli.capability.tool.ToolOutput.text("ok"), argumentsJson);
            }
        };
        registry.setProjectPath(tempDir.toString());
        MultiCallStreamClient llm = new MultiCallStreamClient(List.of(
                new CallScript(
                        listener -> {},
                        new LlmClient.ChatResponse(
                                "assistant",
                                "自审中",
                                null,
                                List.of(
                                        new LlmClient.ToolCall("call_w", new LlmClient.ToolCall.Function(
                                                "write_file", "{\"path\":\"x.txt\",\"content\":\"x\"}")),
                                        new LlmClient.ToolCall("call_r", new LlmClient.ToolCall.Function(
                                                "read_file", "{\"path\":\"x.txt\"}"))
                                ),
                                10,
                                5
                        )
                ),
                new CallScript(
                        listener -> listener.onContentDelta("{\"approved\":true}"),
                        new LlmClient.ChatResponse(
                                "assistant",
                                "{\"approved\":true}",
                                null,
                                null,
                                10,
                                5
                        )
                )
        ));
        SubAgent worker = new SubAgent(AgentProfile.builtinWorker("w-review"), llm, registry);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "review", tempDir.toString());

        worker.review("原始任务", "执行结果", ps, context, null);

        assertFalse(dispatched.contains("write_file"),
                "自审阶段 write_file 应被程序级拦截，不进 ToolRegistry");
        assertTrue(dispatched.contains("read_file"),
                "自审阶段只读工具 read_file 应正常执行");
    }

    @Test
    void shouldRouteLateReasoningToSupplementalSection() {
        // 模拟服务器先下发 content、再追加 reasoning 的情况
        ScriptedStreamClient llm = new ScriptedStreamClient(listener -> {
            listener.onContentDelta("最终答案内容");
            listener.onReasoningDelta("这段思考在答案之后才到");
        });
        SubAgent worker = new SubAgent(AgentProfile.builtinWorker("test-worker"), llm, new ToolRegistry());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        worker.execute(AgentMessage.task("orchestrator", "随便任务"), ps);
        ps.flush();

        String output = baos.toString(StandardCharsets.UTF_8);
        int contentHeadingIdx = output.indexOf("执行输出");
        int contentBodyIdx = output.indexOf("最终答案内容");
        int supplementalHeadingIdx = output.indexOf("补充思考");
        int lateReasoningIdx = output.indexOf("这段思考在答案之后才到");

        assertTrue(contentHeadingIdx >= 0, "执行输出 heading should appear: " + output);
        assertTrue(contentBodyIdx > contentHeadingIdx, "content body should be under 执行输出");
        assertTrue(supplementalHeadingIdx > contentBodyIdx,
                "late reasoning must appear under 补充思考 heading AFTER content, not mixed in");
        assertTrue(lateReasoningIdx > supplementalHeadingIdx,
                "late reasoning body should follow 补充思考 heading");
    }

    @Test
    void shouldPrintFreshHeadingsAcrossToolIterations() {
        // 两轮迭代：第一轮 content + tool_call（narration），第二轮纯 content（final answer）
        // resetBetweenIterations 被调用后，第二轮应该重新打印「执行思考」和「执行输出」标题
        MultiCallStreamClient llm = new MultiCallStreamClient(List.of(
                // 迭代 1：reasoning + content narration + tool_call
                new CallScript(
                        listener -> {
                            listener.onReasoningDelta("准备调用工具……");
                            listener.onContentDelta("我来调用 list_dir 工具");
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "我来调用 list_dir 工具",
                                "准备调用工具……",
                                List.of(new LlmClient.ToolCall(
                                        "call_1",
                                        new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}")
                                )),
                                10, 5
                        )
                ),
                // 迭代 2：reasoning + content（最终答案），无 tool_call
                new CallScript(
                        listener -> {
                            listener.onReasoningDelta("分析完成");
                            listener.onContentDelta("目录列出完毕");
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "目录列出完毕",
                                "分析完成",
                                null,
                                8, 3
                        )
                )
        ));
        SubAgent worker = new SubAgent(AgentProfile.builtinWorker("w1"), llm, new ToolRegistry());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        worker.execute(AgentMessage.task("orchestrator", "任务"), ps);
        ps.flush();

        String output = baos.toString(StandardCharsets.UTF_8);
        // 两轮各自打印一次「执行思考」
        int firstReasoning = output.indexOf("执行思考");
        int secondReasoning = firstReasoning < 0 ? -1 : output.indexOf("执行思考", firstReasoning + 1);
        assertTrue(firstReasoning >= 0, "第一轮应打印执行思考标题");
        assertTrue(secondReasoning > firstReasoning,
                "工具执行后第二轮应重新打印执行思考标题，实际输出：\n" + output);

        // 「执行输出」同样出现两次
        int firstContent = output.indexOf("执行输出");
        int secondContent = firstContent < 0 ? -1 : output.indexOf("执行输出", firstContent + 1);
        assertTrue(firstContent >= 0, "第一轮应打印执行输出标题");
        assertTrue(secondContent > firstContent,
                "工具执行后第二轮应重新打印执行输出标题，实际输出：\n" + output);
    }

    @Test
    void shouldNotEmitEmptyReasoningHeadingForWhitespaceDeltas() {
        // 仅下发空白 reasoning 然后下发 content —— 不能产生空的"执行思考"标题
        ScriptedStreamClient llm = new ScriptedStreamClient(listener -> {
            listener.onReasoningDelta("  ");
            listener.onReasoningDelta("\n");
            listener.onContentDelta("答案");
        });
        SubAgent worker = new SubAgent(AgentProfile.builtinWorker("test-worker"), llm, new ToolRegistry());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        worker.execute(AgentMessage.task("orchestrator", "随便任务"), ps);
        ps.flush();

        String output = baos.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("执行思考"),
                "whitespace-only reasoning should not produce an empty reasoning heading: " + output);
        assertTrue(output.contains("执行输出"), "content heading should still appear");
        assertTrue(output.contains("答案"), "content should still appear");
    }

    @Test
    void workerToolCallsWaitForSharedDispatcherLocks() throws Exception {
        CountDownLatch lockEntered = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch workerToolStarted = new CountDownLatch(1);
        CountDownLatch toolCallResponseDelivered = new CountDownLatch(1);
        AtomicBoolean workerToolStartedBeforeRelease = new AtomicBoolean(false);
        ToolDispatcher lockHolder = new ToolDispatcher(new LockHoldingToolRegistry(lockEntered, releaseLock));
        RecordingToolRegistry registry = new RecordingToolRegistry(
                workerToolStarted,
                releaseLock,
                workerToolStartedBeforeRelease);
        registry.setProjectPath(tempDir.toString());
        MultiCallStreamClient llm = new MultiCallStreamClient(List.of(
                new CallScript(
                        listener -> {
                            listener.onContentDelta("准备写入");
                            toolCallResponseDelivered.countDown();
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "准备写入",
                                null,
                                List.of(new LlmClient.ToolCall(
                                        "call_worker",
                                        new LlmClient.ToolCall.Function("write_file",
                                                "{\"path\":\"shared.txt\",\"content\":\"worker\"}")
                                )),
                                10,
                                5
                        )
                ),
                new CallScript(
                        listener -> listener.onContentDelta("写入完成"),
                        new LlmClient.ChatResponse(
                                "assistant",
                                "写入完成",
                                null,
                                null,
                                10,
                                5
                        )
                )
        ));
        SubAgent worker = new SubAgent(AgentProfile.builtinWorker("w-lock"), llm, registry);
        AgentRunContext lockContext = AgentRunContext.create(AgentMode.TEAM, "lock", tempDir.toString());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> lockFuture = executor.submit(() -> lockHolder.dispatch(List.of(
                    new LlmClient.ToolCall("call_lock", new LlmClient.ToolCall.Function(
                            "write_file", "{\"path\":\"shared.txt\",\"content\":\"lock\"}"))
            ), lockContext));
            assertTrue(lockEntered.await(5, TimeUnit.SECONDS));

            Future<AgentMessage> workerFuture = executor.submit(() -> {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(output, true, StandardCharsets.UTF_8);
                return worker.execute(AgentMessage.task("orchestrator", "写入 shared.txt"), ps);
            });

            assertTrue(toolCallResponseDelivered.await(5, TimeUnit.SECONDS));
            assertFalse(workerToolStarted.await(250, TimeUnit.MILLISECONDS),
                    "SubAgent worker tool execution must wait for the shared dispatcher lock");
            releaseLock.countDown();

            AgentMessage result = workerFuture.get(10, TimeUnit.SECONDS);
            assertEquals(AgentMessage.Type.RESULT, result.type());
            lockFuture.get(5, TimeUnit.SECONDS);
            assertTrue(workerToolStarted.await(5, TimeUnit.SECONDS));
            assertFalse(workerToolStartedBeforeRelease.get(),
                    "SubAgent worker tool execution must not enter ToolRegistry before the shared lock is released");
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    private boolean invokeShouldUseTools(SubAgent agent) throws Exception {
        Method method = SubAgent.class.getDeclaredMethod("shouldUseTools");
        method.setAccessible(true);
        return (boolean) method.invoke(agent);
    }

    private static final class RecordingRunStore implements RunStore {
        private final List<AgentRunEvent> events = new java.util.ArrayList<>();

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

    /**
     * 可编排 delta 下发顺序的 stub GLM 客户端，用于测试流式渲染在异常顺序下的行为。
     */
    private static final class ScriptedStreamClient extends GLMClient {
        private final Consumer<StreamListener> script;

        private ScriptedStreamClient(Consumer<StreamListener> script) {
            super("test-key");
            this.script = script;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            script.accept(listener);
            // 返回空 toolCalls 让 SubAgent 作为最终结果返回
            return new ChatResponse("assistant", "最终答案内容", "这段思考在答案之后才到", null, 10, 5);
        }
    }

    /**
     * 多轮次脚本：每次 chat() 调用按顺序消费一条 CallScript，支持测试 tool-call 分支的后续迭代。
     */
    private record CallScript(Consumer<LlmClient.StreamListener> streamScript, LlmClient.ChatResponse response) {}

    private static final class MultiCallStreamClient extends GLMClient {
        private final java.util.Iterator<CallScript> iter;

        private MultiCallStreamClient(List<CallScript> scripts) {
            super("test-key");
            this.iter = scripts.iterator();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            if (!iter.hasNext()) {
                throw new IOException("脚本已耗尽，未预设第 N 次调用");
            }
            CallScript next = iter.next();
            next.streamScript().accept(listener);
            return next.response();
        }
    }
}
