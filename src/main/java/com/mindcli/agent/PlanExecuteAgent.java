package com.mindcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.LlmTraceLogger;
import com.mindcli.capability.lsp.LspDiagnosticReport;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.capability.memory.TokenBudget;
import com.mindcli.agent.plan.*;
import com.mindcli.platform.prompt.PromptAssembler;
import com.mindcli.platform.prompt.PromptContext;
import com.mindcli.platform.prompt.PromptMode;
import com.mindcli.platform.prompt.ProjectMemoryLoader;
import com.mindcli.runtime.CancellationContext;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunStatus;
import com.mindcli.runtime.run.RunStore;
import com.mindcli.runtime.run.RunStoreFactory;
import com.mindcli.runtime.run.SessionContext;
import com.mindcli.runtime.run.ToolDispatcher;
import com.mindcli.runtime.run.ToolOutcome;
import com.mindcli.runtime.run.ToolOutcomeEventFactory;
import com.mindcli.runtime.run.ToolOutcomeStatus;
import com.mindcli.capability.skill.SkillIndexFormatter;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.platform.render.terminal.AnsiStyle;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.ToolRegistry.ToolExecutionResult;
import com.mindcli.capability.tool.ToolRegistry.ToolInvocation;
import com.mindcli.platform.render.terminal.TerminalMarkdownRenderer;
import com.mindcli.capability.image.ImageReferenceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private record PlanRunOutcome(String result, boolean persistAssistantMessage) {
        static PlanRunOutcome executed(String result) {
            return new PlanRunOutcome(result, true);
        }

        static PlanRunOutcome canceled(String result) {
            return new PlanRunOutcome(result, false);
        }

        static PlanRunOutcome failed(String result) {
            return new PlanRunOutcome(result, true);
        }
    }

    private record TaskRunResult(String result, boolean streamedOutput) {
        static TaskRunResult of(String result, boolean streamedOutput) {
            return new TaskRunResult(result, streamedOutput);
        }
    }

    private record TaskExecutionResult(Task task, String result, boolean streamedOutput, Exception error) {
        static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) {
            return new TaskExecutionResult(task, taskRunResult.result(), taskRunResult.streamedOutput(), null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, false, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;
    private final PrintStream out;
    private final RunStore runStore;
    private final ToolDispatcher toolDispatcher;
    private volatile RunStore activeRunStore;
    private volatile AgentRunContext activeRunContext;
    private volatile String runtimeOwnedLifecycleRunId;
    private volatile SessionContext sessionContext;
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, new ToolRegistry(), null, null, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, null, null);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            RunStore runStore) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, null, runStore);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, out, null);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out, RunStore runStore) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, out, runStore);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, planner, memoryManager, reviewHandler, null, null);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler, PrintStream out) {
        this(llmClient, toolRegistry, planner, memoryManager, reviewHandler, out, null);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler, PrintStream out,
                     RunStore runStore) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.out = out == null ? deferredSystemOut() : out;
        this.planner = planner != null ? planner : new Planner(llmClient, this.out);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
        this.runStore = runStore == null ? RunStoreFactory.create() : runStore;
        this.activeRunStore = this.runStore;
        this.toolDispatcher = new ToolDispatcher(this.toolRegistry);
        this.toolRegistry.setContextProfile(this.memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemoryWriter(this.memoryManager::storeFact);
        this.planner.setProjectMemorySupplier(this::buildProjectMemoryContext);
    }

    public void setSessionContext(SessionContext sessionContext) {
        this.sessionContext = sessionContext;
        this.planner.setSessionContextSupplier(this::buildSessionContext);
    }

    private String buildSessionContext() {
        SessionContext current = sessionContext;
        return current == null ? "" : current.promptContext(memoryManager.getContextProfile().memoryContextTokens());
    }

    private static PrintStream deferredSystemOut() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                System.out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                System.out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * 截断过长的对话历史，保留 system prompt + 最近 3 个 user 轮次。
     * 触发使用 token 阈值（对齐 Agent.java），避免硬编码消息数。
     * 分割点必须在 user message 边界上，保护 tool_call/tool_result 成对协议。
     */
    private void trimConversationHistory(List<LlmClient.Message> messages) {
        long currentTokens = TokenBudget.estimateMessagesTokens(messages);
        int triggerTokens = memoryManager.getContextProfile().compressionTriggerTokens();
        if (currentTokens <= triggerTokens) return;

        int systemEnd = !messages.isEmpty() && "system".equals(messages.get(0).role()) ? 1 : 0;
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).role())) {
                userIndices.add(i);
            }
        }

        final int retainUserRounds = 3;
        if (userIndices.size() <= retainUserRounds) return;

        int splitIdx = userIndices.get(userIndices.size() - retainUserRounds);
        if (splitIdx <= systemEnd) return;

        long beforeTokens = currentTokens;
        int beforeSize = messages.size();
        List<LlmClient.Message> toKeep = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            toKeep.add(messages.get(i));
        }
        toKeep.addAll(messages.subList(splitIdx, messages.size()));

        messages.clear();
        messages.addAll(toKeep);
        log.info("Plan compaction: {}→{} messages, ~{}→~{} tokens",
                beforeSize, messages.size(), beforeTokens,
                TokenBudget.estimateMessagesTokens(messages));
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        AgentRunContext runContext = AgentRunContext.create(
                AgentMode.PLAN,
                userInput,
                toolRegistry.getProjectPath());
        return runInternal(runContext, runStore, true);
    }

    public String run(AgentRunContext runContext, RunStore runStore) {
        AgentRunContext effectiveContext = runContext == null
                ? AgentRunContext.create(AgentMode.PLAN, "", toolRegistry.getProjectPath())
                : runContext;
        return runInternal(effectiveContext, runStore == null ? this.runStore : runStore, false);
    }

    private String runInternal(AgentRunContext runContext, RunStore activeStore, boolean appendLifecycleStart) {
        String userInput = runContext.input();
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        RunStore previousStore = activeRunStore;
        AgentRunContext previousRunContext = activeRunContext;
        String previousRuntimeOwnedRunId = runtimeOwnedLifecycleRunId;
        activeRunStore = activeStore == null ? this.runStore : activeStore;
        activeRunContext = runContext;
        runtimeOwnedLifecycleRunId = appendLifecycleStart ? null : runContext.runId();
        try {
            if (appendLifecycleStart) {
                appendRunEvent(runContext, AgentRunEventType.RUN_STARTED);
                appendRunEvent(runContext, AgentRunEventType.MODE_SELECTED, Map.of(
                        "mode", AgentMode.PLAN.name(),
                        "adapterMode", AgentMode.PLAN.name()));
            }
            StreamState streamState = new StreamState();
            try {
                if (CancellationContext.isCancelled()) {
                    String result = "⏹️ 已取消当前计划执行。";
                    appendTerminalEvent(runContext, result);
                    return result;
                }
                PlanRunOutcome outcome = runWithPlan(userInput, streamState);
                appendTerminalEvent(runContext, outcome.result());
                if (streamState.hasStreamedOutput() && (outcome.result() == null || outcome.result().isBlank())) {
                    return "";
                }
                return outcome.result();
            } catch (Exception e) {
                log.error("Plan run failed", e);
                String result = "❌ 执行失败: " + e.getMessage();
                appendRunEvent(runContext, AgentRunEventType.RUN_FAILED, Map.of(
                        "status", AgentRunStatus.FAILED.name(),
                        "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                return result;
            }
        } finally {
            activeRunStore = previousStore;
            activeRunContext = previousRunContext;
            runtimeOwnedLifecycleRunId = previousRuntimeOwnedRunId;
        }
    }

/**
     * 使用Plan-and-Execute模式执行
     */
    private PlanRunOutcome runWithPlan(String goal, StreamState streamState) throws IOException {
        ExecutionPlan plan = planner.createPlan(goal);
        return reviewAndExecutePlan(plan, streamState);
    }

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            out.println("📝 已收到补充要求，正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求：" + feedback);
        }
    }

    private String executePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        memoryManager.resetSurfaced();
        out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }
            //获取当前满足全部依赖，可执行的有序任务列表
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }
            //批量执行这一批就绪任务（单任务串行 / 多任务最多4并发）
            List<TaskExecutionResult> batchResults = executeTaskBatch(plan, executableTasks, streamState);
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    streamedTaskOutputs.put(task.getId(), batchResult.streamedOutput());
                    log.info("Task completed: {} status={} resultChars={}",
                            task.getId(), task.getStatus(), batchResult.result() == null ? 0 : batchResult.result().length());
                    if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
                        out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        out.println("✅ 完成 [" + task.getId() + "]: "
                                + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    }
                    continue;
                }

                Exception error = batchResult.error();
                out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                // --- 三级递进恢复 ---
                // 第一级：瞬态错误 → 指数退避重试
                if (task.shouldRetry(error)) {
                    task.incrementRetry();
                    task.resetToPending();
                    long delayMs = (1L << (task.getRetryCount() - 1)) * 1000;
                    out.println("🔁 重试 [" + task.getId() + "] ("
                            + task.getRetryCount() + "/" + task.getMaxRetries()
                            + ")，等待 " + delayMs + "ms...\n");
                    log.info("Retrying task {} ({}/{}) after transient error: {}",
                            task.getId(), task.getRetryCount(), task.getMaxRetries(), error.getMessage());
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }

                String degradation = Optional.ofNullable(task.getDegradation())
                        .orElse("REPLAN")
                        .trim()
                        .toUpperCase(Locale.ROOT);

                // 第二级：显式允许跳过，且任务本身非关键 → 跳过，下游降级执行
                if ("SKIP".equals(degradation) && !task.isCritical()) {
                    task.markSkipped();
                    log.warn("Task {} skipped by degradation policy", task.getId());
                    out.println("⏭️ 跳过 [" + task.getId() + "]（degradation=SKIP），下游将降级执行\n");
                    continue;
                }

                if ("BLOCK".equals(degradation)) {
                    task.markFailed(error.getMessage());
                    log.warn("Task {} failed and blocked by degradation policy", task.getId());
                    out.println("⛔ 阻断 [" + task.getId() + "]（degradation=BLOCK）\n");
                    continue;
                }

                // 第三级：默认或安全回退 → 局部重规划子树
                out.println("🔄 任务 [" + task.getId() + "] 失败，局部重规划子树...\n");
                task.markFailed(error.getMessage());
                try {
                    ExecutionPlan partialPlan = planner.replanSubtree(plan, task, error.getMessage());
                    plan.mergeSubtree(partialPlan);
                    log.info("Subtree replan merged for failed task {}", task.getId());
                } catch (IOException e) {
                    log.error("Subtree replan failed for task {}", task.getId(), e);
                    task.markFailed("局部重规划失败: " + e.getMessage());
                }
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            List<DependencyGraph.BlockedNode<Task>> blockedTasks = plan.getBlockedTasks();
            if (!blockedTasks.isEmpty()) {
                DependencyGraph.BlockedNode<Task> blocked = blockedTasks.get(0);
                return "⚠️ 计划未能继续推进，存在未满足依赖的任务（"
                        + formatBlockedDependencies(blocked.blockingDependencies()) + "）。";
            }
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        String planSummary = finalResult.isEmpty()
                ? buildFinalResult(plan, streamedTaskOutputs)
                : finalResult.toString();

        if (plan.hasFailed()) {
            plan.markFailed();
            if (planSummary.isBlank()) {
                return "⚠️ 计划部分完成，有任务失败。";
            }
            return "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks,
                                                       StreamState streamState) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getType());
            out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState, out)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4), r -> {
            Thread t = new Thread(r, "mindcli-plan-executor");
            t.setDaemon(true);
            return t;
        });
        try {
            Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState, taskOut));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                }));
            }

            List<TaskExecutionResult> results = new ArrayList<>();
            for (Future<TaskExecutionResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception exception
                            ? exception
                            : new RuntimeException(cause);
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), error));
                }
            }

            // 按任务顺序 flush 各缓冲区到 stdout，避免并行输出交错
            for (Task task : executableTasks) {
                ByteArrayOutputStream buf = buffers.get(task.getId());
                if (buf != null && buf.size() > 0) {
                    out.print(buf.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static final int MAX_TASK_ITERATIONS = 5;

    private void appendRunEvent(AgentRunContext context, AgentRunEventType type) {
        appendRunEvent(context, type, Map.of());
    }

    private void appendRunEvent(AgentRunContext context, AgentRunEventType type, Map<String, String> attributes) {
        if (isRuntimeOwnedLifecycleEvent(context, type)) {
            return;
        }
        activeRunStore.append(com.mindcli.runtime.run.AgentRunEvent.of(context, type, attributes));
    }

    private void appendTerminalEvent(AgentRunContext context, String result) {
        String normalized = result == null ? "" : result.trim();
        AgentRunEventType type;
        AgentRunStatus status;
        if (normalized.startsWith("⏹️")) {
            type = AgentRunEventType.RUN_CANCELLED;
            status = AgentRunStatus.CANCELLED;
        } else if (normalized.startsWith("❌")) {
            type = AgentRunEventType.RUN_FAILED;
            status = AgentRunStatus.FAILED;
        } else if (normalized.startsWith("⚠️")) {
            type = AgentRunEventType.RUN_FAILED;
            status = AgentRunStatus.BLOCKED;
        } else {
            type = AgentRunEventType.RUN_FINISHED;
            status = AgentRunStatus.SUCCESS;
        }
        appendRunEvent(context, type, Map.of("status", status.name()));
    }

    private boolean isRuntimeOwnedLifecycleEvent(AgentRunContext context, AgentRunEventType type) {
        return runtimeOwnedLifecycleRunId != null
                && context != null
                && runtimeOwnedLifecycleRunId.equals(context.runId())
                && (type == AgentRunEventType.RUN_STARTED
                || type == AgentRunEventType.MODE_SELECTED
                || type == AgentRunEventType.RUN_FINISHED
                || type == AgentRunEventType.RUN_FAILED
                || type == AgentRunEventType.RUN_CANCELLED
                || type == AgentRunEventType.BUDGET_EXHAUSTED);
    }

    /**
     * 执行单个任务（支持多轮工具调用）
     */
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task,
                                      StreamState streamState, PrintStream out) throws IOException {
        String prompt = promptAssembler.assemble(PromptMode.PLAN, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .memoryContext(buildSessionContext())
                .variable("taskType", task.getType())
                .variable("taskDescription", task.getDescription())
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());

        // 注入长期记忆上下文
        String memoryContext = memoryManager.buildContextForQuery(
                task.getDescription(),
                memoryManager.getContextProfile().memoryContextTokens(),
                activeRunContext,
                activeRunStore);
        String taskInput = buildTaskContext(goal, plan, task);
        if (!memoryContext.isEmpty()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }

        List<LlmClient.Message> messages = new ArrayList<>(Arrays.asList(
                LlmClient.Message.system(prompt),
                ImageReferenceParser.userMessage(
                        taskInput,
                        Path.of(toolRegistry.getProjectPath()))
        ));

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;
        TaskStreamRenderer streamRenderer = new TaskStreamRenderer(task.getId(), streamState, out);
        AgentBudget taskBudget = AgentBudget.fromLlmClient(llmClient);

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalCachedInputTokens = 0;

        while (iteration < MAX_TASK_ITERATIONS) {
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。", streamRenderer.hasStreamedOutput());
            }

            // AgentBudget 三重阀：token 耗尽 / 停滞检测 / 硬轮数兜底
            AgentBudget.ExitReason exitReason = taskBudget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                log.warn("Task {} budget exhausted: reason={}, iteration={}",
                        task.getId(), exitReason, iteration);
                streamRenderer.finish();
                String toolOnlyResult = allResults.toString().trim();
                if (!toolOnlyResult.isEmpty()) {
                    return TaskRunResult.of("⚠️ 子任务因 " + taskBudget.describeExit(exitReason)
                            + " 提前终止，已有工具输出：\n" + toolOnlyResult, streamRenderer.hasStreamedOutput());
                }
                return TaskRunResult.of("⚠️ 子任务因 " + taskBudget.describeExit(exitReason) + " 提前终止",
                        streamRenderer.hasStreamedOutput());
            }

            iteration++;
            taskBudget.beginIteration();

            // 调 LLM 前评估 messages 是否接近 window 上限；超阈值压缩早期消息为摘要。
            injectPendingLspDiagnostics(messages, out);
            trimConversationHistory(messages);

            LlmClient.ChatResponse response;
            try {
                response = com.mindcli.platform.llm.LlmRetryPolicy.withRetry(() ->
                        llmClient.chat(
                                messages,
                                llmClient.supportsTools() ? toolRegistry.getToolDefinitions() : null,
                                streamRenderer
                        ),
                        "plan-task-" + task.getId()
                );
            } catch (Exception e) {
                log.error("Task {} LLM call failed after retries: {}", task.getId(), e.getMessage());
                streamRenderer.finish();
                throw new IOException("LLM 调用失败: " + e.getMessage(), e);
            }
            LlmTraceLogger.logReasoning(log,
                    "plan-task task=" + task.getId() + " iteration=" + iteration,
                    llmClient,
                    response.reasoningContent());
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。", streamRenderer.hasStreamedOutput());
            }

            totalInputTokens += response.inputTokens();
            totalOutputTokens += response.outputTokens();
            totalCachedInputTokens += response.cachedInputTokens();
            taskBudget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

            log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                    task.getId(),
                    iteration,
                    response.toolCalls() == null ? 0 : response.toolCalls().size(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                    response.content() == null ? 0 : response.content().length());

            if (!response.hasToolCalls()) {
                memoryManager.recordTokenUsage(totalInputTokens, totalOutputTokens, totalCachedInputTokens);
                // 子任务结束，增量提取长期记忆
                memoryManager.extractFactsIncrementalAsync(messages, activeRunContext, activeRunStore);
                if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                    String toolOnlyResult = allResults.toString().trim();
                    streamRenderer.finish();
                    return TaskRunResult.of(toolOnlyResult, streamRenderer.hasStreamedOutput());
                }
                streamRenderer.finish();
                return TaskRunResult.of(response.content(), streamRenderer.hasStreamedOutput());
            }

            // 有工具调用：执行工具并将结果回灌到消息历史
            printToolCalls(out, response.toolCalls());
            taskBudget.recordToolCalls(response.toolCalls());
            messages.add(LlmClient.Message.assistant(
                    response.reasoningContent(),
                    response.content(),
                    response.toolCalls()
            ));

            // 在工具执行前 flush 并重置流式渲染器：避免 Markdown renderer pending 文本
            // 被 HITL 提示"跨过"导致 🧠 / 🤖 标题与内容错位
            streamRenderer.resetBetweenIterations();

            List<ToolExecutionResult> toolResults = executeToolCalls(task.getId(), response.toolCalls());
            for (ToolExecutionResult toolResult : toolResults) {
                allResults.append(toolResult.result()).append("\n");
                messages.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
            }
            appendImageToolMessages(messages, toolResults);
        }

        String fallbackResult = allResults.toString().trim();
        streamRenderer.finish();
        return TaskRunResult.of(fallbackResult, streamRenderer.hasStreamedOutput());
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context for plan task", e);
            return "";
        }
    }

    private String buildProjectMemoryContext() {
        try {
            return ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
        } catch (Exception e) {
            log.warn("Failed to load MIND.md project memory", e);
            return "";
        }
    }

    private void injectPendingLspDiagnostics(List<LlmClient.Message> messages, PrintStream out) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        messages.add(LlmClient.Message.user(report.promptText()));
        out.println(report.displayText());
        log.info("Injected LSP diagnostics into plan task conversation");
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private List<ToolExecutionResult> executeToolCalls(String taskId, List<LlmClient.ToolCall> toolCalls) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Task {} scheduling tool {}", taskId, toolName);
            log.debug("Task {} tool args [{}]: {}", taskId, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Task {} executing {} tool calls in parallel", taskId, invocations.size());
        }
        AgentRunContext dispatchContext = toolDispatchContext(taskId);
        List<ToolOutcome> outcomes = toolDispatcher.dispatchInvocations(invocations, dispatchContext);
        appendToolOutcomeEvents(dispatchContext, outcomes);
        List<ToolExecutionResult> results = outcomes.stream()
                .map(PlanExecuteAgent::toLegacyResult)
                .toList();
        for (ToolExecutionResult result : results) {
            log.debug("Task {} tool result preview [{}]: {}", taskId, result.name(), preview(result.result(), 300));
        }
        return results;
    }

    private AgentRunContext toolDispatchContext(String taskId) {
        AgentRunContext base = activeRunContext == null
                ? AgentRunContext.create(AgentMode.PLAN, "", toolRegistry.getProjectPath())
                : activeRunContext;
        Map<String, String> metadata = new LinkedHashMap<>(base.metadata());
        metadata.put("role", "plan");
        if (taskId != null && !taskId.isBlank()) {
            metadata.put("taskId", taskId);
        }
        return new AgentRunContext(
                base.runId(),
                AgentMode.PLAN,
                base.input(),
                base.workspace(),
                base.startedAt(),
                metadata);
    }

    private void appendToolOutcomeEvents(AgentRunContext context, List<ToolOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return;
        }
        for (ToolOutcome outcome : outcomes) {
            activeRunStore.append(ToolOutcomeEventFactory.create(context, outcome, Map.of()));
        }
    }

    private static ToolExecutionResult toLegacyResult(ToolOutcome outcome) {
        return new ToolExecutionResult(
                outcome.id(),
                outcome.name(),
                outcome.argumentsJson(),
                outcome.text(),
                outcome.elapsedMillis(),
                outcome.status() == ToolOutcomeStatus.TIMED_OUT,
                outcome.imageParts());
    }

    private void appendImageToolMessages(List<LlmClient.Message> messages, List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            messages.add(LlmClient.Message.user(parts));
        }
    }

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    private static final class StreamState {
        private volatile boolean streamedOutput;

        private void markStreamed() {
            this.streamedOutput = true;
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }
    }

    private static final class TaskStreamRenderer implements LlmClient.StreamListener {
        private final String taskId;
        private final StreamState streamState;
        private final PrintStream out;
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private TaskStreamRenderer(String taskId, StreamState streamState, PrintStream out) {
            this.taskId = taskId;
            this.streamState = streamState;
            this.out = out;
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                lateReasoning.append(delta);
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            } else {
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                // content 可能只是 tool-call 前的叙述，也可能是最终回答，用"输出"避免误导。
                out.println(AnsiStyle.section("🤖 任务输出 [" + taskId + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private synchronized void finish() {
            if (streamedOutput) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                if (contentRenderer != null) {
                    contentRenderer.finish();
                }
                flushLateReasoning();
                out.println("\n");
            }
        }

        /**
         * 两次 iteration 之间（通常是一次 tool-call 分支完成后）调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代能重新打印 🧠 / 🤖 标题，避免标题和内容被 HITL / 工具执行中断而错位。
         */
        private synchronized void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            flushLateReasoning();
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private synchronized boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void flushLateReasoning() {
            String late = lateReasoning.toString().trim();
            if (late.isEmpty()) {
                lateReasoning.setLength(0);
                return;
            }
            out.println();
            out.println(AnsiStyle.heading("🧠 补充思考 [" + taskId + "]"));
            TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(out);
            renderer.append(late);
            renderer.finish();
            lateReasoning.setLength(0);
        }
    }

    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    private String formatBlockedDependencies(List<DependencyGraph.DependencyBlocker> blockers) {
        return String.join(", ", blockers.stream()
                .map(blocker -> blocker.dependencyId() + "=" + blocker.state())
                .toList());
    }

    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        return plan.getAllTasks().stream()
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

}
