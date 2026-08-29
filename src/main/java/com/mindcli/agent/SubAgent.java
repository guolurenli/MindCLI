package com.mindcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.agent.profile.AgentProfile;
import com.mindcli.agent.profile.AgentToolPolicy;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.LlmTraceLogger;
import com.mindcli.capability.lsp.LspDiagnosticReport;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.capability.memory.TokenBudget;
import com.mindcli.platform.prompt.PromptAssembler;
import com.mindcli.platform.prompt.PromptContext;
import com.mindcli.platform.prompt.PromptMode;
import com.mindcli.platform.prompt.ProjectMemoryLoader;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.RunStore;
import com.mindcli.runtime.run.SessionContext;
import com.mindcli.runtime.run.ToolDispatcher;
import com.mindcli.runtime.run.ToolOutcome;
import com.mindcli.runtime.run.ToolOutcomeEventFactory;
import com.mindcli.runtime.run.ToolOutcomeStatus;
import com.mindcli.capability.skill.SkillIndexFormatter;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.ToolRegistry.ToolExecutionResult;
import com.mindcli.capability.tool.ToolRegistry.ToolInvocation;
import com.mindcli.platform.render.terminal.AnsiStyle;
import com.mindcli.platform.render.terminal.TerminalMarkdownRenderer;
import com.mindcli.capability.image.ImageReferenceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 子代理 - 可配置角色的轻量 Agent
 *
 * 每个 SubAgent 有独立的角色、系统提示词和对话历史，
 * 但共享 LLM 客户端和工具注册表。
 */
public class SubAgent {
    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final AgentProfile profile;
    private final String name;
    private final AgentRole role;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolDispatcher toolDispatcher;
    private final List<LlmClient.Message> conversationHistory;
    private final ThreadLocal<AgentRunContext> activeRunContext = new ThreadLocal<>();
    private final ThreadLocal<RunStore> activeRunStore = new ThreadLocal<>();
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private MemoryManager memoryManager;
    private volatile SessionContext sessionContext;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private volatile boolean readOnly;

    public SubAgent(AgentProfile profile, LlmClient llmClient, ToolRegistry toolRegistry) {
        this.profile = profile;
        this.name = profile.name();
        this.role = profile.role();
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolDispatcher = new ToolDispatcher(toolRegistry);
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.conversationHistory = new ArrayList<>();
        this.conversationHistory.add(LlmClient.Message.system(getSystemPrompt()));
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        refreshSystemPrompt();
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        refreshSystemPrompt();
    }

    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    public void setSessionContext(SessionContext sessionContext) {
        this.sessionContext = sessionContext;
        refreshSystemPrompt();
    }

    /**
     * 根据角色获取系统提示词
     */
    private String getSystemPrompt() {
        PromptContext context = PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .memoryContext(buildSessionContext())
                .externalContext(buildProfileAndExternalContext())
                .skillIndex(buildSkillIndex())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build();
        if (role == AgentRole.CUSTOM) {
            return promptAssembler.assembleCustom(profile.developerInstructions(), context);
        }
        return promptAssembler.assemble(promptMode(), context);
    }

    private String buildSessionContext() {
        SessionContext current = sessionContext;
        int maxTokens = memoryManager == null ? 2_000 : memoryManager.getContextProfile().memoryContextTokens();
        return current == null ? "" : current.promptContext(maxTokens);
    }

    private PromptMode promptMode() {
        return switch (role) {
            case EXPLORER -> PromptMode.TEAM_EXPLORER;
            case WORKER -> PromptMode.TEAM_WORKER;
            case CUSTOM -> PromptMode.TEAM_WORKER;
        };
    }

    /**
     * 截断过长的对话历史，保留 system prompt + 最近 3 个 user 轮次。
     * 触发使用 token 阈值（对齐 Agent.java），避免硬编码消息数。
     * 分割点必须在 user message 边界上，保护 tool_call/tool_result 成对协议。
     */
    private void trimConversationHistory() {
        if (memoryManager == null) return;

        long currentTokens = TokenBudget.estimateMessagesTokens(conversationHistory);
        int triggerTokens = memoryManager.getContextProfile().compressionTriggerTokens();
        if (currentTokens <= triggerTokens) return;

        int systemEnd = "system".equals(conversationHistory.get(0).role()) ? 1 : 0;
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < conversationHistory.size(); i++) {
            if ("user".equals(conversationHistory.get(i).role())) {
                userIndices.add(i);
            }
        }

        final int retainUserRounds = 3;
        if (userIndices.size() <= retainUserRounds) return;

        int splitIdx = userIndices.get(userIndices.size() - retainUserRounds);
        if (splitIdx <= systemEnd) return;

        long beforeTokens = currentTokens;
        int beforeSize = conversationHistory.size();
        List<LlmClient.Message> toKeep = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            toKeep.add(conversationHistory.get(i));
        }
        toKeep.addAll(conversationHistory.subList(splitIdx, conversationHistory.size()));

        conversationHistory.clear();
        conversationHistory.addAll(toKeep);
        log.info("[{}] compaction: {}→{} messages, ~{}→~{} tokens", name,
                beforeSize, conversationHistory.size(), beforeTokens,
                TokenBudget.estimateMessagesTokens(conversationHistory));
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("[{}] failed to build skill index", name, e);
            return "";
        }
    }

    private void refreshSystemPrompt() {
        if (!conversationHistory.isEmpty()) {
            conversationHistory.set(0, LlmClient.Message.system(getSystemPrompt()));
        }
    }

    private String buildExternalContext() {
        if (!toolRegistry.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("[{}] failed to build external context", name, e);
            return "";
        }
    }

    private String buildProfileAndExternalContext() {
        String profileContext = buildProfileContext();
        String externalContext = buildExternalContext();
        if (profileContext.isBlank()) {
            return externalContext;
        }
        if (externalContext.isBlank()) {
            return profileContext;
        }
        return profileContext + "\n\n" + externalContext;
    }

    private String buildProfileContext() {
        return """
                当前 Agent Profile:
                - name: %s
                - role: %s
                - permissionMode: %s
                - allowedTools: %s
                - deniedTools: %s
                - commandAllowlist: %s
                - memoryScope: %s
                - model: %s

                只能调用 allowedTools 声明的工具；如果当前任务需要越权能力，请说明缺少的能力，不要反复调用被拒绝的工具。
                """.formatted(
                profile.name(),
                profile.role().name(),
                profile.permissionMode(),
                AgentToolPolicy.formatTools(profile.tools()),
                AgentToolPolicy.formatTools(profile.deniedTools()),
                AgentToolPolicy.formatTools(profile.commandAllowlist()),
                profile.memoryScope(),
                profile.model()).trim();
    }

    private String buildProjectMemoryContext() {
        try {
            return ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
        } catch (Exception e) {
            log.warn("[{}] failed to load MIND.md project memory", name, e);
            return "";
        }
    }

    /**
     * 执行任务，返回结果消息（默认输出到 System.out）
     */
    public AgentMessage execute(AgentMessage task) {
        return execute(task, System.out);
    }

    /**
     * 执行任务并将流式输出写入指定 PrintStream。并发执行时为每个步骤传入独立的 PrintStream，
     * 避免多个 Agent 同时写入 System.out 造成输出交错。
     */
    public AgentMessage execute(AgentMessage task, PrintStream out) {
        log.info("[{}] executing task from {}: type={}", name, task.fromAgent(), task.type());
        pruneHistoricalImagePayloads();
        refreshSystemPrompt();
        String taskContent = task.content();

        // 检索长期记忆并注入任务上下文（对齐 Agent.java 的记忆检索）
        if (memoryManager != null) {
            memoryManager.resetSurfaced();
            String memoryContext = memoryManager.buildContextForQuery(
                    taskContent,
                    memoryManager.getContextProfile().memoryContextTokens(),
                    activeRunContext.get(),
                    activeRunStore.get());
            if (!memoryContext.isEmpty()) {
                taskContent = "## 相关长期记忆\n\n" + memoryContext + "\n\n## 当前任务\n\n" + taskContent;
            }
        }

        // 将任务注入对话
        conversationHistory.add(ImageReferenceParser.userMessage(
                taskContent,
                Path.of(toolRegistry.getProjectPath())));

        SubAgentStreamRenderer streamRenderer = new SubAgentStreamRenderer(name, role, out);

        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

        // 与 Agent.java 对称：主退出条件 = LLM 自决，budget 仅在 token / 停滞 / 硬轮数兜底。
        while (true) {
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                streamRenderer.finish();
                String description = budget.describeExit(exitReason);
                log.warn("[{}] run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        name, exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                return AgentMessage.error(name, role, description);
            }

            budget.beginIteration();

            // 调 LLM 前评估 conversationHistory 是否接近 window 上限；超阈值压缩早期消息为摘要。
            injectPendingLspDiagnostics(out);
            trimConversationHistory();

            try {
                LlmClient.ChatResponse response = com.mindcli.platform.llm.LlmRetryPolicy.withRetry(() ->
                        llmClient.chat(
                                conversationHistory,
                                toolDefinitionsForProfile(),
                                streamRenderer
                        ),
                        "sub-agent-" + name + "-" + role
                );
                LlmTraceLogger.logReasoning(log,
                        "sub-agent name=" + name + " role=" + role + " iteration=" + budget.iteration(),
                        llmClient,
                        response.reasoningContent());

                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                if (response.hasToolCalls()) {
                    budget.recordToolCalls(response.toolCalls());
                    printToolCalls(out, response.toolCalls());
                    conversationHistory.add(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    // 在工具执行前 flush 并重置流式渲染器：TerminalMarkdownRenderer 按换行 flush，
                    // 没有换行的 pending 内容会被 HITL 提示"跨过"导致标题错位。
                    streamRenderer.resetBetweenIterations();

                    List<ToolExecutionResult> toolResults = executeToolCalls(response.toolCalls());
                    for (ToolExecutionResult toolResult : toolResults) {
                        conversationHistory.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                    }
                    appendImageToolMessages(toolResults);
                    continue;
                }

                // 没有工具调用，返回最终结果
                conversationHistory.add(LlmClient.Message.assistant(response.content()));

                streamRenderer.finish();

                // 增量提取长期记忆，子任务中的关键发现不丢失
                if (memoryManager != null) {
                    memoryManager.extractFactsIncrementalAsync(
                            conversationHistory,
                            activeRunContext.get(),
                            activeRunStore.get());
                }

                return AgentMessage.result(name, role, response.content());

            } catch (Exception e) {
                log.error("[{}] LLM call failed", name, e);
                streamRenderer.finish();
                return AgentMessage.error(name, role, "LLM 调用失败: " + e.getMessage());
            }
        }
    }

    AgentMessage executeWithRunContext(AgentMessage task, PrintStream out, AgentRunContext runContext) {
        return executeWithRunContext(task, out, runContext, null);
    }

    AgentMessage executeWithRunContext(AgentMessage task, PrintStream out, AgentRunContext runContext,
                                       RunStore runStore) {
        return withRuntimeContext(runContext, runStore, () -> execute(task, out));
    }

    /**
     * 直连执行单条任务并返回统一约定字符串（供 {@code /agent <name> <任务>} 与 SingleAgentAdapter 使用）。
     * 返回格式与 TeamModeAdapter 对齐：成功返回正文，错误以 ❌ 开头。
     */
    public String run(String task, PrintStream out, AgentRunContext runContext, RunStore runStore) {
        AgentMessage result = executeWithContext(
                AgentMessage.task("user", task), "", out, runContext, runStore);
        if (result == null) {
            return "❌ 子代理未返回结果";
        }
        if (result.type() == AgentMessage.Type.ERROR) {
            return "❌ " + (result.content() == null ? "执行失败" : result.content());
        }
        return result.content() == null ? "" : result.content();
    }

    /**
     * 执行任务（带上下文注入），用于 Worker 接收额外上下文
     */
    public AgentMessage executeWithContext(AgentMessage task, String context) {
        return executeWithContext(task, context, System.out);
    }

    public AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out) {
        return executeWithContext(task, context, out, null);
    }

    AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out,
                                    AgentRunContext runContext) {
        return executeWithContext(task, context, out, runContext, null);
    }

    AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out,
                                    AgentRunContext runContext, RunStore runStore) {
        String enrichedContent = task.content();
        if (context != null && !context.isEmpty()) {
            enrichedContent = context + "\n\n当前任务：" + task.content();
        }
        AgentMessage enrichedTask = new AgentMessage(task.fromAgent(), task.fromRole(),
                enrichedContent, task.type());
        return executeWithRunContext(enrichedTask, out, runContext, runStore);
    }

    /**
     * 检查执行结果。默认 /team 会由实际执行的 Worker/Explorer 调用此方法完成自审。
     */
    public AgentMessage review(String originalTask, String executionResult) {
        return review(originalTask, executionResult, System.out);
    }

    public AgentMessage review(String originalTask, String executionResult, PrintStream out) {
        return review(originalTask, executionResult, out, null);
    }

    AgentMessage review(String originalTask, String executionResult, PrintStream out,
                        AgentRunContext runContext) {
        return review(originalTask, executionResult, out, runContext, null);
    }

    AgentMessage review(String originalTask, String executionResult, PrintStream out,
                        AgentRunContext runContext, RunStore runStore) {
        String reviewInput = """
                你现在进入当前步骤的自审阶段。请检查执行结果是否正确、完整、可交付。
                自审阶段不要写文件、创建项目或执行有副作用的命令；如需修复，请返回 approved=false 并说明问题。

                请只输出 JSON：
                {
                  "approved": true,
                  "summary": "检查摘要",
                  "issues": [],
                  "suggestions": []
                }

                原始任务：%s

                执行结果：
                %s
                """.formatted(originalTask, executionResult).trim();
        AgentMessage reviewTask = AgentMessage.task("orchestrator", reviewInput);
        readOnly = true;
        try {
            return executeWithRunContext(reviewTask, out, runContext, runStore);
        } finally {
            readOnly = false;
        }
    }

    private AgentMessage withRuntimeContext(AgentRunContext runContext, RunStore runStore,
                                            Supplier<AgentMessage> action) {
        if (runContext == null && runStore == null) {
            return action.get();
        }
        AgentRunContext previousContext = activeRunContext.get();
        RunStore previousStore = activeRunStore.get();
        if (runContext == null) {
            activeRunContext.remove();
        } else {
            activeRunContext.set(runContext);
        }
        if (runStore == null) {
            activeRunStore.remove();
        } else {
            activeRunStore.set(runStore);
        }
        try {
            return action.get();
        } finally {
            if (previousContext == null) {
                activeRunContext.remove();
            } else {
                activeRunContext.set(previousContext);
            }
            if (previousStore == null) {
                activeRunStore.remove();
            } else {
                activeRunStore.set(previousStore);
            }
        }
    }

    /**
     * 清空对话历史（保留系统提示词），用于处理下一个独立任务
     */
    public void clearHistory() {
        LlmClient.Message systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
    }

    private void pruneHistoricalImagePayloads() {
        int messageCount = 0;
        int imageCount = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            LlmClient.Message message = conversationHistory.get(i);
            int images = message.imagePartCount();
            if (images <= 0) {
                continue;
            }
            conversationHistory.set(i, message.withoutImageContent());
            messageCount++;
            imageCount += images;
        }
        if (imageCount > 0) {
            log.info("[{}] pruned historical image payloads before sub-agent turn: messages={}, images={}",
                    name, messageCount, imageCount);
        }
    }

    /**
     * profile 声明了工具能力的子代理才向模型暴露工具。
     */
    private boolean shouldUseTools() {
        return profile != null && !profile.tools().isEmpty();
    }

    /** 有副作用的工具（自审 readOnly 阶段需程序级拦截，不依赖 prompt）。 */
    private static boolean isMutatingTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        return switch (toolName) {
            case "write_file", "create_project", "execute_command", "revert_turn", "save_memory" -> true;
            default -> toolName.startsWith("mcp__");
        };
    }

    private List<LlmClient.Tool> toolDefinitionsForProfile() {
        if (!shouldUseTools() || llmClient == null || !llmClient.supportsTools()) {
            return null;
        }
        List<LlmClient.Tool> definitions = toolRegistry.getToolDefinitions();
        if (profile.tools().contains("*")) {
            return readOnly
                    ? definitions.stream().filter(t -> !isMutatingTool(t.name())).toList()
                    : definitions;
        }
        return definitions.stream()
                .filter(tool -> AgentToolPolicy.toolAllowed(profile, tool.name()))
                .filter(tool -> !readOnly || !isMutatingTool(tool.name()))
                .toList();
    }

    private void injectPendingLspDiagnostics(PrintStream out) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        conversationHistory.add(LlmClient.Message.user(report.promptText()));
        out.println(report.displayText());
        log.info("[{}] injected LSP diagnostics into sub-agent conversation", name);
    }

    private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls) {
        List<ToolInvocation> invocations = new ArrayList<>();
        List<ToolExecutionResult> ordered = new ArrayList<>(Collections.nCopies(toolCalls.size(), null));
        List<Integer> dispatchIndices = new ArrayList<>();

        for (int i = 0; i < toolCalls.size(); i++) {
            LlmClient.ToolCall toolCall = toolCalls.get(i);
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("[{}] scheduling tool: {}", name, toolName);
            log.debug("[{}] tool args [{}]: {}", name, toolName, toolArgs);
            if (readOnly && isMutatingTool(toolName)) {
                log.info("[{}] readOnly 拦截副作用工具: {}", name, toolName);
                ordered.set(i, new ToolExecutionResult(toolCall.id(), toolName, toolArgs,
                        "🛡️ 自审阶段禁止调用有副作用的工具: " + toolName, 0, false, List.of()));
                continue;
            }
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
            dispatchIndices.add(i);
        }

        if (!invocations.isEmpty()) {
            if (invocations.size() > 1) {
                log.info("[{}] executing {} tool calls in parallel", name, invocations.size());
            }
            AgentRunContext dispatchContext = toolDispatchContext();
            List<ToolOutcome> outcomes = toolDispatcher.dispatchInvocations(invocations, dispatchContext);
            appendToolOutcomeEvents(dispatchContext, outcomes);
            for (int j = 0; j < outcomes.size(); j++) {
                ordered.set(dispatchIndices.get(j), SubAgent.toLegacyResult(outcomes.get(j)));
            }
        }
        return ordered;
    }

    private AgentRunContext toolDispatchContext() {
        AgentRunContext base = activeRunContext.get();
        if (base == null) {
            base = AgentRunContext.create(AgentMode.TEAM, "", toolRegistry.getProjectPath());
        }
        Map<String, String> metadata = new LinkedHashMap<>(base.metadata());
        metadata.put("agentName", name);
        metadata.put("role", role.name());
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
        return new AgentRunContext(
                base.runId(),
                base.mode(),
                base.input(),
                base.workspace(),
                base.startedAt(),
                metadata);
    }

    private void appendToolOutcomeEvents(AgentRunContext context, List<ToolOutcome> outcomes) {
        RunStore runStore = activeRunStore.get();
        if (runStore == null || outcomes == null || outcomes.isEmpty()) {
            return;
        }
        for (ToolOutcome outcome : outcomes) {
            runStore.append(ToolOutcomeEventFactory.create(context, outcome, Map.of()));
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

    private void appendImageToolMessages(List<ToolExecutionResult> toolResults) {
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
            conversationHistory.add(LlmClient.Message.user(parts));
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

    public String getName() {
        return name;
    }

    public AgentRole getRole() {
        return role;
    }

    public AgentProfile getProfile() {
        return profile;
    }

    /**
     * SubAgent 流式渲染器，分区展示 reasoning_content 与 content。
     *
     * 与 {@link com.mindcli.agent.Agent.StreamRenderer} 使用同一策略应对
     * "content 开始后又追加 reasoning"的场景：迟到的 reasoning 会被累积到 lateReasoning，
     * 在 finish() 时以"🧠 补充思考"独立展示，避免混入结果区。
     */
    private static final class SubAgentStreamRenderer implements LlmClient.StreamListener {
        private final String agentName;
        private final AgentRole role;
        private final PrintStream out;
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private SubAgentStreamRenderer(String agentName, AgentRole role, PrintStream out) {
            this.agentName = agentName;
            this.role = role;
            this.out = out;
        }

        @Override
        public void onReasoningDelta(String delta) {
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
                out.println(AnsiStyle.heading("🧠 " + reasoningLabel() + " [" + agentName + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    // 实质 reasoning 尚未流出就被 content 打断：先补打思考过程再切到结果
                    out.println(AnsiStyle.heading("🧠 " + reasoningLabel() + " [" + agentName + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                out.println(AnsiStyle.section("🤖 " + contentLabel() + " [" + agentName + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private String reasoningLabel() {
            return switch (role) {
                case EXPLORER -> "探索思考";
                case WORKER -> "执行思考";
                case CUSTOM -> "思考";
            };
        }

        private String contentLabel() {
            // WORKER 可能在 tool_calls 前先 narrate，用"输出"避免"结果"暗示已经完成。
            return switch (role) {
                case EXPLORER -> "探索结果";
                case WORKER -> "执行输出";
                case CUSTOM -> "输出";
            };
        }

        /**
         * 在两次迭代（通常是 tool-call 分支）之间调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代的 reasoning/content 能重新打印各自的标题。
         */
        private void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                out.println();
                out.println(AnsiStyle.heading("🧠 补充思考 [" + agentName + "]"));
                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private void finish() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                out.println();
                out.println(AnsiStyle.heading("🧠 补充思考 [" + agentName + "]"));
                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                out.println("\n");
            }
        }
    }
}
