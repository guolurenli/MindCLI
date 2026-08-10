package com.mindcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.agent.profile.AgentPool;
import com.mindcli.agent.profile.AgentProfile;
import com.mindcli.agent.profile.AgentProfileLoader;
import com.mindcli.agent.profile.AgentTaskRequirements;
import com.mindcli.agent.profile.AgentToolPolicy;
import com.mindcli.llm.LlmClient;
import com.mindcli.memory.MemoryManager;
import com.mindcli.plan.PlanSchema;
import com.mindcli.plan.PlanSchemaParser;
import com.mindcli.plan.PlanSchemaValidator;
import com.mindcli.plan.PlanTaskSpec;
import com.mindcli.plan.PlanValidationResult;
import com.mindcli.runtime.CancellationContext;
import com.mindcli.runtime.agent.AgentMode;
import com.mindcli.runtime.agent.AgentRunContext;
import com.mindcli.runtime.agent.AgentRunEvent;
import com.mindcli.runtime.agent.AgentRunEventType;
import com.mindcli.runtime.agent.AgentRunStatus;
import com.mindcli.runtime.agent.RunStore;
import com.mindcli.runtime.agent.RunStoreFactory;
import com.mindcli.skill.SkillRegistry;
import com.mindcli.tool.ToolRegistry;
import com.mindcli.util.AnsiStyle;
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
 * 1. 用户提交任务 -> 编排器交给规划者
 * 2. 规划者拆解任务 -> 编排器解析计划
 * 3. 编排器按依赖顺序将子任务分配给执行者
 * 4. 执行者返回结果 -> 编排器交给检查者
 * 5. 检查者通过则完成，否则带上反馈重新分配给执行者
 * 6. 所有子任务完成后，编排器汇总返回最终结果
 *
 * 并行策略：
 * - 同一依赖批次内部 **并行** 执行（最多 Worker 池大小并发，默认 2）
 * - 每个并行步骤使用独立的 PrintStream 缓冲流式输出，批次结束后按 step_id 顺序 flush 到 stdout，
 *   避免多线程写同一个终端流造成交错，同时仍让用户看到结构化的执行过程
 * - 单步批次仍走直连流式路径，保持"实时打字"的观感
 * - Worker 通过 {@link java.util.concurrent.BlockingQueue} 池化分配，确保同一 Worker 不会被两个步骤并发占用
 * - Reviewer 在并行路径中按步骤即时创建独立实例，避免对话历史竞争
 */
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RETRIES_PER_STEP = 2;

    private final LlmClient llmClient;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;
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
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;

    // 执行步骤的数据结构（package-private 供测试访问）
    record ExecutionStep(String id, String description, String type,
                                  List<String> dependencies, List<String> requiredTools,
                                  String preferredAgent, String riskLevel,
                                  String result, StepStatus status) {
        static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return pending(id, description, type, dependencies, List.of(), "", "");
        }

        static ExecutionStep pending(String id, String description, String type, List<String> dependencies,
                                     List<String> requiredTools, String preferredAgent, String riskLevel) {
            return new ExecutionStep(id, description, type, dependencies,
                    requiredTools == null ? List.of() : List.copyOf(requiredTools),
                    preferredAgent == null ? "" : preferredAgent,
                    riskLevel == null || riskLevel.isBlank() ? "low" : riskLevel,
                    null, StepStatus.PENDING);
        }

        ExecutionStep withResult(String result) {
            return new ExecutionStep(id, description, type, dependencies, requiredTools,
                    preferredAgent, riskLevel, result, StepStatus.COMPLETED);
        }

        ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies, requiredTools,
                    preferredAgent, riskLevel, result, StepStatus.FAILED);
        }

        ExecutionStep withSkipped(String reason) {
            return new ExecutionStep(id, description, type, dependencies,
                    requiredTools, preferredAgent, riskLevel,
                    reason != null ? reason : "步骤被跳过", StepStatus.SKIPPED);
        }

        ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, requiredTools,
                    preferredAgent, riskLevel, result, StepStatus.RUNNING);
        }
    }

    private record ReviewChildResult(AgentRunContext context, AgentMessage message) {
    }

    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
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
        this.planner = createSubAgent(firstProfile(AgentRole.PLANNER, "planner"));
        this.workers = this.agentProfiles.stream()
                .filter(profile -> profile.role() == AgentRole.WORKER)
                .map(this::createSubAgent)
                .toList();
        this.reviewer = createSubAgent(firstProfile(AgentRole.REVIEWER, "reviewer"));
        this.runStore = runStore == null ? RunStoreFactory.create() : runStore;
        this.activeRunStore = this.runStore;
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        planner.setExternalContextSupplier(this.externalContextSupplier);
        workers.forEach(worker -> worker.setExternalContextSupplier(this.externalContextSupplier));
        reviewer.setExternalContextSupplier(this.externalContextSupplier);
    }

    /**
     * 把 Skill 系统下发给所有 SubAgent。
     */
    public void setSkillSystem(com.mindcli.skill.SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        planner.setSkillRegistry(skillRegistry);
        for (SubAgent worker : workers) {
            worker.setSkillRegistry(skillRegistry);
        }
        reviewer.setSkillRegistry(skillRegistry);
    }

    private AgentProfile firstProfile(AgentRole role, String fallbackName) {
        return agentProfiles.stream()
                .filter(profile -> profile.role() == role)
                .findFirst()
                .orElseGet(() -> AgentProfile.legacy(fallbackName, role));
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

    private int workerParallelism() {
        int configured = agentProfiles.stream()
                .filter(profile -> profile.role() == AgentRole.WORKER)
                .mapToInt(AgentProfile::maxConcurrency)
                .sum();
        return Math.max(1, configured);
    }

    private AgentTaskRequirements requirementsFor(ExecutionStep step) {
        return new AgentTaskRequirements(
                step.id(),
                step.requiredTools(),
                step.preferredAgent(),
                step.riskLevel());
    }

    private AgentTaskRequirements reviewerRequirementsFor(ExecutionStep step) {
        return new AgentTaskRequirements(
                step.id(),
                List.of(),
                "",
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

        // 1. 规划阶段：让规划者拆解任务
        out.println(AnsiStyle.heading("📋 第一阶段：规划"));
        out.println("🧑‍💼 规划者正在分析任务...\n");

        AgentMessage planMessage = AgentMessage.task("orchestrator",
                "请为以下任务制定执行计划：\n" + userInput);
        AgentRunContext plannerRunContext = childRunContext(runContext, "planner", null, 0,
                planner.getProfile(), null, "planner profile");
        appendChildRunStarted(plannerRunContext, "plan");
        AgentMessage planResult = planner.executeWithRunContext(planMessage, out, plannerRunContext, activeRunStore);
        appendRunEvent(plannerRunContext, AgentRunEventType.LLM_RESPONSE, Map.of(
                "phase", "plan",
                "messageType", planResult.type().name()));
        appendChildTerminalEvent(plannerRunContext, planResult, "plan");
        planner.clearHistory();
        if (CancellationContext.isCancelled()) {
            String cancelled = "⏹️ 已取消当前多 Agent 任务。";
            appendTerminalEvent(runContext, cancelled);
            return cancelled;
        }

        if (planResult.type() == AgentMessage.Type.ERROR) {
            String failed = "❌ 规划阶段失败，规划者 LLM 调用出错：" + planResult.content();
            appendRunEvent(runContext, AgentRunEventType.RUN_FAILED, Map.of(
                    "status", AgentRunStatus.FAILED.name(),
                    "phase", "plan"));
            return failed;
        }
        if (planResult.content() == null || planResult.content().isBlank()) {
            String failed = "❌ 规划失败：规划者未能生成有效计划";
            appendRunEvent(runContext, AgentRunEventType.RUN_FAILED, Map.of(
                    "status", AgentRunStatus.FAILED.name(),
                    "phase", "plan"));
            return failed;
        }

        // 2. 解析计划
        List<ExecutionStep> steps = parsePlan(planResult.content());
        if (steps.isEmpty()) {
            String failed = "❌ 规划失败：无法解析执行计划\n原始输出:\n" + planResult.content();
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
            batchIndex++;

            if (executable.size() == 1) {
                // 单步批次：直接串行流式输出，保持实时打字观感
                ExecutionStep step = executable.get(0);
                String context = buildStepContext(steps, step);
                runStep(runContext, step, steps, retryCount, context, out);
            } else {
                // 多步批次：真正并行执行，每步用独立的 PrintStream 缓冲，完成后按 step_id 顺序 flush
                out.println("⚡ 批次 #" + batchIndex + "：" + executable.size()
                        + " 个独立步骤并行执行（最多 " + workerParallelism() + " 个并发 Worker）\n");
                runBatchParallel(runContext, executable, steps, retryCount);
            }
        }

        // 5. 处理未能执行的残留步骤（显式提示用户）
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                // 列出其依赖状态，帮助用户理解为何未能执行
                List<String> depStatus = step.dependencies().stream()
                        .map(dep -> dep + "=" + getStepStatus(dep, steps))
                        .toList();
                out.println("⏭️ 步骤 [" + step.id() + "] 未能执行（依赖状态: "
                        + String.join(", ", depStatus) + "）: " + step.description());
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
                        spec.requiredTools(), spec.preferredAgent(), spec.riskLevel()));
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
                            old.result(), old.status()));
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

        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> {
                            StepStatus s = statusMap.get(dep);
                            // COMPLETED（正常）和 SKIPPED（显式降级）可放行；
                            // FAILED 表示依赖结果不可用，必须阻断下游步骤。
                            return s == StepStatus.COMPLETED
                                || s == StepStatus.SKIPPED;
                        }))
                .toList();
    }

    /**
     * 解析检查者的审批结果
     *
     * 解析失败时采取保守策略：默认判为"不通过"，避免在审查者异常输出时让问题结果直接放行。
     */
    boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            log.warn("Reviewer returned empty content, defaulting to rejected");
            return false;
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode approvedNode = root.path("approved");
            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                log.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
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
                log.warn("Reviewer output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    /**
     * 解析检查者反馈的问题
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
                                            String selectedReason) {
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
        }
        if (requirements != null) {
            metadata.put("requiredTools", AgentToolPolicy.formatTools(requirements.requiredTools()));
            metadata.put("preferredAgent", requirements.preferredAgent());
            metadata.put("riskLevel", requirements.riskLevel());
        }
        if (selectedReason != null && !selectedReason.isBlank()) {
            metadata.put("selectedReason", selectedReason);
        }
        return AgentRunContext.create(parent.mode(), parent.input(), parent.workspace(), metadata);
    }

    private void appendAgentSelected(AgentRunContext parentContext, String role, ExecutionStep step,
                                     AgentProfile profile, AgentTaskRequirements requirements,
                                     String selectedReason) {
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
                                            String selectedReason) {
        AgentRunContext childContext = childRunContext(parentContext, "worker", step.id(), attempt,
                worker.getProfile(), requirements, selectedReason);
        appendChildRunStarted(childContext, "execute");
        AgentMessage result;
        try {
            result = worker.executeWithContext(taskMsg, context, out, childContext, activeRunStore);
        } catch (RuntimeException e) {
            result = AgentMessage.error(worker.getName(), AgentRole.WORKER, errorMessage(e));
        }
        appendRunEvent(childContext, AgentRunEventType.LLM_RESPONSE, Map.of(
                "phase", "execute",
                "agent", worker.getName(),
                "messageType", result == null ? "null" : result.type().name()));
        appendChildTerminalEvent(childContext, result, "execute");
        return result;
    }

    private ReviewChildResult executeReviewerChild(AgentRunContext parentContext, ExecutionStep step,
                                                   SubAgent reviewer, String workerResult,
                                                   PrintStream out, int attempt,
                                                   AgentTaskRequirements requirements,
                                                   String selectedReason) {
        AgentRunContext childContext = childRunContext(parentContext, "reviewer", step.id(), attempt,
                reviewer.getProfile(), requirements, selectedReason);
        appendChildRunStarted(childContext, "review");
        AgentMessage result;
        try {
            result = reviewer.review(step.description(), workerResult, out, childContext, activeRunStore);
        } catch (RuntimeException e) {
            result = AgentMessage.error(reviewer.getName(), AgentRole.REVIEWER, errorMessage(e));
        }
        appendRunEvent(childContext, AgentRunEventType.LLM_RESPONSE, Map.of(
                "phase", "review",
                "agent", reviewer.getName(),
                "messageType", result == null ? "null" : result.type().name()));
        return new ReviewChildResult(childContext, result);
    }

    private void appendReviewerDecisionEvent(AgentRunContext context, boolean approved,
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
     * 每个步骤获取一个 Worker（池化，避免同一 Worker 被两个步骤并发占用），同时创建独立的 Reviewer 实例，
     * 流式输出写入步骤本地的 ByteArrayOutputStream；所有任务完成后按 step_id 顺序将缓冲区 flush 到 stdout。
     */
    private void runBatchParallel(AgentRunContext runContext, List<ExecutionStep> batch, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount) {
        int parallelism = Math.min(batch.size(), workerParallelism());
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
     * 执行单个步骤（Worker 执行 + Reviewer 审查 + 最多 2 次重试）。
     *
     * 此方法被串行和并行两条路径共享，通过 {@code out} 控制流式输出目的地。
     */
    private void runStep(AgentRunContext runContext, ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount,
                         String context, PrintStream out) {
        AgentTaskRequirements workerRequirements = requirementsFor(step);
        AgentTaskRequirements reviewerRequirements = reviewerRequirementsFor(step);
        try (AgentPool.AgentLease workerLease = agentPool.acquire(AgentRole.WORKER, workerRequirements)) {
            SubAgent worker = createSubAgent(workerLease.profile());
            appendAgentSelected(runContext, "worker", step, workerLease.profile(),
                    workerRequirements, workerLease.selectionReason());
            runStepWithWorker(runContext, step, steps, retryCount, worker, context, out,
                    workerRequirements, workerLease.selectionReason(), reviewerRequirements);
        } catch (IllegalStateException e) {
            updateStep(steps, step.id(), step.withFailed("Agent profile 选择失败: " + e.getMessage()));
            out.println("❌ 步骤 [" + step.id() + "] Agent profile 选择失败：" + e.getMessage() + "\n");
        }
    }

    private void runStepWithWorker(AgentRunContext runContext, ExecutionStep step, List<ExecutionStep> steps,
                                   Map<String, Integer> retryCount,
                                   SubAgent worker, String context, PrintStream out,
                                   AgentTaskRequirements workerRequirements,
                                   String workerSelectionReason,
                                   AgentTaskRequirements reviewerRequirements) {
        out.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = executeWorkerChild(runContext, step, worker, taskMsg, context, out, 0,
                workerRequirements, workerSelectionReason);
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

        try (AgentPool.AgentLease reviewerLease = agentPool.acquire(AgentRole.REVIEWER, reviewerRequirements)) {
            SubAgent reviewer = createSubAgent(reviewerLease.profile());
            String reviewerSelectionReason = reviewerLease.selectionReason();
            appendAgentSelected(runContext, "reviewer", step, reviewerLease.profile(),
                    reviewerRequirements, reviewerSelectionReason);
        out.println("🔍 " + reviewer.getName() + " 正在审查步骤 [" + step.id() + "] 的结果...");
        ReviewChildResult reviewChild = executeReviewerChild(runContext, step, reviewer, result.content(), out, 0,
                reviewerRequirements, reviewerSelectionReason);
        AgentMessage reviewResult = reviewChild.message();
        reviewer.clearHistory();

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            log.warn("Reviewer failed for step {}: {}", step.id(), reviewResult.content());
            appendReviewerDecisionEvent(reviewChild.context(), false, AgentRunStatus.FAILED,
                    "审查阶段失败：" + resultContent(reviewResult));
            out.println("❌ 步骤 [" + step.id() + "] 审查阶段 LLM 调用失败，结果未通过验证\n");
            updateStep(steps, step.id(), step.withFailed("审查阶段失败：" + resultContent(reviewResult)));
            return;
        }

        boolean approved = parseReviewApproval(reviewResult.content());
        String acceptedResult = result.content();

        if (approved) {
            appendReviewerDecisionEvent(reviewChild.context(), true, AgentRunStatus.SUCCESS, "");
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
            return;
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = parseReviewIssues(reviewResult.content());
        appendReviewerDecisionEvent(reviewChild.context(), false, AgentRunStatus.BLOCKED, issues);
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries++;
            retryCount.put(step.id(), retries);
            out.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，正在重新执行...");
            out.println("   反馈: " + issues + "\n");

            String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
            AgentMessage retryResult = executeWorkerChild(runContext, step, worker, taskMsg, feedbackContext, out, retries,
                    workerRequirements, workerSelectionReason);
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
            ReviewChildResult retryReviewChild = executeReviewerChild(runContext, step, reviewer, acceptedResult, out, retries,
                    reviewerRequirements, reviewerSelectionReason);
            AgentMessage retryReview = retryReviewChild.message();
            reviewer.clearHistory();

            if (retryReview.type() == AgentMessage.Type.ERROR) {
                log.warn("Reviewer failed for step {} retry {}: {}", step.id(), retries, retryReview.content());
                approved = false;
                issues = "重试审查失败：" + resultContent(retryReview);
                appendReviewerDecisionEvent(retryReviewChild.context(), false, AgentRunStatus.FAILED, issues);
                break;
            }

            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
            appendReviewerDecisionEvent(retryReviewChild.context(), approved,
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

        return context.toString();
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
     * 注意：Worker/Reviewer 的完整输出在执行阶段已经通过流式渲染打印给用户，
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
            }
        }

        return result.toString();
    }
}
