package com.mindcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mindcli.platform.llm.GLMClient;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.capability.memory.LongTermMemory;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.InMemoryRunStore;
import com.mindcli.runtime.run.JsonlRunStore;
import com.mindcli.runtime.run.RunStore;
import com.mindcli.capability.tool.ToolRegistry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.stream.Collectors.toSet;

class AgentOrchestratorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldParseSimplePlan() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "summary": "读取文件",
                    "steps": [
                        {
                            "id": "step_1",
                            "description": "读取 pom.xml",
                            "type": "FILE_READ",
                            "dependencies": []
                        }
                    ]
                }
                """;

        List<ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
        assertEquals("step_1", steps.get(0).id());
        assertEquals("读取 pom.xml", steps.get(0).description());
    }

    @Test
    void shouldParseMultiStepPlanWithDependencies() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "summary": "创建并验证项目",
                    "steps": [
                        {
                            "id": "s1",
                            "description": "创建项目",
                            "type": "COMMAND",
                            "dependencies": []
                        },
                        {
                            "id": "s2",
                            "description": "读取 pom.xml",
                            "type": "FILE_READ",
                            "dependencies": ["s1"]
                        },
                        {
                            "id": "s3",
                            "description": "验证结构",
                            "type": "VERIFICATION",
                            "dependencies": ["s2"]
                        }
                    ]
                }
                """;

        List<ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(3, steps.size());

        // 验证重编号
        assertEquals("step_1", steps.get(0).id());
        assertEquals("step_2", steps.get(1).id());
        assertEquals("step_3", steps.get(2).id());

        // 验证依赖被正确映射
        assertTrue(steps.get(0).dependencies().isEmpty());
        assertEquals(List.of("step_1"), steps.get(1).dependencies());
        assertEquals(List.of("step_2"), steps.get(2).dependencies());
    }

    @Test
    void shouldParsePlanWithMarkdownCodeBlock() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                ```json
                {
                    "summary": "简单任务",
                    "steps": [
                        {
                            "id": "t1",
                            "description": "执行命令",
                            "type": "COMMAND",
                            "dependencies": []
                        }
                    ]
                }
                ```
                """;

        List<ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
    }

    @Test
    void shouldParsePlanWithTasksField() {
        // 兼容 "tasks" 字段（Plan-and-Execute 的格式）
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "summary": "用 tasks 字段",
                    "tasks": [
                        {
                            "id": "task_1",
                            "description": "第一步",
                            "type": "COMMAND",
                            "dependencies": []
                        }
                    ]
                }
                """;

        List<ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
        assertEquals("第一步", steps.get(0).description());
    }

    @Test
    void shouldCarryProfileRequirementsFromPlanSchema() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "schemaVersion": 3,
                    "summary": "写代码",
                    "tasks": [
                        {
                            "id": "task_1",
                            "description": "修改文件",
                            "type": "FILE_WRITE",
                            "dependencies": [],
                            "requiredTools": ["read_file", "write_file"],
                            "preferredAgent": "code-writer",
                            "riskLevel": "medium"
                        }
                    ]
                }
                """;

        List<ExecutionStep> steps = orchestrator.parsePlan(planJson);

        assertEquals(List.of("read_file", "write_file"), steps.get(0).requiredTools());
        assertEquals("code-writer", steps.get(0).preferredAgent());
        assertEquals("medium", steps.get(0).riskLevel());
    }

    @Test
    void shouldRejectUnknownTaskType() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "summary": "非法类型",
                    "tasks": [
                        {
                            "id": "task_1",
                            "description": "读取文件",
                            "type": "MAGIC",
                            "dependencies": []
                        }
                    ]
                }
                """;

        assertTrue(orchestrator.parsePlan(planJson).isEmpty());
    }

    @Test
    void shouldReturnEmptyListForInvalidJson() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        assertTrue(orchestrator.parsePlan("").isEmpty());
        assertTrue(orchestrator.parsePlan("not json").isEmpty());
        assertTrue(orchestrator.parsePlan("{}").isEmpty());
        assertTrue(orchestrator.parsePlan("{\"steps\": []}").isEmpty());
    }

    @Test
    void shouldGetExecutableSteps() {
        TeamScheduler scheduler = new TeamScheduler();

        // step_1 无依赖，step_2 依赖 step_1
        List<ExecutionStep> steps = new ArrayList<>(List.of(
                ExecutionStep.pending("step_1", "创建项目", "COMMAND", List.of()),
                ExecutionStep.pending("step_2", "验证结构", "VERIFICATION", List.of("step_1"))
        ));

        // 只有 step_1 可执行
        List<ExecutionStep> executable = leadersOf(scheduler.nextWave(steps));
        assertEquals(1, executable.size());
        assertEquals("step_1", executable.get(0).id());

        // 完成 step_1 后 step_2 可执行
        steps.set(0, steps.get(0).withResult("项目已创建"));
        executable = leadersOf(scheduler.nextWave(steps));
        assertEquals(1, executable.size());
        assertEquals("step_2", executable.get(0).id());
    }

    @Test
    void shouldGetMultipleExecutableStepsForParallelTasks() {
        TeamScheduler scheduler = new TeamScheduler();

        List<ExecutionStep> steps = List.of(
                ExecutionStep.pending("step_1", "任务A", "COMMAND", List.of()),
                ExecutionStep.pending("step_2", "任务B", "COMMAND", List.of()),
                ExecutionStep.pending("step_3", "汇总", "ANALYSIS", List.of("step_1", "step_2"))
        );

        List<ExecutionStep> executable = leadersOf(scheduler.nextWave(steps));
        assertEquals(2, executable.size());
    }

    private static List<ExecutionStep> leadersOf(ScheduleWave wave) {
        List<ExecutionStep> leaders = new ArrayList<>();
        wave.readOnly().forEach(group -> leaders.add(group.leader()));
        wave.mutating().forEach(group -> leaders.add(group.leader()));
        return leaders;
    }

    @Test
    void shouldDeduplicateIdenticalReadyWriteSteps(@TempDir Path tempDir) throws Exception {
        AtomicInteger workerCalls = new AtomicInteger();
        AtomicInteger reviewCalls = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "重复写入任务",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "更新 README",
                              "type": "FILE_WRITE",
                              "dependencies": []
                            },
                            {
                              "id": "s2",
                              "description": "更新 README",
                              "type": "FILE_WRITE",
                              "dependencies": []
                            }
                          ]
                        }
                        """);
            }
            if (body.contains("原始任务：")) {
                reviewCalls.incrementAndGet();
                return response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """);
            }
            if (body.contains("当前任务：更新 README")) {
                workerCalls.incrementAndGet();
                return response("README 已更新");
            }
            return response("fallback");
        };

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new DispatchingStubGLMClient(dispatcher),
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new InMemoryRunStore()
        );

        String finalResult = orchestrator.run("测试重复写入去重");

        assertTrue(finalResult.contains("多 Agent 协作任务完成"), finalResult);
        assertEquals(1, workerCalls.get(), "duplicate write steps should reuse one worker execution");
        assertEquals(1, reviewCalls.get(), "duplicate write steps should reuse one review");
        assertTrue(finalResult.contains("[step_1] ✅ 更新 README"), finalResult);
        assertTrue(finalResult.contains("[step_2] ✅ 更新 README"), finalResult);
    }

    @Test
    void mutatingStepsFallBackToSerialWhenNotGitRepo(@TempDir Path tempDir) throws Exception {
        CountDownLatch firstWorkerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);
        CountDownLatch secondWorkerStarted = new CountDownLatch(1);
        AtomicBoolean secondStartedBeforeRelease = new AtomicBoolean(false);
        AtomicInteger peakConcurrency = new AtomicInteger();
        AtomicInteger currentConcurrency = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "schemaVersion": 3,
                          "summary": "两步写入",
                          "tasks": [
                            {"id": "a", "description": "更新 A 文件", "type": "FILE_WRITE", "dependencies": []},
                            {"id": "b", "description": "更新 B 文件", "type": "FILE_WRITE", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("原始任务：")) {
                return response("{\"approved\": true, \"summary\": \"通过\", \"issues\": []}");
            }
            if (body.contains("当前任务：更新 A 文件")) {
                return waitForReleaseThenReturn(firstWorkerStarted, releaseFirstWorker,
                        currentConcurrency, peakConcurrency, response("A 已更新"));
            }
            if (body.contains("当前任务：更新 B 文件")) {
                secondStartedBeforeRelease.set(releaseFirstWorker.getCount() > 0);
                secondWorkerStarted.countDown();
                return response("B 已更新");
            }
            return response("fallback");
        };

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString()); // 非 git 目录
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new DispatchingStubGLMClient(dispatcher),
                registry,
                new NoOpMemoryManager(tempDir.toFile()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new InMemoryRunStore()
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> runFuture = executor.submit(() -> orchestrator.run("测试非 git 目录回退串行"));

            assertTrue(firstWorkerStarted.await(10, TimeUnit.SECONDS), "first worker should start");
            assertFalse(secondWorkerStarted.await(300, TimeUnit.MILLISECONDS),
                    "second worker should not start before first releases");
            assertFalse(secondStartedBeforeRelease.get(), "second worker must not start concurrently");

            releaseFirstWorker.countDown();

            assertTrue(secondWorkerStarted.await(5, TimeUnit.SECONDS),
                    "second worker should start after first finishes");
            String finalResult = runFuture.get(10, TimeUnit.SECONDS);
            assertTrue(finalResult.contains("多 Agent 协作任务完成"), finalResult);
            assertEquals(1, peakConcurrency.get(), "非 git 目录下写入步骤应回退串行");
        } finally {
            releaseFirstWorker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void mutatingStepsRunInParallelViaWorktreeIsolation(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        initGitRepo(project);

        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        AtomicInteger peakConcurrency = new AtomicInteger();
        AtomicInteger currentConcurrency = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "schemaVersion": 3,
                          "summary": "两步写入",
                          "tasks": [
                            {"id": "a", "description": "更新 A 文件", "type": "FILE_WRITE", "dependencies": []},
                            {"id": "b", "description": "更新 B 文件", "type": "FILE_WRITE", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("原始任务：")) {
                return response("{\"approved\": true, \"summary\": \"通过\", \"issues\": []}");
            }
            if (body.contains("当前任务：更新 A 文件") || body.contains("当前任务：更新 B 文件")) {
                return waitForReleaseThenReturn(workersReady, releaseWorkers,
                        currentConcurrency, peakConcurrency, response("done"));
            }
            return response("fallback");
        };

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(project.toString());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new DispatchingStubGLMClient(dispatcher),
                registry,
                new NoOpMemoryManager(project.toFile()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new InMemoryRunStore()
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> runFuture = executor.submit(() -> orchestrator.run("测试 worktree 并行写入"));

            assertTrue(workersReady.await(10, TimeUnit.SECONDS),
                    "both workers should reach chat() concurrently, current=" + currentConcurrency.get());
            assertEquals(2, currentConcurrency.get(), "both workers should be waiting before release");
            releaseWorkers.countDown();

            String finalResult = runFuture.get(15, TimeUnit.SECONDS);
            assertTrue(finalResult.contains("多 Agent 协作任务完成"), finalResult);
            assertEquals(2, peakConcurrency.get(), "无依赖的写入步骤应通过 worktree 隔离并行执行");
        } finally {
            releaseWorkers.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldParseReviewApproval() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        // 正常通过的 JSON
        assertTrue(orchestrator.parseReviewApproval(
                "{\"approved\": true, \"summary\": \"通过\", \"issues\": []}"));

        // 未通过的 JSON
        assertFalse(orchestrator.parseReviewApproval(
                "{\"approved\": false, \"summary\": \"未通过\", \"issues\": [\"缺少错误处理\"]}"));

        // null 或空内容采取保守策略：默认不通过
        assertFalse(orchestrator.parseReviewApproval(null));
        assertFalse(orchestrator.parseReviewApproval(""));

        // 含否定关键词的纯文本
        assertFalse(orchestrator.parseReviewApproval("执行结果未通过审查"));
        assertFalse(orchestrator.parseReviewApproval("代码质量不合格"));

        // 含肯定关键词的非 JSON 文本
        assertTrue(orchestrator.parseReviewApproval("审查通过，代码质量良好"));

        // 既无肯定关键词也无 JSON：保守判为不通过
        assertFalse(orchestrator.parseReviewApproval("hmm"));

        // JSON 缺少 approved 字段：保守判为不通过
        assertFalse(orchestrator.parseReviewApproval("{\"summary\": \"无 approved 字段\"}"));
    }

    @Test
    void shouldParseReviewIssues() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        String reviewJson = """
                {
                    "approved": false,
                    "summary": "存在问题",
                    "issues": ["缺少错误处理", "代码风格不一致"],
                    "suggestions": ["添加 try-catch", "统一缩进"]
                }
                """;

        String issues = orchestrator.parseReviewIssues(reviewJson);
        assertTrue(issues.contains("缺少错误处理"));
        assertTrue(issues.contains("代码风格不一致"));
    }

    @Test
    void shouldFallbackToSummaryForIssues() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        String reviewJson = "{\"approved\": false, \"summary\": \"质量不达标\", \"issues\": []}";
        String issues = orchestrator.parseReviewIssues(reviewJson);
        assertEquals("质量不达标", issues);
    }

    @Test
    void shouldHandleInvalidReviewJson() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String issues = orchestrator.parseReviewIssues("not valid json");
        assertEquals("审查未通过，请改进执行结果", issues);
    }

    @Test
    void shouldRetryRejectedStepUntilApproval(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步任务",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "执行任务",
                              "type": "COMMAND",
                              "dependencies": []
                            }
                          ]
                        }
                        """),
                response("第一次执行结果"),
                response("""
                        {"approved": false, "summary": "第一次未通过", "issues": ["需要补充细节"]}
                        """),
                response("第二次执行结果"),
                response("""
                        {"approved": false, "summary": "第二次未通过", "issues": ["还缺最后结论"]}
                        """),
                response("第三次执行结果"),
                response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """)
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String finalResult = orchestrator.run("测试重试逻辑");

        assertTrue(finalResult.contains("第三次执行结果"));
        assertFalse(finalResult.contains("第二次执行结果"));
    }

    @Test
    void shouldRunIndependentStepsInParallel(@TempDir Path tempDir) throws Exception {
        // 两个互相独立的步骤同属一个依赖批次。若并行执行生效，两个 worker 应同时在 chat() 内等待。
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        AtomicInteger peakConcurrency = new AtomicInteger();
        AtomicInteger currentConcurrency = new AtomicInteger();
        AtomicInteger workerChatCalls = new AtomicInteger();
        Queue<String> seenUserMessages = new ConcurrentLinkedQueue<>();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            seenUserMessages.add(body);
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "并行两步",
                          "steps": [
                            {"id": "a", "description": "任务A", "type": "ANALYSIS", "dependencies": []},
                            {"id": "b", "description": "任务B", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("原始任务")) {
                return response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """);
            }
            if (body.contains("任务A")) {
                workerChatCalls.incrementAndGet();
                return waitForReleaseThenReturn(workersReady, releaseWorkers, currentConcurrency, peakConcurrency,
                        response("任务A 的结果"));
            }
            if (body.contains("任务B")) {
                workerChatCalls.incrementAndGet();
                return waitForReleaseThenReturn(workersReady, releaseWorkers, currentConcurrency, peakConcurrency,
                        response("任务B 的结果"));
            }
            return response("fallback");
        };

        DispatchingStubGLMClient llmClient = new DispatchingStubGLMClient(dispatcher);
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                registry,
                new NoOpMemoryManager(tempDir.toFile()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new InMemoryRunStore()
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> runFuture = executor.submit(() -> orchestrator.run("测试并行执行"));

            assertTrue(workersReady.await(10, TimeUnit.SECONDS),
                    "Both workers should reach chat() concurrently, current="
                            + currentConcurrency.get() + ", peak=" + peakConcurrency.get()
                            + ", seen=" + seenUserMessages.stream().map(AgentOrchestratorTest::preview).toList());
            assertEquals(2, currentConcurrency.get(), "Both workers should be waiting before release");
            releaseWorkers.countDown();

            String finalResult = runFuture.get(10, TimeUnit.SECONDS);
            assertTrue(finalResult.contains("多 Agent 协作任务完成"), "finalResult should report completion");
            assertTrue(finalResult.contains("任务A"), "finalResult should mention task A");
            assertTrue(finalResult.contains("任务B"), "finalResult should mention task B");
            assertEquals(2, workerChatCalls.get(), "Each worker should call chat() exactly once");
            assertEquals(2, peakConcurrency.get(), "Expected two workers to run concurrently");
        } finally {
            releaseWorkers.countDown();
            executor.shutdownNow();
        }
    }

    private static LlmClient.ChatResponse waitForReleaseThenReturn(CountDownLatch workersReady,
                                                                  CountDownLatch releaseWorkers,
                                                                  AtomicInteger current,
                                                                  AtomicInteger peak,
                                                                  LlmClient.ChatResponse response) {
        int now = current.incrementAndGet();
        peak.updateAndGet(prev -> Math.max(prev, now));
        workersReady.countDown();
        try {
            releaseWorkers.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            current.decrementAndGet();
        }
        return response;
    }

    private static String preview(String value) {
        if (value == null) {
            return "<null>";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ');
        return normalized.length() > 160 ? normalized.substring(0, 157) + "..." : normalized;
    }

    @Test
    void shouldReportIncompleteRunWhenFailureBlocksRemainingSteps(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "两步任务",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "第一步",
                              "type": "COMMAND",
                              "dependencies": []
                            },
                            {
                              "id": "s2",
                              "description": "第二步",
                              "type": "ANALYSIS",
                              "dependencies": ["s1"]
                            }
                          ]
                        }
                        """),
                response("")
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String finalResult = orchestrator.run("测试失败阻塞");

        assertTrue(finalResult.contains("未完全完成"));
        assertTrue(finalResult.contains("[step_1] ❌ 第一步"));
        assertTrue(finalResult.contains("[step_2] ⏳ 第二步"));
        assertTrue(finalResult.contains("FAILED"), "blocked dependency reason should be surfaced: " + finalResult);
    }

    @Test
    void teamRunRecordsLifecycleEventsInSharedRunStore(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步计划",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "第一步",
                              "type": "ANALYSIS",
                              "dependencies": []
                            }
                          ]
                        }
                        """),
                response("团队执行结果"),
                response("{\"approved\": true, \"summary\": \"通过\", \"issues\": []}")
        ));
        RecordingRunStore runStore = new RecordingRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile()),
                System.out,
                runStore
        );

        String result = orchestrator.run("测试 team 账本");

        assertTrue(result.contains("团队执行结果") || result.contains("✅"));
        List<AgentRunEventType> types = runStore.allEvents().stream()
                .map(AgentRunEvent::type)
                .toList();
        assertTrue(types.contains(AgentRunEventType.RUN_STARTED));
        assertTrue(types.contains(AgentRunEventType.MODE_SELECTED));
        assertTrue(types.contains(AgentRunEventType.RUN_FINISHED));
    }

    @Test
    void teamRunRoutesReadOnlyStepsToExplorerAndRecordsChildRuns(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步计划",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "第一步",
                              "type": "ANALYSIS",
                              "dependencies": []
                            }
                          ]
                        }
                        """),
                response("团队执行结果"),
                response("{\"approved\": true, \"summary\": \"通过\", \"issues\": []}")
        ));
        RecordingRunStore runStore = new RecordingRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile()),
                System.out,
                runStore
        );

        orchestrator.run("测试 team child run");

        List<AgentRunEvent> allEvents = runStore.allEvents();
        String parentRunId = allEvents.stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_STARTED)
                .filter(event -> !event.attributes().containsKey("parentRunId"))
                .findFirst()
                .orElseThrow()
                .runId();
        List<AgentRunEvent> childStarts = allEvents.stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_STARTED)
                .filter(event -> parentRunId.equals(event.attributes().get("parentRunId")))
                .toList();

        assertEquals(2, childStarts.size());
        assertEquals(Set.of("explorer"),
                childStarts.stream().map(event -> event.attributes().get("role")).collect(toSet()));
        assertTrue(childStarts.stream().noneMatch(event -> "planner".equals(event.attributes().get("role"))));
        assertTrue(allEvents.stream()
                .filter(event -> event.type() == AgentRunEventType.LLM_RESPONSE)
                .filter(event -> parentRunId.equals(event.runId()))
                .anyMatch(event -> "plan".equals(event.attributes().get("phase"))));
        assertTrue(childStarts.stream()
                .allMatch(event -> event.attributes().containsKey("profileName")));
        assertTrue(childStarts.stream()
                .allMatch(event -> event.attributes().containsKey("permissionMode")));
        assertTrue(childStarts.stream()
                .filter(event -> "explorer".equals(event.attributes().get("role")))
                .allMatch(event -> "step_1".equals(event.attributes().get("stepId"))));
        assertTrue(childStarts.stream()
                .filter(event -> "explorer".equals(event.attributes().get("role")))
                .map(event -> event.attributes().get("phase"))
                .collect(toSet())
                .containsAll(Set.of("execute", "review")));
        assertTrue(childStarts.stream()
                .allMatch(event -> parentRunId.equals(event.attributes().get("rootRunId"))));
        assertEquals(2, childStarts.stream().map(AgentRunEvent::runId).collect(toSet()).size());
    }

    @Test
    void teamRunRoutesMutatingStepsToWorkerAndRetriesViaSelfReview(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步计划",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "更新文件",
                              "type": "FILE_WRITE",
                              "dependencies": []
                            }
                          ]
                        }
                        """),
                response("第一次执行结果"),
                response("""
                        {"approved": false, "summary": "第一次未通过", "issues": ["缺少证据"]}
                        """),
                response("第二次执行结果"),
                response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """)
        ));
        RecordingRunStore runStore = new RecordingRunStore();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                registry,
                new NoOpMemoryManager(tempDir.toFile()),
                System.out,
                runStore
        );

        String result = orchestrator.run("测试 worker 自审重试");

        assertTrue(result.contains("多 Agent 协作任务完成"), result);
        assertTrue(result.contains("第二次执行结果"), result);

        List<AgentRunEvent> allEvents = runStore.allEvents();
        String parentRunId = allEvents.stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_STARTED)
                .filter(event -> !event.attributes().containsKey("parentRunId"))
                .findFirst()
                .orElseThrow()
                .runId();
        List<AgentRunEvent> childStarts = allEvents.stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_STARTED)
                .filter(event -> parentRunId.equals(event.attributes().get("parentRunId")))
                .toList();

        assertEquals(Set.of("worker"),
                childStarts.stream().map(event -> event.attributes().get("role")).collect(toSet()));
        assertTrue(childStarts.stream().noneMatch(event -> "planner".equals(event.attributes().get("role"))));
        assertTrue(allEvents.stream()
                .filter(event -> event.type() == AgentRunEventType.LLM_RESPONSE)
                .filter(event -> parentRunId.equals(event.runId()))
                .anyMatch(event -> "plan".equals(event.attributes().get("phase"))));
        assertTrue(childStarts.stream()
                .filter(event -> "worker".equals(event.attributes().get("role")))
                .allMatch(event -> "step_1".equals(event.attributes().get("stepId"))));
        assertTrue(childStarts.stream()
                .filter(event -> "worker".equals(event.attributes().get("role")))
                .map(event -> event.attributes().get("phase"))
                .collect(toSet())
                .containsAll(Set.of("execute", "review")));
        assertEquals(4, childStarts.size(), "execute/review for two attempts");
        assertFalse(childStarts.stream().anyMatch(event -> "reviewer".equals(event.attributes().get("role"))));
    }

    @Test
    void teamWorkerToolCallsRecordStructuredToolOutcomeEvents(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步计划",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "写入文件",
                              "type": "FILE_WRITE",
                              "dependencies": []
                            }
                          ]
                        }
                        """),
                new LlmClient.ChatResponse(
                        "assistant",
                        "准备写入",
                        null,
                        List.of(new LlmClient.ToolCall(
                                "call_worker",
                                new LlmClient.ToolCall.Function("write_file",
                                        "{\"path\":\"team.txt\",\"content\":\"team\"}")
                        )),
                        10,
                        5
                ),
                response("worker finished"),
                response("{\"approved\": true, \"summary\": \"通过\", \"issues\": []}")
        ));
        RecordingRunStore runStore = new RecordingRunStore();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                registry,
                new NoOpMemoryManager(tempDir.toFile()),
                System.out,
                runStore
        );

        orchestrator.run("测试 team worker tool outcome");

        AgentRunEvent outcome = runStore.allEvents().stream()
                .filter(event -> event.type() == AgentRunEventType.TOOL_OUTCOME)
                .findFirst()
                .orElseThrow();
        assertEquals("COMPLETED", outcome.attributes().get("status"));
        assertEquals("write_file", outcome.attributes().get("toolName"));
        assertEquals("call_worker", outcome.attributes().get("toolId"));
        assertEquals("ALLOW", outcome.attributes().get("hookDecision"));
        assertEquals("worker#1", outcome.attributes().get("profileName"));
        assertEquals("LEGACY_COMPAT", outcome.attributes().get("permissionMode"));
        assertEquals("ALLOW", outcome.attributes().get("policyDecision"));
        assertEquals("worker#1", outcome.attributes().get("agentName"));
        assertEquals("WORKER", outcome.attributes().get("role"));
        assertTrue(outcome.attributes().containsKey("parentRunId"));
        assertTrue(outcome.attributes().get("lockKeys").contains("FILE:"));
    }

    @Test
    void workerSelfReviewFailureDoesNotCompleteResult(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步计划",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "第一步",
                              "type": "FILE_WRITE",
                              "dependencies": []
                            }
                          ]
                        }
                        """),
                response("未经审查的执行结果")
        ));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String finalResult = orchestrator.run("测试 worker self review fail closed");

        assertTrue(finalResult.contains("未完全完成"));
        assertTrue(finalResult.contains("[step_1] ❌ 第一步"));
        assertFalse(finalResult.contains("✅ 多 Agent 协作任务完成"));
    }

    @Test
    void rejectedSelfReviewChildrenAreBlockedInJsonlState(@TempDir Path tempDir) throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步计划",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "第一步",
                              "type": "FILE_WRITE",
                              "dependencies": []
                            }
                          ]
                        }
                        """),
                response("第一次候选结果"),
                response("{\"approved\": false, \"summary\": \"拒绝\", \"issues\": [\"缺证据\"]}"),
                response("第二次候选结果"),
                response("{\"approved\": false, \"summary\": \"拒绝\", \"issues\": [\"仍缺证据\"]}"),
                response("第三次候选结果"),
                response("{\"approved\": false, \"summary\": \"拒绝\", \"issues\": [\"还是缺证据\"]}")
        ));
        Path runsRoot = tempDir.resolve("runs");
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile()),
                System.out,
                new JsonlRunStore(runsRoot)
        );

        String finalResult = orchestrator.run("测试 reviewer durable fail closed");

        assertTrue(finalResult.contains("未完全完成"));
        Path parentRunDir = Files.list(runsRoot)
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow();
        JsonNode childRuns = MAPPER.readTree(Files.readString(parentRunDir.resolve("run.state.json")))
                .path("childRuns");
        List<JsonNode> workerSummaries = new ArrayList<>();
        childRuns.forEach(child -> {
            if ("worker".equals(child.path("role").asText())) {
                workerSummaries.add(child);
            }
        });

        assertEquals(6, workerSummaries.size());
        List<JsonNode> reviewSummaries = workerSummaries.stream()
                .filter(child -> "review".equals(child.path("lastEventAttributes").path("phase").asText()))
                .toList();

        assertEquals(3, reviewSummaries.size());
        assertTrue(reviewSummaries.stream()
                .allMatch(child -> "false".equals(child.path("approved").asText())));
        assertTrue(reviewSummaries.stream()
                .allMatch(child -> "BLOCKED".equals(child.path("businessStatus").asText())));
        assertTrue(reviewSummaries.stream()
                .noneMatch(child -> "SUCCESS".equals(child.path("businessStatus").asText())));
        assertTrue(reviewSummaries.stream()
                .map(child -> child.path("lastEventAttributes").path("phase").asText())
                .collect(toSet())
                .contains("review"));
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    /** 用 JGit 建仓并做初始提交，使 CLI git 的 worktree 能力可用。 */
    private static void initGitRepo(Path root) throws Exception {
        Files.createDirectories(root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.getRepository().getConfig().setString("user", null, "name", "test");
            git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
            git.getRepository().getConfig().save();
            Files.writeString(root.resolve("README.md"), "init\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").call();
        }
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(File storageDir) {
            super(new GLMClient("test-key"), 200000, new LongTermMemory(storageDir));
        }
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

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }
    }

    /**
     * 基于最后一条用户消息内容派发响应的 stub，支持多线程并发调用。
     */
    private static final class DispatchingStubGLMClient extends GLMClient {
        private final Function<String, ChatResponse> dispatcher;

        private DispatchingStubGLMClient(Function<String, ChatResponse> dispatcher) {
            super("test-key");
            this.dispatcher = dispatcher;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            String lastUserMessage = findLastUser(messages);
            ChatResponse response = dispatcher.apply(lastUserMessage);
            if (response == null) {
                throw new IOException("无匹配响应，最后的 user 消息: " + lastUserMessage);
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }

        private static String findLastUser(List<Message> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if ("user".equals(m.role())) {
                    return m.content() == null ? "" : m.content();
                }
            }
            return "";
        }
    }
}
