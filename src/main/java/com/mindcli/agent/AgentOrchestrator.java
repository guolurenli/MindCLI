package com.mindcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.agent.profile.AgentPool;
import com.mindcli.agent.profile.AgentProfile;
import com.mindcli.agent.profile.AgentProfileLoader;
import com.mindcli.agent.profile.AgentTaskRequirements;
import com.mindcli.agent.profile.AgentToolPolicy;
import com.mindcli.agent.plan.DependencyGraph;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.LlmRetryPolicy;
import com.mindcli.platform.llm.LlmTraceLogger;
import com.mindcli.platform.prompt.PromptAssembler;
import com.mindcli.platform.prompt.PromptContext;
import com.mindcli.platform.prompt.PromptMode;
import com.mindcli.platform.prompt.ProjectMemoryLoader;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.agent.plan.PlanSchema;
import com.mindcli.agent.plan.PlanSchemaParser;
import com.mindcli.agent.plan.PlanSchemaValidator;
import com.mindcli.agent.plan.PlanTaskSpec;
import com.mindcli.agent.plan.PlanValidationResult;
import com.mindcli.runtime.CancellationContext;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunStatus;
import com.mindcli.runtime.run.RunStore;
import com.mindcli.runtime.run.RunStoreFactory;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.platform.render.terminal.AnsiStyle;
import com.mindcli.platform.render.terminal.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Agent 编排器 - Multi-Agent 系统的"主"
 *
 * 负责管理团队、分配任务、路由消息、解决冲突。
 * 采用主从架构：编排器是主，子代理是从。
 *
 * 协作流程：
 * 1. 用户提交任务 -> 编排器内建规划拆解任务
 * 2. 编排器解析计划
 * 3. 编排器按依赖顺序将子任务分配给执行者
 * 4. 执行者返回结果 -> 同一执行者进入自审阶段
 * 5. 自审通过则完成，否则带上反馈重新执行
 * 6. 所有子任务完成后，编排器汇总返回最终结果
 *
 * 并行策略：
     * - 同一依赖批次内部 **并行** 执行（最多执行 profile 池大小并发，默认 explorer 2 + worker 1）
 * - 每个并行步骤使用独立的 PrintStream 缓冲流式输出，批次结束后按 step_id 顺序 flush 到 stdout，
 *   避免多线程写同一个终端流造成交错，同时仍让用户看到结构化的执行过程
 * - 单步批次仍走直连流式路径，保持"实时打字"的观感
 * - 执行 Agent 通过 AgentPool profile lease 池化分配，确保同一 profile 不会被两个步骤并发占用
 * - Worker/Explorer 执行后进入自己的 review->repair 循环，不再依赖默认 reviewer 子代理
 */
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RETRIES_PER_STEP = 2;

    private final LlmClient llmClient;
    private final List<SubAgent> explorers;
    private final List<SubAgent> workers;
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final List<AgentProfile> agentProfiles;
    private final AgentPool agentPool;
    private final PrintStream out;
    private final RunStore runStore;
    private volatile RunStore activeRunStore;
    private volatile String runtimeOwnedLifecycleRunId;
    private final PlanSchemaParser planSchemaParser = new PlanSchemaParser(mapper);
    private final PlanSchemaValidator planSchemaValidator = new PlanSchemaValidator();
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;

    // 执行步骤的数据结构（package-private 供测试访问）
    record ExecutionStep(String id, String description, String type,
                                  List<String> dependencies, List<String> requiredTools,
                                  String preferredAgent, String riskLevel,
                                  List<String> writeScope,
                                  String result, StepStatus status) {
        static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return pending(id, description, type, dependencies, List.of(), "", "", List.of());
        }

        static ExecutionStep pending(String id, String description, String type, List<String> dependencies,
                                     List<String> requiredTools, String preferredAgent, String riskLevel) {
            return pending(id, description, type, dependencies, requiredTools, preferredAgent, riskLevel, List.of());
        }

        static ExecutionStep pending(String id, String description, String type, List<String> dependencies,
                                     List<String> requiredTools, String preferredAgent, String riskLevel,
                                     List<String> writeScope) {
            return new ExecutionStep(id, description, type, dependencies,
                    requiredTools == null ? List.of() : List.copyOf(requiredTools),
                    preferredAgent == null ? "" : preferredAgent,
                    riskLevel == null || riskLevel.isBlank() ? "low" : riskLevel,
                    writeScope == null ? List.of() : List.copyOf(writeScope),
                    null, StepStatus.PENDING);
        }

        ExecutionStep withResult(String result) {
            return new ExecutionStep(id, description, type, dependencies, requiredTools,
                    preferredAgent, riskLevel, writeScope, result, StepStatus.COMPLETED);
        }

        ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies, requiredTools,
                    preferredAgent, riskLevel, writeScope, result, StepStatus.FAILED);
        }

        ExecutionStep withSkipped(String reason) {
            return new ExecutionStep(id, description, type, dependencies,
                    requiredTools, preferredAgent, riskLevel, writeScope,
                    reason != null ? reason : "步骤被跳过", StepStatus.SKIPPED);
        }

        ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, requiredTools,
                    preferredAgent, riskLevel, writeScope, result, StepStatus.RUNNING);
        }
    }

    private record ReviewChildResult(AgentRunContext context, AgentMessage message) {
    }

    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    }

    private record StepExecutionGroup(
            String fingerprint,
            ExecutionStep leader,
            List<ExecutionStep> duplicates,
            boolean mutating
    ) {
        private StepExecutionGroup {
            fingerprint = fingerprint == null ? "" : fingerprint;
            leader = Objects.requireNonNull(leader, "leader");
            duplicates = duplicates == null ? List.of() : List.copyOf(duplicates);
        }
    }

    public AgentOrchestrator(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this(llmClient, toolRegistry, memoryManager, System.out);
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager, PrintStream out) {
        this(llmClient, toolRegistry, memoryManager, out, null);
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager, PrintStream out, RunStore runStore) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
        this.toolRegistry = toolRegistry;
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemoryWriter(memoryManager::storeFact);
        this.memoryManager = memoryManager;
        this.agentProfiles = AgentProfileLoader.load(Path.of(this.toolRegistry.getProjectPath()));
        this.agentPool = new AgentPool(this.agentProfiles);
        this.explorers = this.agentProfiles.stream()
                .filter(profile -> profile.role() == AgentRole.EXPLORER)
                .map(this::createSubAgent)
                .toList();
        this.workers = this.agentProfiles.stream()
                .filter(profile -> profile.role() == AgentRole.WORKER)
                .map(this::createSubAgent)
                .toList();
        this.runStore = runStore == null ? RunStoreFactory.create() : runStore;
        this.activeRunStore = this.runStore;
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        explorers.forEach(explorer -> explorer.setExternalContextSupplier(this.externalContextSupplier));
        workers.forEach(worker -> worker.setExternalContextSupplier(this.externalContextSupplier));
    }

    /**
     * 把 Skill 系统下发给所有 SubAgent。
     */
    public void setSkillSystem(com.mindcli.capability.skill.SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        for (SubAgent explorer : explorers) {
            explorer.setSkillRegistry(skillRegistry);
        }
        for (SubAgent worker : workers) {
            worker.setSkillRegistry(skillRegistry);
        }
    }

    private SubAgent createSubAgent(AgentProfile profile) {
        SubAgent agent = new SubAgent(profile, llmClient, toolRegistry);
        agent.setMemoryManager(memoryManager);
        agent.setExternalContextSupplier(externalContextSupplier);
        if (skillRegistry != null) {
            agent.setSkillRegistry(skillRegistry);
        }
        return agent;
    }

    /**
     * 内联规划：主 agent 直接调用 LLM 拆解任务（无独立 planner 子代理）。
     */
    private String planWithOrchestrator(String userInput) throws Exception {
        String systemPrompt = promptAssembler.assemble(PromptMode.TEAM_PLANNER,
                PromptContext.builder()
                        .projectMemoryContext(buildProjectMemoryContext())
                        .build())
                + "\n\n" + buildAgentCatalog();
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system(systemPrompt),
                LlmClient.Message.user("请为以下任务制定执行计划：\n" + userInput));
        PlanningStreamRenderer renderer = new PlanningStreamRenderer(out);
        LlmClient.ChatResponse response = LlmRetryPolicy.withRetry(
                () -> llmClient.chat(messages, null, renderer), "team-planner");
        LlmTraceLogger.logReasoning(log, "team-planner", llmClient, response.reasoningContent());
        renderer.finish();
        return response.content();
    }

    /**
     * 把内置 + 自定义子代理的 name/description 注入规划 prompt，供主代理按 description 选人。
     */
    private String buildAgentCatalog() {
        StringBuilder sb = new StringBuilder("## 可用子代理\n\n");
        for (AgentProfile profile : agentProfiles) {
            sb.append("- ").append(profile.name()).append(": ").append(profile.description()).append("\n");
        }
        sb.append("\n规划时请为每个步骤的 preferredAgent 字段填入最合适的子代理名（可用上面任一名字）。\n");
        return sb.toString();
    }

    private String buildProjectMemoryContext() {
        try {
            return ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
        } catch (Exception e) {
            log.warn("Failed to load PAI.md project memory for orchestrator", e);
            return "";
        }
    }

    private int roleParallelism(AgentRole role) {
        int configured = agentProfiles.stream()
                .filter(profile -> profile.role() == role)
                .mapToInt(AgentProfile::maxConcurrency)
                .sum();
        return Math.max(1, configured);
    }

    private AgentRole readOnlyExecutionRole() {
        return agentPool.profiles(AgentRole.EXPLORER).isEmpty()
                ? AgentRole.WORKER
                : AgentRole.EXPLORER;
    }

    private AgentRole executionRoleFor(ExecutionStep step) {
        if (isMutatingStep(step)) {
            return AgentRole.WORKER;
        }
        AgentRole readOnlyRole = readOnlyExecutionRole();
        if (readOnlyRole == AgentRole.EXPLORER
                && !roleHasProfileForTools(AgentRole.EXPLORER, step.requiredTools())) {
            return AgentRole.WORKER;
        }
        return readOnlyRole;
    }

    private static String childRoleName(AgentRole role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    private int batchParallelism(List<ExecutionStep> batch) {
        Set<AgentRole> roles = executionRolesIn(batch);
        int configured = roles.stream()
                .mapToInt(this::roleParallelism)
                .sum();
        return Math.max(1, configured);
    }

    private String batchRoleLabel(List<ExecutionStep> batch) {
        Set<AgentRole> roles = executionRolesIn(batch);
        if (roles.isEmpty()) {
            return childRoleName(readOnlyExecutionRole());
        }
        return String.join("+", roles.stream()
                .map(AgentOrchestrator::childRoleName)
                .toList());
    }

    private Set<AgentRole> executionRolesIn(List<ExecutionStep> batch) {
        Set<AgentRole> roles = new LinkedHashSet<>();
        if (batch == null) {
            return roles;
        }
        for (ExecutionStep step : batch) {
            roles.add(executionRoleFor(step));
        }
        return roles;
    }

    private boolean roleHasProfileForTools(AgentRole role, List<String> requiredTools) {
        return agentPool.profiles(role).stream()
                .anyMatch(profile -> profileAllowsAll(profile, requiredTools));
    }

    private static boolean profileAllowsAll(AgentProfile profile, List<String> requiredTools) {
        if (requiredTools == null || requiredTools.isEmpty()) {
            return true;
        }
        for (String tool : requiredTools) {
            if (!profile.allowsTool(tool)) {
                return false;
            }
        }
        return true;
    }

    private AgentTaskRequirements requirementsFor(ExecutionStep step) {
        return new AgentTaskRequirements(
                step.id(),
                step.requiredTools(),
                step.preferredAgent(),
                step.riskLevel());
    }

    /**
     * 运行多 Agent 协作任务
     */
    public String run(String userInput) {
        AgentRunContext runContext = AgentRunContext.create(
                AgentMode.TEAM,
                userInput,
                toolRegistry.getProjectPath());
        return runInternal(runContext, runStore, true);
    }

    public String run(AgentRunContext runContext, RunStore runStore) {
        AgentRunContext effectiveContext = runContext == null
                ? AgentRunContext.create(AgentMode.TEAM, "", toolRegistry.getProjectPath())
                : runContext;
        return runInternal(effectiveContext, runStore == null ? this.runStore : runStore, false);
    }

    private String runInternal(AgentRunContext runContext, RunStore activeStore, boolean appendLifecycleStart) {
        String userInput = runContext.input();
        log.info("Multi-Agent run started: inputLength={}", userInput == null ? 0 : userInput.length());
        RunStore previousStore = activeRunStore;
        String previousRuntimeOwnedRunId = runtimeOwnedLifecycleRunId;
        activeRunStore = activeStore == null ? this.runStore : activeStore;
        runtimeOwnedLifecycleRunId = appendLifecycleStart ? null : runContext.runId();
        try {
            if (appendLifecycleStart) {
                appendRunEvent(runContext, AgentRunEventType.RUN_STARTED);
                appendRunEvent(runContext, AgentRunEventType.MODE_SELECTED, Map.of(
                        "mode", AgentMode.TEAM.name(),
                        "adapterMode", AgentMode.TEAM.name()));
            }
            return runTeam(runContext, userInput);
        } finally {
            activeRunStore = previousStore;
            runtimeOwnedLifecycleRunId = previousRuntimeOwnedRunId;
        }
    }

    private String runTeam(AgentRunContext runContext, String userInput) {
        memoryManager.resetSurfaced();
        if (CancellationContext.isCancelled()) {
            String cancelled = "⏹️ 已取消当前多 Agent 任务。";
            appendTerminalEvent(runContext, cancelled);
            return cancelled;
        }

        // 1. 规划阶段：主 agent 内建规划（对齐 Codex default 主 agent 内建 update_plan）
        out.println(AnsiStyle.heading("📋 第一阶段：规划"));
        out.println("🧑‍💼 正在分析任务并制定执行计划...\n");

        String planContent;
        try {
            planContent = planWithOrchestrator(userInput);
        } catch (Exception e) {
            String failed = "❌ 规划阶段失败，LLM 调用出错：" + e.getMessage();
            appendRunEvent(runContext, AgentRunEventType.RUN_FAILED, Map.of(
                    "status", AgentRunStatus.FAILED.name(),
                    "phase", "plan"));
            return failed;
        }
        appendRunEvent(runContext, AgentRunEventType.LLM_RESPONSE, Map.of(
                "phase", "plan",
                "agent", "orchestrator",
                "messageType", "RESULT"));
        if (CancellationContext.isCancelled()) {
            String cancelled = "⏹️ 已取消当前多 Agent 任务。";
            appendTerminalEvent(runContext, cancelled);
            return cancelled;
        }
        if (planContent == null || planContent.isBlank()) {
            String failed = "❌ 规划失败：未能生成有效计划";
            appendRunEvent(runContext, AgentRunEventType.RUN_FAILED, Map.of(
                    "status", AgentRunStatus.FAILED.name(),
                    "phase", "plan"));
            return failed;
        }

        // 2. 解析计划
        List<ExecutionStep> steps = parsePlan(planContent);
        if (steps.isEmpty()) {
            String failed = "❌ 规划失败：无法解析执行计划\n原始输出:\n" + planContent;
            appendRunEvent(runContext, AgentRunEventType.RUN_FAILED, Map.of(
                    "status", AgentRunStatus.FAILED.name(),
                    "phase", "parse_plan"));
            return failed;
        }

        out.println(AnsiStyle.heading("📋 执行计划"));
        out.println(summarizeSteps(steps) + "\n");

        // 3. 执行阶段：按依赖顺序分配给执行者
        out.println(AnsiStyle.heading("⚡ 第二阶段：执行"));
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        int batchIndex = 0;

        while (true) {
            if (CancellationContext.isCancelled()) {
                String cancelled = "⏹️ 已取消当前多 Agent 任务。";
                appendTerminalEvent(runContext, cancelled);
                return cancelled;
            }
            List<ExecutionStep> executable = getExecutableSteps(steps);
            if (executable.isEmpty()) {
                break;
            }
            List<StepExecutionGroup> groups = collapseExecutableGroups(executable);
            if (groups.isEmpty()) {
                continue;
            }

            Map<String, String> mutatingSerialReasons = mutatingSerialReasons(groups);
            List<StepExecutionGroup> readOnlyBatch = new ArrayList<>();
            for (StepExecutionGroup group : groups) {
                if (group.mutating()) {
                    if (!readOnlyBatch.isEmpty()) {
                        batchIndex++;
                        runReadOnlyGroupBatch(runContext, readOnlyBatch, steps, retryCount, batchIndex);
                        readOnlyBatch.clear();
                    }
                    batchIndex++;
                    runMutatingGroup(runContext, group, steps, retryCount, batchIndex, out,
                            mutatingSerialReasons.getOrDefault(group.leader().id(), ""));
                } else {
                    readOnlyBatch.add(group);
                }
            }
            if (!readOnlyBatch.isEmpty()) {
                batchIndex++;
                runReadOnlyGroupBatch(runContext, readOnlyBatch, steps, retryCount, batchIndex);
            }
        }

        // 5. 处理未能执行的残留步骤（显式提示用户）
        List<DependencyGraph.BlockedNode<ExecutionStep>> blockedSteps = blockedSteps(steps);
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                DependencyGraph.BlockedNode<ExecutionStep> blocked = blockedSteps.stream()
                        .filter(item -> item.node().id().equals(step.id()))
                        .findFirst()
                        .orElse(null);
                out.println("⏭️ 步骤 [" + step.id() + "] 未能执行（依赖状态: "
                        + formatBlockedDependencies(blocked, step.dependencies(), steps) + "）: " + step.description());
            }
            if (step.status() == StepStatus.SKIPPED) {
                out.println("⏭️ 步骤 [" + step.id() + "] 已跳过: " + step.description());
            }
        }

        // 6. 汇总结果
        String finalResult = buildFinalResult(steps);
        appendTerminalEvent(runContext, finalResult);
        return finalResult;
    }

    private List<DependencyGraph.BlockedNode<ExecutionStep>> blockedSteps(List<ExecutionStep> steps) {
        return DependencyGraph.of(
                steps,
                ExecutionStep::id,
                ExecutionStep::dependencies).blockedNodes(
                candidate -> candidate.status() == StepStatus.PENDING,
                dep -> getStepStatus(dep, steps).name(),
                state -> "COMPLETED".equals(state) || "SKIPPED".equals(state));
    }

    private String formatBlockedDependencies(DependencyGraph.BlockedNode<ExecutionStep> blocked,
                                             List<String> dependencies,
                                             List<ExecutionStep> steps) {
        if (blocked != null && !blocked.blockingDependencies().isEmpty()) {
            return String.join(", ", blocked.blockingDependencies().stream()
                    .map(dep -> dep.dependencyId() + "=" + dep.state())
                    .toList());
        }
        return String.join(", ", dependencies.stream()
                .map(dep -> dep + "=" + getStepStatus(dep, steps))
                .toList());
    }

    private List<StepExecutionGroup> collapseExecutableGroups(List<ExecutionStep> executable) {
        if (executable == null || executable.isEmpty()) {
            return List.of();
        }
        Map<String, List<ExecutionStep>> groups = new LinkedHashMap<>();
        Map<String, Boolean> mutatingByFingerprint = new LinkedHashMap<>();
        for (ExecutionStep step : executable) {
            if (step == null) {
                continue;
            }
            String fingerprint = stepFingerprint(step);
            groups.computeIfAbsent(fingerprint, ignored -> new ArrayList<>()).add(step);
            mutatingByFingerprint.putIfAbsent(fingerprint, isMutatingStep(step));
        }

        List<StepExecutionGroup> collapsed = new ArrayList<>();
        for (Map.Entry<String, List<ExecutionStep>> entry : groups.entrySet()) {
            List<ExecutionStep> groupSteps = entry.getValue();
            if (groupSteps.isEmpty()) {
                continue;
            }
            ExecutionStep leader = groupSteps.get(0);
            List<ExecutionStep> duplicates = groupSteps.size() <= 1
                    ? List.of()
                    : new ArrayList<>(groupSteps.subList(1, groupSteps.size()));
            collapsed.add(new StepExecutionGroup(
                    entry.getKey(),
                    leader,
                    duplicates,
                    mutatingByFingerprint.getOrDefault(entry.getKey(), false)));
        }
        return List.copyOf(collapsed);
    }

    private void runReadOnlyGroupBatch(AgentRunContext runContext, List<StepExecutionGroup> groups,
                                       List<ExecutionStep> steps, Map<String, Integer> retryCount, int batchIndex) {
        if (groups == null || groups.isEmpty()) {
            return;
        }
        if (groups.size() == 1) {
            StepExecutionGroup group = groups.get(0);
            String context = buildStepContext(steps, group.leader());
            runStep(runContext, group.leader(), steps, retryCount, context, out);
            propagateDuplicateResult(group, steps);
            return;
        }
        List<ExecutionStep> leaders = groups.stream()
                .map(StepExecutionGroup::leader)
                .toList();
        out.println("⚡ 批次 #" + batchIndex + "：" + leaders.size()
                + " 个只读步骤并行执行（最多 " + batchParallelism(leaders)
                + " 个并发 " + batchRoleLabel(leaders) + "）\n");
        runBatchParallel(runContext, leaders, steps, retryCount);
        for (StepExecutionGroup group : groups) {
            propagateDuplicateResult(group, steps);
        }
    }

    private void runMutatingGroup(AgentRunContext runContext, StepExecutionGroup group, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount, int batchIndex, PrintStream out,
                                  String serialReason) {
        if (group == null) {
            return;
        }
        String reason = serialReason == null || serialReason.isBlank()
                ? "写入型步骤，按顺序执行以避免并发冲突"
                : serialReason;
        out.println("🧷 批次 #" + batchIndex + "：" + group.leader().id()
                + " " + reason + "\n");
        String context = buildStepContext(steps, group.leader());
        runStep(runContext, group.leader(), steps, retryCount, context, out);
        propagateDuplicateResult(group, steps);
    }

    private Map<String, String> mutatingSerialReasons(List<StepExecutionGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }
        List<StepExecutionGroup> mutatingGroups = groups.stream()
                .filter(StepExecutionGroup::mutating)
                .toList();
        if (mutatingGroups.isEmpty()) {
            return Map.of();
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        for (StepExecutionGroup group : mutatingGroups) {
            List<String> scope = normalizeScopes(group.leader().writeScope());
            if (scope.isEmpty()) {
                reasons.put(group.leader().id(), "写入范围未声明，按顺序执行以避免并发冲突");
                continue;
            }
            for (StepExecutionGroup other : mutatingGroups) {
                if (group == other) {
                    continue;
                }
                List<String> otherScope = normalizeScopes(other.leader().writeScope());
                if (otherScope.isEmpty()) {
                    continue;
                }
                if (writeScopesOverlap(scope, otherScope)) {
                    reasons.put(group.leader().id(), "写入范围重叠，按顺序执行："
                            + formatScopes(scope) + " 与 " + other.leader().id()
                            + " 的 " + formatScopes(otherScope));
                    break;
                }
            }
        }
        return reasons;
    }

    private static boolean writeScopesOverlap(List<String> left, List<String> right) {
        for (String a : normalizeScopes(left)) {
            for (String b : normalizeScopes(right)) {
                if (normalizedScopeOverlaps(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean normalizedScopeOverlaps(String left, String right) {
        String a = normalizeScopePrefix(left);
        String b = normalizeScopePrefix(right);
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.equals(b)
                || b.startsWith(a + "/")
                || a.startsWith(b + "/");
    }

    private static String normalizeScopePrefix(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim()
                .replace('\\', '/')
                .replaceAll("/+", "/")
                .toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/**") || normalized.endsWith("/*")) {
            normalized = normalized.substring(0, normalized.lastIndexOf('/'));
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void propagateDuplicateResult(StepExecutionGroup group, List<ExecutionStep> steps) {
        if (group == null || group.duplicates().isEmpty()) {
            return;
        }
        ExecutionStep leader = stepById(steps, group.leader().id());
        if (leader == null) {
            return;
        }
        for (ExecutionStep duplicate : group.duplicates()) {
            updateStep(steps, duplicate.id(), copyExecutionOutcome(duplicate, leader));
        }
    }

    private ExecutionStep copyExecutionOutcome(ExecutionStep target, ExecutionStep source) {
        return switch (source.status()) {
            case COMPLETED -> target.withResult(source.result());
            case SKIPPED -> target.withSkipped(source.result());
            case FAILED -> target.withFailed(source.result());
            case PENDING, RUNNING -> target;
        };
    }

    private ExecutionStep stepById(List<ExecutionStep> steps, String stepId) {
        if (steps == null || stepId == null || stepId.isBlank()) {
            return null;
        }
        for (ExecutionStep step : steps) {
            if (step.id().equals(stepId)) {
                return step;
            }
        }
        return null;
    }

    private String stepFingerprint(ExecutionStep step) {
        if (step == null) {
            return "";
        }
        return String.join("|",
                normalizeFingerprintPart(step.type()),
                normalizeFingerprintPart(step.description()),
                joinSorted(step.requiredTools()),
                joinSorted(step.writeScope()),
                normalizeFingerprintPart(step.preferredAgent()),
                normalizeFingerprintPart(step.riskLevel()),
                joinSorted(step.dependencies()));
    }

    private boolean isMutatingStep(ExecutionStep step) {
        if (step == null) {
            return false;
        }
        List<String> requiredTools = step.requiredTools() == null ? List.of() : step.requiredTools();
        if (requiredTools.stream().anyMatch(tool -> "write_file".equalsIgnoreCase(tool)
                || "create_project".equalsIgnoreCase(tool))) {
            return true;
        }
        boolean usesCommand = requiredTools.stream().anyMatch(tool -> "execute_command".equalsIgnoreCase(tool));
        if (usesCommand) {
            String riskLevel = step.riskLevel() == null ? "" : step.riskLevel().trim().toLowerCase(Locale.ROOT);
            return !"low".equals(riskLevel);
        }
        return false;
    }

    private static String normalizeFingerprintPart(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String joinSorted(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(value -> value.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                .sorted()
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * 解析规划者输出的 JSON 计划
     */
    List<ExecutionStep> parsePlan(String planJson) {
        try {
            PlanSchema schema = planSchemaParser.parse(planJson);
            PlanValidationResult validation = planSchemaValidator.validate(schema);
            if (!validation.isValid()) {
                log.warn("Plan schema invalid: {}", validation.toIOException().getMessage());
                return List.of();
            }

            List<PlanTaskSpec> specs = schema.tasks();
            List<ExecutionStep> steps = new ArrayList<>(specs.size());
            Map<String, String> idMapping = new HashMap<>();

            int stepIndex = 1;
            for (PlanTaskSpec spec : specs) {
                String originalId = spec.id();
                String newId = "step_" + stepIndex++;
                idMapping.put(originalId, newId);
                steps.add(ExecutionStep.pending(newId, spec.description(), spec.type().name(), new ArrayList<>(),
                        spec.requiredTools(), spec.preferredAgent(), spec.riskLevel(), spec.writeScope()));
            }

            // 第二遍：建立依赖
            stepIndex = 1;
            for (PlanTaskSpec spec : specs) {
                String newId = "step_" + stepIndex++;
                List<String> deps = new ArrayList<>();
                for (String dep : spec.dependencies()) {
                    String mapped = idMapping.get(dep);
                    if (mapped != null) {
                        deps.add(mapped);
                    }
                }
                int idx = stepIndex - 2;
                if (idx >= 0 && idx < steps.size()) {
                    ExecutionStep old = steps.get(idx);
                    steps.set(idx, new ExecutionStep(old.id(), old.description(), old.type(),
                            deps, old.requiredTools(), old.preferredAgent(), old.riskLevel(),
                            old.writeScope(), old.result(), old.status()));
                }
            }

            return steps;
        } catch (Exception e) {
            log.error("Failed to parse plan JSON", e);
            return List.of();
        }
    }

    /**
     * 获取当前可执行的步骤（依赖已全部完成）
     */
    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        DependencyGraph<ExecutionStep> graph = DependencyGraph.of(
                steps,
                ExecutionStep::id,
                ExecutionStep::dependencies);
        return graph.readyNodes(
                step -> step.status() == StepStatus.PENDING,
                dependencyId -> {
                    StepStatus status = statusMap.get(dependencyId);
                    // COMPLETED（正常）和 SKIPPED（显式降级）可放行；
                    // FAILED 表示依赖结果不可用，必须阻断下游步骤。
                    return status == StepStatus.COMPLETED || status == StepStatus.SKIPPED;
                });
    }

    /**
     * 解析自审阶段的审批结果
     *
     * 解析失败时采取保守策略：默认判为"不通过"，避免异常输出时让问题结果直接放行。
     */
    boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            log.warn("Review returned empty content, defaulting to rejected");
            return false;
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode approvedNode = root.path("approved");
            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                log.warn("Review JSON missing 'approved' field, defaulting to rejected");
                return false;
            }
            return approvedNode.asBoolean(false);
        } catch (Exception e) {
            // 无法解析 JSON：必须同时不含否定关键词且含有肯定关键词，才视为通过
            String lower = reviewContent.toLowerCase();
            boolean hasNegativeKeyword = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean hasPositiveKeyword = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");
            if (hasNegativeKeyword) {
                return false;
            }
            if (!hasPositiveKeyword) {
                log.warn("Review output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    /**
     * 解析自审反馈的问题
     */
    String parseReviewIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            return "";
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);

            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isArray() && !issuesNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode issue : issuesNode) {
                    sb.append("- ").append(issue.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            JsonNode suggestionsNode = root.path("suggestions");
            if (suggestionsNode.isArray() && !suggestionsNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode suggestion : suggestionsNode) {
                    sb.append("- ").append(suggestion.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            // 返回 summary 作为备选
            String summary = root.path("summary").asText();
            if (!summary.isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
        }
        return "审查未通过，请改进执行结果";
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 获取工具注册表（用于同步项目路径）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    private void appendRunEvent(AgentRunContext context, AgentRunEventType type) {
        appendRunEvent(context, type, Map.of());
    }

    private void appendRunEvent(AgentRunContext context, AgentRunEventType type, Map<String, String> attributes) {
        if (isRuntimeOwnedLifecycleEvent(context, type)) {
            return;
        }
        activeRunStore.append(AgentRunEvent.of(context, type, attributes));
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

    private AgentRunContext childRunContext(AgentRunContext parent, String role, String stepId, int attempt,
                                            AgentProfile profile, AgentTaskRequirements requirements,
                                            String selectedReason,
                                            List<String> writeScope,
                                            List<String> forbiddenWriteScope) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("parentRunId", parent.runId());
        metadata.put("rootRunId", parent.metadata().getOrDefault("rootRunId", parent.runId()));
        metadata.put("role", role);
        metadata.put("attempt", String.valueOf(attempt));
        if (stepId != null && !stepId.isBlank()) {
            metadata.put("stepId", stepId);
        }
        if (profile != null) {
            metadata.put("profileName", profile.name());
            metadata.put("profileRole", profile.role().name());
            metadata.put("permissionMode", profile.permissionMode());
            metadata.put("allowedTools", AgentToolPolicy.formatTools(profile.tools()));
            metadata.put("deniedTools", AgentToolPolicy.formatTools(profile.deniedTools()));
            metadata.put("commandAllowlist", AgentToolPolicy.formatTools(profile.commandAllowlist()));
            metadata.put("memoryScope", profile.memoryScope());
            metadata.put("model", profile.model());
            metadata.put("contextMode", profile.contextMode());
            metadata.put("approvalPolicy", profile.approvalPolicy());
        }
        if (requirements != null) {
            metadata.put("requiredTools", AgentToolPolicy.formatTools(requirements.requiredTools()));
            metadata.put("preferredAgent", requirements.preferredAgent());
            metadata.put("riskLevel", requirements.riskLevel());
        }
        if (selectedReason != null && !selectedReason.isBlank()) {
            metadata.put("selectedReason", selectedReason);
        }
        if (writeScope != null && !writeScope.isEmpty()) {
            metadata.put("writeScope", formatScopes(writeScope));
        }
        if (forbiddenWriteScope != null && !forbiddenWriteScope.isEmpty()) {
            metadata.put("forbiddenWriteScope", formatScopes(forbiddenWriteScope));
        }
        return AgentRunContext.create(parent.mode(), parent.input(), parent.workspace(), metadata);
    }

    private void appendAgentSelected(AgentRunContext parentContext, String role, ExecutionStep step,
                                     AgentProfile profile, AgentTaskRequirements requirements,
                                     String selectedReason, List<String> forbiddenWriteScope) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("stepId", step.id());
        attributes.put("role", role);
        attributes.put("profileName", profile.name());
        attributes.put("profileRole", profile.role().name());
        attributes.put("permissionMode", profile.permissionMode());
        attributes.put("requiredTools", AgentToolPolicy.formatTools(requirements.requiredTools()));
        attributes.put("preferredAgent", requirements.preferredAgent());
        attributes.put("riskLevel", requirements.riskLevel());
        attributes.put("selectedReason", selectedReason);
        if (step.writeScope() != null && !step.writeScope().isEmpty()) {
            attributes.put("writeScope", formatScopes(step.writeScope()));
        }
        if (forbiddenWriteScope != null && !forbiddenWriteScope.isEmpty()) {
            attributes.put("forbiddenWriteScope", formatScopes(forbiddenWriteScope));
        }
        appendRunEvent(parentContext, AgentRunEventType.AGENT_SELECTED, attributes);
    }

    private void appendChildRunStarted(AgentRunContext context, String phase) {
        appendRunEvent(context, AgentRunEventType.RUN_STARTED, Map.of("phase", phase));
        appendRunEvent(context, AgentRunEventType.MODE_SELECTED, Map.of(
                "phase", phase,
                "adapterMode", "TEAM_CHILD"));
    }

    private void appendChildTerminalEvent(AgentRunContext context, AgentMessage result, String phase) {
        boolean failed = result == null
                || result.type() == AgentMessage.Type.ERROR
                || result.content() == null
                || result.content().isBlank();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("phase", phase);
        attributes.put("status", failed ? AgentRunStatus.FAILED.name() : AgentRunStatus.SUCCESS.name());
        if (failed) {
            attributes.put("error", resultContent(result));
        }
        appendRunEvent(context, failed ? AgentRunEventType.RUN_FAILED : AgentRunEventType.RUN_FINISHED, attributes);
    }

    private AgentMessage executeWorkerChild(AgentRunContext parentContext, ExecutionStep step,
                                            SubAgent worker, AgentMessage taskMsg, String context,
                                            PrintStream out, int attempt,
                                            AgentTaskRequirements requirements,
                                            String selectedReason,
                                            List<String> forbiddenWriteScope) {
        AgentRunContext childContext = childRunContext(parentContext, childRoleName(worker.getRole()), step.id(), attempt,
                worker.getProfile(), requirements, selectedReason, step.writeScope(), forbiddenWriteScope);
        appendChildRunStarted(childContext, "execute");
        AgentMessage result;
        try {
            result = worker.executeWithContext(taskMsg, context, out, childContext, activeRunStore);
        } catch (RuntimeException e) {
            result = AgentMessage.error(worker.getName(), worker.getRole(), errorMessage(e));
        }
        appendRunEvent(childContext, AgentRunEventType.LLM_RESPONSE, Map.of(
                "phase", "execute",
                "agent", worker.getName(),
                "messageType", result == null ? "null" : result.type().name()));
        appendChildTerminalEvent(childContext, result, "execute");
        return result;
    }

    private ReviewChildResult executeSelfReviewChild(AgentRunContext parentContext, ExecutionStep step,
                                                     SubAgent agent, String executionResult,
                                                     PrintStream out, int attempt,
                                                     AgentTaskRequirements requirements,
                                                     String selectedReason,
                                                     List<String> forbiddenWriteScope) {
        AgentRunContext childContext = childRunContext(parentContext, childRoleName(agent.getRole()), step.id(), attempt,
                agent.getProfile(), requirements, selectedReason, step.writeScope(), forbiddenWriteScope);
        appendChildRunStarted(childContext, "review");
        AgentMessage result;
        try {
            result = agent.review(step.description(), executionResult, out, childContext, activeRunStore);
        } catch (RuntimeException e) {
            result = AgentMessage.error(agent.getName(), agent.getRole(), errorMessage(e));
        }
        appendRunEvent(childContext, AgentRunEventType.LLM_RESPONSE, Map.of(
                "phase", "review",
                "agent", agent.getName(),
                "messageType", result == null ? "null" : result.type().name()));
        return new ReviewChildResult(childContext, result);
    }

    private void appendReviewDecisionEvent(AgentRunContext context, boolean approved,
                                           AgentRunStatus status, String issues) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("phase", "review");
        attributes.put("status", status.name());
        attributes.put("businessStatus", status.name());
        attributes.put("approved", String.valueOf(approved));
        if (issues != null && !issues.isBlank()) {
            attributes.put("issues", issues);
        }
        appendRunEvent(context,
                approved ? AgentRunEventType.RUN_FINISHED : AgentRunEventType.RUN_FAILED,
                attributes);
    }

    private static String resultContent(AgentMessage result) {
        if (result == null || result.content() == null || result.content().isBlank()) {
            return "unknown error";
        }
        return result.content();
    }

    private static String errorMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * 并行执行一批相互独立的步骤。
     *
     * 每个步骤获取一个执行 profile lease（池化，避免同一 profile 被两个步骤并发占用），
     * 执行和自审共用同一个 SubAgent 实例；所有任务完成后按 step_id 顺序将缓冲区 flush 到 stdout。
     */
    private void runBatchParallel(AgentRunContext runContext, List<ExecutionStep> batch, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount) {
        int parallelism = Math.min(batch.size(), batchParallelism(batch));
        //创建固定线程池，线程命名、设为守护线程
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "mindcli-multi-agent");
            t.setDaemon(true);
            return t;
        });
        //存放每个步骤独立输出缓冲区，并发Map保证多线程安全读写
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        //保存所有异步任务句柄，用于后续阻塞等待全部执行完成
        List<Future<?>> futures = new ArrayList<>();

        for (ExecutionStep step : batch) {
            //1.为当前步骤创建独立内存缓冲区
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
            //组装该步骤需要的全局上下文：前置步骤结果、项目信息、变量等
            String context = buildStepContext(steps, step);

            futures.add(executor.submit(() -> {
                try {
                    runStep(runContext, step, steps, retryCount, context, stepOut);
                } catch (RuntimeException e) {
                    log.error("Parallel step {} failed unexpectedly", step.id(), e);
                    updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + e.getMessage() + "\n");
                } finally {
                    stepOut.flush();
                }
                return null;
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Batch wait interrupted");
            } catch (ExecutionException e) {
                log.error("Parallel step task failed", e.getCause());
            }
        }
        executor.shutdownNow();

        // 按 step_id 顺序 flush 各步骤的缓冲输出，保证用户看到的执行过程有稳定顺序
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buf = buffers.get(step.id());
            if (buf != null && buf.size() > 0) {
                out.print(buf.toString(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /**
     * 执行单个步骤（Worker/Explorer 执行 + 自审 + 最多 2 次重试）。
     *
     * 此方法被串行和并行两条路径共享，通过 {@code out} 控制流式输出目的地。
     */
    private void runStep(AgentRunContext runContext, ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount,
                         String context, PrintStream out) {
        AgentTaskRequirements workerRequirements = requirementsFor(step);
        List<String> forbiddenWriteScope = forbiddenWriteScopes(steps, step);
        try (AgentPool.AgentLease workerLease = acquireForStep(step, workerRequirements)) {
            SubAgent worker = createSubAgent(workerLease.profile());
            appendAgentSelected(runContext, childRoleName(worker.getRole()), step, workerLease.profile(),
                    workerRequirements, workerLease.selectionReason(), forbiddenWriteScope);
            runStepWithWorker(runContext, step, steps, retryCount, worker, context, out,
                    workerRequirements, workerLease.selectionReason(), forbiddenWriteScope);
        } catch (IllegalStateException e) {
            updateStep(steps, step.id(), step.withFailed("Agent profile 选择失败: " + e.getMessage()));
            out.println("❌ 步骤 [" + step.id() + "] Agent profile 选择失败：" + e.getMessage() + "\n");
        }
    }

    /**
     * 委派决策：preferredAgent 优先（跨 role 命中自定义/内置 agent），未命中回退只读/写入二分。
     */
    private AgentPool.AgentLease acquireForStep(ExecutionStep step, AgentTaskRequirements requirements) {
        String preferred = step.preferredAgent();
        if (preferred != null && !preferred.isBlank() && agentPool.hasProfile(preferred)) {
            return agentPool.acquireByName(preferred, requirements);
        }
        return agentPool.acquire(executionRoleFor(step), requirements);
    }

    private void runStepWithWorker(AgentRunContext runContext, ExecutionStep step, List<ExecutionStep> steps,
                                   Map<String, Integer> retryCount,
                                   SubAgent worker, String context, PrintStream out,
                                   AgentTaskRequirements workerRequirements,
                                   String workerSelectionReason,
                                   List<String> forbiddenWriteScope) {
        out.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = executeWorkerChild(runContext, step, worker, taskMsg, context, out, 0,
                workerRequirements, workerSelectionReason, forbiddenWriteScope);
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        if (result.type() == AgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：" + result.content() + "\n");
            return;
        }
        if (result.content() == null || result.content().isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：结果为空\n");
            return;
        }

        out.println("🔍 " + worker.getName() + " 正在自审步骤 [" + step.id() + "] 的结果...");
        ReviewChildResult reviewChild = executeSelfReviewChild(runContext, step, worker, result.content(), out, 0,
                workerRequirements, workerSelectionReason, forbiddenWriteScope);
        AgentMessage reviewResult = reviewChild.message();

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            log.warn("Self-review failed for step {}: {}", step.id(), reviewResult.content());
            appendReviewDecisionEvent(reviewChild.context(), false, AgentRunStatus.FAILED,
                    "审查阶段失败：" + resultContent(reviewResult));
            out.println("❌ 步骤 [" + step.id() + "] 审查阶段 LLM 调用失败，结果未通过验证\n");
            updateStep(steps, step.id(), step.withFailed("审查阶段失败：" + resultContent(reviewResult)));
            return;
        }

        boolean approved = parseReviewApproval(reviewResult.content());
        String acceptedResult = result.content();

        if (approved) {
            appendReviewDecisionEvent(reviewChild.context(), true, AgentRunStatus.SUCCESS, "");
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
            return;
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = parseReviewIssues(reviewResult.content());
        appendReviewDecisionEvent(reviewChild.context(), false, AgentRunStatus.BLOCKED, issues);
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries++;
            retryCount.put(step.id(), retries);
            out.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，正在重新执行...");
            out.println("   反馈: " + issues + "\n");

            String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
            AgentMessage retryResult = executeWorkerChild(runContext, step, worker, taskMsg, feedbackContext, out, retries,
                    workerRequirements, workerSelectionReason, forbiddenWriteScope);
            if (retryResult.type() == AgentMessage.Type.ERROR) {
                log.warn("Step {} retry {} failed at LLM layer: {}", step.id(), retries, retryResult.content());
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                approved = false;
                continue;
            }
            if (retryResult.content() == null || retryResult.content().isBlank()) {
                acceptedResult = "执行结果为空";
                approved = false;
                issues = "执行结果为空";
                log.info("Step {} retry {} returned empty result", step.id(), retries);
                continue;
            }

            acceptedResult = retryResult.content();
            ReviewChildResult retryReviewChild = executeSelfReviewChild(runContext, step, worker, acceptedResult, out, retries,
                    workerRequirements, workerSelectionReason, forbiddenWriteScope);
            AgentMessage retryReview = retryReviewChild.message();

            if (retryReview.type() == AgentMessage.Type.ERROR) {
                log.warn("Self-review failed for step {} retry {}: {}", step.id(), retries, retryReview.content());
                approved = false;
                issues = "重试审查失败：" + resultContent(retryReview);
                appendReviewDecisionEvent(retryReviewChild.context(), false, AgentRunStatus.FAILED, issues);
                break;
            }

            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
            appendReviewDecisionEvent(retryReviewChild.context(), approved,
                    approved ? AgentRunStatus.SUCCESS : AgentRunStatus.BLOCKED,
                    approved ? "" : issues);
        }

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("✅ 步骤 [" + step.id() + "] 重试后审查通过\n");
        } else {
            updateStep(steps, step.id(), step.withFailed("审查未通过：" + issues
                    + "\n候选结果：" + acceptedResult));
            out.println("❌ 步骤 [" + step.id() + "] 超过最大重试次数，结果未通过审查\n");
        }
    }

    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文：\n");

        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");
                if (step.result() != null && !step.result().isBlank()) {
                    String preview = step.result().length() > 500
                            ? step.result().substring(0, 500) + "..."
                            : step.result();
                    context.append("结果：").append(preview).append("\n");
                }
                context.append("\n");
            }
        }

        appendFileOwnershipContext(context, steps, currentStep);

        return context.toString();
    }

    private void appendFileOwnershipContext(StringBuilder context, List<ExecutionStep> steps,
                                            ExecutionStep currentStep) {
        if (context == null || currentStep == null || !isMutatingStep(currentStep)) {
            return;
        }
        List<String> allowed = normalizeScopes(currentStep.writeScope());
        List<String> forbidden = forbiddenWriteScopes(steps, currentStep);
        if (allowed.isEmpty() && forbidden.isEmpty()) {
            return;
        }

        context.append("文件所有权边界：\n");
        if (allowed.isEmpty()) {
            context.append("- 允许修改范围：未声明。不要扩大修改范围；如必须写文件，请先说明需要的范围。\n");
        } else {
            context.append("- 允许修改范围：").append(formatScopes(allowed)).append("\n");
        }
        if (!forbidden.isEmpty()) {
            context.append("- 禁止修改范围：").append(formatScopes(forbidden)).append("\n");
        }
        context.append("- 如果任务需要越界修改，必须停止并说明原因，不得擅自修改禁止范围。\n\n");
    }

    private List<String> forbiddenWriteScopes(List<ExecutionStep> steps, ExecutionStep currentStep) {
        if (steps == null || currentStep == null) {
            return List.of();
        }
        Set<String> forbidden = new LinkedHashSet<>();
        for (ExecutionStep step : steps) {
            if (step == null || step.id().equals(currentStep.id()) || !isMutatingStep(step)) {
                continue;
            }
            forbidden.addAll(normalizeScopes(step.writeScope()));
        }
        return List.copyOf(forbidden);
    }

    private static List<String> normalizeScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        return scopes.stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String formatScopes(List<String> scopes) {
        return String.join(",", normalizeScopes(scopes));
    }

    private StepStatus getStepStatus(String stepId, List<ExecutionStep> steps) {
        for (ExecutionStep step : steps) {
            if (step.id().equals(stepId)) return step.status();
        }
        return StepStatus.PENDING;
    }

    private String summarizeSteps(List<ExecutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (ExecutionStep step : steps) {
            String deps = step.dependencies().isEmpty() ? "无"
                    : String.join(", ", step.dependencies());
            String icon = switch (step.status()) {
                case COMPLETED -> "✅";
                case FAILED -> "❌";
                case SKIPPED -> "⏭️";
                default -> "⏳";
            };
            sb.append(String.format("  %s [%s] %s (依赖: %s)%n",
                    icon, step.id(), step.description(), deps));
        }
        return sb.toString();
    }

    /**
     * 构建最终汇总。
     *
     * 注意：Worker/Explorer 的完整输出在执行阶段已经通过流式渲染打印给用户，
     * 此处只返回"步骤状态 + 简短预览"作为总结，避免同一段内容被打印 2-3 次。
     */
    private String buildFinalResult(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean allDone = steps.stream().allMatch(step ->
                step.status() == StepStatus.COMPLETED || step.status() == StepStatus.SKIPPED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);
        boolean hasSkippedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.SKIPPED);

        if (allCompleted) {
            result.append("✅ 多 Agent 协作任务完成！\n\n");
        } else if (allDone && hasSkippedSteps) {
            result.append("⚠️ 多 Agent 协作任务完成（部分步骤已跳过）。\n\n");
        } else if (hasFailedSteps) {
            result.append("⚠️ 多 Agent 协作任务未完全完成，存在失败步骤。\n\n");
        } else {
            result.append("⚠️ 多 Agent 协作任务部分完成，仍有未执行步骤。\n\n");
        }
        result.append("📋 执行总结：\n");
        List<DependencyGraph.BlockedNode<ExecutionStep>> blockedSteps = blockedSteps(steps);

        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            if (step.status() == StepStatus.COMPLETED) {
                result.append("✅ ");
            } else if (step.status() == StepStatus.FAILED) {
                result.append("❌ ");
            } else if (step.status() == StepStatus.SKIPPED) {
                result.append("⏭️ ");
            } else {
                result.append("⏳ ");
            }
            result.append(step.description()).append("\n");

            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 120
                        ? step.result().substring(0, 120) + "..."
                        : step.result();
                result.append("   结果：").append(preview).append("\n");
            } else if (step.status() == StepStatus.PENDING) {
                blockedSteps.stream()
                        .filter(blocked -> blocked.node().id().equals(step.id()))
                        .findFirst()
                        .map(blocked -> formatBlockedDependencies(blocked, step.dependencies(), steps))
                        .filter(reason -> !reason.isBlank())
                        .ifPresent(reason -> result.append("   阻塞：").append(reason).append("\n"));
            }
        }

        return result.toString();
    }

    /**
     * 内联规划的流式渲染器，仅展示"规划思考"（reasoning），计划 JSON 不直接打印。
     */
    private static final class PlanningStreamRenderer implements LlmClient.StreamListener {
        private final PrintStream out;
        private TerminalMarkdownRenderer reasoningRenderer;
        private boolean reasoningStarted;
        private boolean streamed;

        private PlanningStreamRenderer(PrintStream out) {
            this.out = out == null ? System.out : out;
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!reasoningStarted) {
                out.println(AnsiStyle.heading("🧠 规划思考"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningStarted = true;
                streamed = true;
            }
            reasoningRenderer.append(delta);
            out.flush();
        }

        private void finish() {
            if (streamed) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                out.println("\n");
            }
        }
    }
}
