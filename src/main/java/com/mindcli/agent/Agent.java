package com.mindcli.agent;

import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.LlmTraceLogger;
import com.mindcli.platform.context.ContextProfile;
import com.mindcli.platform.context.TokenUsageFormatter;
import com.mindcli.capability.lsp.LspDiagnosticReport;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.platform.prompt.PromptAssembler;
import com.mindcli.platform.prompt.PromptContext;
import com.mindcli.platform.prompt.PromptMode;
import com.mindcli.platform.prompt.ProjectMemoryLoader;
import com.mindcli.platform.render.PlainRenderer;
import com.mindcli.platform.render.Renderer;
import com.mindcli.platform.render.StatusInfo;
import com.mindcli.runtime.run.AgentLoopContext;
import com.mindcli.runtime.run.AgentLoopExecutor;
import com.mindcli.runtime.run.AgentLoopObserver;
import com.mindcli.runtime.run.AgentLoopPolicy;
import com.mindcli.runtime.run.AgentLoopResult;
import com.mindcli.runtime.run.AgentLoopStatus;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRuntime;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunResult;
import com.mindcli.runtime.run.InMemoryRunStore;
import com.mindcli.runtime.run.ReActModeAdapter;
import com.mindcli.runtime.run.RunStoreFactory;
import com.mindcli.runtime.run.RunStore;
import com.mindcli.runtime.run.ToolDispatcher;
import com.mindcli.runtime.run.ToolOutcome;
import com.mindcli.capability.skill.SkillIndexFormatter;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.util.AnsiStyle;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.util.TerminalMarkdownRenderer;
import com.mindcli.capability.image.ImageReferenceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Agent 核心类 - 实现 ReAct 循环
 */
public class Agent {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    //LLM客户端（统一抽象）
    private LlmClient llmClient;
    //工具注册表
    private final ToolRegistry toolRegistry;
    //对话历史（核心数据结构）
    private final List<LlmClient.Message> conversationHistory;
    //记忆管理器（长期记忆门店）
    private final MemoryManager memoryManager;
    //外部上下文提供者
    private Supplier<String> externalContextSupplier = () -> "";
    //skill注册表
    private SkillRegistry skillRegistry;
    //界面渲染器
    private Renderer renderer;
    //HITL状态提供者
    private Supplier<Boolean> hitlEnabledSupplier = () -> false;
    //流式输出时是否返回最终响应
    private boolean returnFinalResponseWhenStreamed;
    //提示词组装器
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    //运行时事件账本（Phase 2 先使用内存实现）
    private final RunStore runStore;

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), RunStoreFactory.create());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, RunStoreFactory.create());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, RunStore runStore) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.runStore = runStore == null ? new InMemoryRunStore() : runStore;
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = new MemoryManager(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemoryWriter(memoryManager::storeFact);
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.memoryManager.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    public void setReturnFinalResponseWhenStreamed(boolean returnFinalResponseWhenStreamed) {
        this.returnFinalResponseWhenStreamed = returnFinalResponseWhenStreamed;
    }

    /**
     * 注入 HITL 启用状态的快照源，用于状态栏 / StatusInfo 显示。
     * Main 启动后用 {@code reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled)} 接进来。
     */
    public void setHitlEnabledSupplier(Supplier<Boolean> supplier) {
        this.hitlEnabledSupplier = supplier == null ? () -> false : supplier;
    }

    /**
     * 获取渲染器；首次调用时如果未设置，懒加载一个 {@link PlainRenderer} 兜底，
     * 保证旧调用方（构造 Agent 后没有 setRenderer 的代码、单测等）行为不变。
     */
    private Renderer renderer() {
        if (renderer == null) {
            renderer = new PlainRenderer();
        }
        return renderer;
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        AgentRunContext runContext = AgentRunContext.create(
                AgentMode.REACT,
                userInput,
                toolRegistry.getProjectPath());
        return userFacingContent(new AgentRuntime(runStore, toolRegistry.getSnapshotService())
                .run(runContext, new ReActModeAdapter(this)));
    }

    public RunStore runStore() {
        return runStore;
    }

    public AgentRunResult run(AgentRunContext runContext, RunStore runStore) {
        if (runContext == null) {
            throw new IllegalArgumentException("runContext must not be null");
        }
        String userInput = runContext.input();
        RunStore effectiveRunStore = runStore == null ? this.runStore : runStore;
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        pruneHistoricalImagePayloads();

        // 重置本轮已注入记忆的去重集合
        memoryManager.resetSurfaced();

        // 检索相关长期记忆，注入到 system prompt（支持工具感知过滤）
        ContextProfile contextProfile = memoryManager.getContextProfile();
        java.util.Set<String> activeToolNames = toolRegistry.getToolDefinitions().stream()
                .map(LlmClient.Tool::name)
                .collect(java.util.stream.Collectors.toSet());
        String memoryContext = memoryManager.buildContextForQuery(
                userInput, contextProfile.memoryContextTokens(), activeToolNames, runContext, effectiveRunStore);

        // 预加载 MEMORY.md 索引（会话级缓存，只在首次运行时加载）
        String memoryIndexSection = buildMemoryIndexSection();
        if (!memoryIndexSection.isEmpty()) {
            memoryContext = memoryIndexSection + "\n" + memoryContext;
        }

        updateSystemPromptWithMemory(memoryContext);

        // 添加用户输入到历史
        String userMessageContent = userInput;
        conversationHistory.add(ImageReferenceParser.userMessage(
                userMessageContent,
                Path.of(toolRegistry.getProjectPath())));
        StreamRenderer streamRenderer = new StreamRenderer(renderer());

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
        pushStatus(budget, startNanos, "running");

        List<LlmClient.Tool> toolDefinitions = llmClient.supportsTools()
                ? toolRegistry.getToolDefinitions()
                : null;
        AgentLoopObserver observer = new AgentLoopObserver() {
            @Override
            public void beforeIteration(int iteration, List<LlmClient.Message> messages, List<LlmClient.Tool> tools) {
                injectPendingLspDiagnostics();
                trimConversationHistory();
                logRequestContext("react iteration=" + iteration, tools);
                streamRenderer.beginThinking();
            }

            @Override
            public void afterLlmResponse(int iteration, LlmClient.ChatResponse response) {
                LlmTraceLogger.logReasoning(log, "react iteration=" + iteration, llmClient, response.reasoningContent());
            }

            @Override
            public void beforeToolDispatch(int iteration, List<LlmClient.ToolCall> toolCalls) {
                log.info("LLM requested {} tool call(s) in iteration {}", toolCalls.size(), iteration);
                for (LlmClient.ToolCall toolCall : toolCalls) {
                    String toolName = toolCall.function() == null ? "" : toolCall.function().name();
                    String toolArgs = toolCall.function() == null ? "" : toolCall.function().arguments();
                    log.info("Scheduling tool: {} (iteration={})", toolName, iteration);
                    log.debug("Tool args [{}]: {}", toolName, toolArgs);
                }
                if (toolCalls.size() > 1) {
                    log.info("Executing {} tool calls in parallel (iteration={})", toolCalls.size(), iteration);
                }
                // 在工具执行前就 flush 本轮流式渲染器，避免 TerminalMarkdownRenderer
                // 内部 pending 缓冲区（仅按换行 flush）里的文本被 HITL 提示"跨过"。
                streamRenderer.resetBetweenIterations();
                renderer().appendToolCalls(toolCalls);
            }

            @Override
            public void afterToolDispatch(int iteration, List<ToolOutcome> outcomes) {
                for (ToolOutcome outcome : outcomes) {
                    log.debug("Tool result preview [{}]: {}", outcome.name(), preview(outcome.text(), 300));
                    emitToolResultSummary(outcome);
                }
                pushStatus(budget, startNanos, "running");
            }
        };

        AgentLoopResult loopResult = new AgentLoopExecutor(llmClient, new ToolDispatcher(toolRegistry), effectiveRunStore)
                .execute(new AgentLoopContext(
                        runContext,
                        conversationHistory,
                        toolDefinitions,
                        new AgentLoopPolicy("react", llmClient.supportsTools()),
                        budget,
                        streamRenderer,
                        observer));

        return handleLoopResult(runContext, effectiveRunStore, loopResult, budget, streamRenderer, startNanos);
    }

    private AgentRunResult handleLoopResult(AgentRunContext runContext, RunStore effectiveRunStore,
                                            AgentLoopResult loopResult, AgentBudget budget,
                                            StreamRenderer streamRenderer, long startNanos) {
        if (loopResult.status() == AgentLoopStatus.CANCELLED) {
            log.info("ReAct run cancelled");
            streamRenderer.finish();
            pushStatus(budget, startNanos, "idle");
            String content = loopResult.content().isBlank() ? "⏹️ 已取消当前任务。" : loopResult.content();
            return AgentRunResult.cancelled(runContext, content);
        }

        if (loopResult.status() == AgentLoopStatus.BUDGET_EXHAUSTED) {
            log.warn("ReAct run exhausted budget: description={}, iteration={}, tokens={}/{}",
                    loopResult.exitDescription(),
                    budget.iteration(),
                    budget.totalInputTokens() + budget.totalOutputTokens(),
                    budget.tokenBudget());
            streamRenderer.finish();
            pushStatus(budget, startNanos, "idle");
            return AgentRunResult.budgetExhausted(runContext, "❌ " + loopResult.exitDescription());
        }

        if (loopResult.status() == AgentLoopStatus.FAILED) {
            log.error("LLM call failed in ReAct loop: {}", loopResult.errorMessage());
            streamRenderer.finish();
            pushStatus(budget, startNanos, "idle");
            return AgentRunResult.failed(runContext, "❌ 调用 LLM 失败: " + loopResult.errorMessage());
        }

        // 增量异步提取本轮新增的长期记忆事实。
        // 对齐 Claude Code Stop hook：只传本轮新增 exchange，不重传整段历史。
        memoryManager.extractFactsIncrementalAsync(conversationHistory, runContext, effectiveRunStore);
        memoryManager.recordTokenUsage(
                loopResult.inputTokens(),
                loopResult.outputTokens(),
                loopResult.cachedInputTokens());
        pushStatus(budget, startNanos, "idle");
        log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                loopResult.inputTokens(),
                loopResult.outputTokens(),
                loopResult.reasoningContent().length(),
                loopResult.content().length());
        if (log.isDebugEnabled()) {
            log.debug("Assistant answer preview: {}", preview(loopResult.content(), 500));
        }

        if (streamRenderer.hasStreamedOutput()) {
            streamRenderer.finish();
            return AgentRunResult.success(runContext,
                    returnFinalResponseWhenStreamed ? loopResult.content().trim() : "");
        }
        streamRenderer.clearThinkingPanel();
        return AgentRunResult.success(runContext,
                formatUserFacingResponse(loopResult.reasoningContent(), loopResult.content()));
    }

    private String userFacingContent(AgentRunResult result) {
        if (result == null) {
            return "";
        }
        return result.isSuccess() || result.status() == com.mindcli.runtime.run.AgentRunStatus.CANCELLED
                ? result.content()
                : result.errorMessage();
    }

    /**
     * 清空对话历史并重建基础系统提示，不影响长期记忆条目
     */
    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    /**
     * 手动截断过长对话历史，不等待上下文窗口阈值触发。
     */
    public CompactionResult compactHistoryNow() {
        long beforeTokens = estimateCurrentContextTokens();
        long beforeSize = conversationHistory.size();
        trimConversationHistory();
        boolean trimmed = conversationHistory.size() < beforeSize;
        return new CompactionResult(trimmed, beforeTokens, estimateCurrentContextTokens(), null);
    }

    public record CompactionResult(boolean compacted, long beforeTokens, long afterTokens, String error) {
    }

    /** 当前状态栏快照：ctx 表示下一轮请求仍会携带的上下文估算，不含累计 in/out 用量。 */
    public StatusInfo currentStatus(String phase) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        String model = llmClient == null ? "—" : llmClient.getModelName();
        long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
        boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
        long contextTokens = estimateCurrentContextTokens();
        if ("idle".equals(normalizedPhase)) {
            return StatusInfo.idle(model, contextWindow, contextTokens, hitl);
        }
        return StatusInfo.active(model, contextWindow, contextTokens, hitl, normalizedPhase);
    }

    /**
     * 将记忆上下文注入到 system prompt 中（替换 conversationHistory[0]）
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        conversationHistory.set(0, LlmClient.Message.system(buildSystemPrompt(memoryContext)));
    }

    private String buildSystemPrompt(String memoryContext) {
        return promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .memoryContext(memoryContext)
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());
    }

    /**
     * 截断过长的对话历史，保留 system prompt + 最近用户轮次的消息。
     *
     * 对齐 Claude Code 的 Compaction Summary 语义：
     * - 保留最近 N 轮完整原文（等价于 CC 的保留窗口）
     * - 被丢弃的早期消息先调 lightQuery 生成压缩摘要，摘要在 system prompt 后插入
     * - 摘要失败时静默降级为纯截断，不阻塞主流程
     *
     * 关键约束：分割点必须落在 user message 边界，避免切断 tool_call / tool_result
     * 的成对协议（LLM API 要求 tool_call 和 tool_result 成对出现，切断会导致 400 错误）。
     */
    private void trimConversationHistory() {
        // 用 token 阈值替代硬编码消息数，避免小消息过早截断、大消息已超限才发现
        long currentTokens = estimateCurrentContextTokens();
        int triggerTokens = memoryManager.getContextProfile().compressionTriggerTokens();
        if (currentTokens <= triggerTokens) return;

        // 找到所有 user message 的索引
        int systemEnd = "system".equals(conversationHistory.get(0).role()) ? 1 : 0;
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < conversationHistory.size(); i++) {
            if ("user".equals(conversationHistory.get(i).role())) {
                userIndices.add(i);
            }
        }

        // 保留最近 3 个 user 轮次（不够则不做）
        final int retainUserRounds = 3;
        if (userIndices.size() <= retainUserRounds) return;

        int splitIdx = userIndices.get(userIndices.size() - retainUserRounds);
        if (splitIdx <= systemEnd) return;

        // 被丢弃的消息
        List<LlmClient.Message> toDiscard = new ArrayList<>(
                conversationHistory.subList(systemEnd, splitIdx));

        // 异步生成压缩摘要（fire-and-forget 不阻塞；失败降级为纯截断）
        String compactionSummary = summarizeDiscardedMessages(toDiscard);

        // 在 user message 边界处分割，保护 tool_call/tool_result 不被切断
        long beforeTokens = estimateCurrentContextTokens();
        int beforeSize = conversationHistory.size();
        List<LlmClient.Message> toKeep = new ArrayList<>();
        // 保留 system prompt
        for (int i = 0; i < systemEnd; i++) {
            toKeep.add(conversationHistory.get(i));
        }
        // 插入压缩摘要（来自被丢弃的消息）
        if (compactionSummary != null && !compactionSummary.isEmpty()) {
            toKeep.add(LlmClient.Message.system(
                    "[Earlier conversation summary]\n" + compactionSummary));
        }
        // 保留从分割点（user message 边界）开始的所有消息
        toKeep.addAll(conversationHistory.subList(splitIdx, conversationHistory.size()));

        conversationHistory.clear();
        conversationHistory.addAll(toKeep);

        log.info("Compaction: {}→{} messages, {}→{} tokens, summary={} chars, kept {} user rounds",
                beforeSize, conversationHistory.size(),
                beforeTokens, estimateCurrentContextTokens(),
                compactionSummary != null ? compactionSummary.length() : 0,
                retainUserRounds);
    }

    /**
     * 对即将被丢弃的消息生成压缩摘要。
     * 对齐 Claude Code Compaction Summary：保留推理过程和关键决策，不提取永久事实（那是 MemoryExtractor 的职责）。
     *
     * @return 摘要文本，失败时返回 null（降级为纯截断）
     */
    private String summarizeDiscardedMessages(List<LlmClient.Message> messages) {
        if (messages == null || messages.isEmpty()) return null;
        if (llmClient == null) return null;

        try {
            // 只取 user + assistant 消息，跳过 tool 结果（tool 输出太长且摘要不需要原文）
            String dialogue = messages.stream()
                    .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                    .map(m -> m.role().toUpperCase() + ": " + truncateForSummary(m.content(), 2000))
                    .reduce("", (a, b) -> a + "\n\n" + b);

            if (dialogue.length() < 300) return null;

            String prompt = """
                    请对以下对话片段做简洁摘要，保留：
                    - 用户做了什么关键决策或提出什么重要约束
                    - 你读了哪些文件、做了哪些关键修改
                    - 遇到了什么错误以及如何解决的
                    - 当前仍未完成的事项

                    不要提取可推导的代码模式、文件路径或架构约定。
                    摘要控制在 500 字以内。

                    对话：
                    %s
                    """.formatted(dialogue);

            List<LlmClient.Message> request = List.of(
                    LlmClient.Message.system("你是一个对话摘要助手，只输出简洁事实，不做推测。"),
                    LlmClient.Message.user(prompt)
            );

            // 使用 lightQuery（便宜模型 + 限制输出）控制成本
            LlmClient.ChatResponse response = llmClient.lightQuery(request, 512);
            String summary = response.content();
            if (summary == null || summary.isBlank() || "NONE".equals(summary.trim())) {
                return null;
            }
            return summary.trim();
        } catch (Exception e) {
            log.warn("压缩摘要生成失败，降级为纯截断: {}", e.getMessage());
            return null;
        }
    }

    private static String truncateForSummary(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }

    private void pruneHistoricalImagePayloads() {
        // 找到最后一条 user message，保护其图片（用户可能刚发了截图）
        int lastUserIdx = -1;
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            if ("user".equals(conversationHistory.get(i).role())) {
                lastUserIdx = i;
                break;
            }
        }

        int messageCount = 0;
        int imageCount = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            if (i == lastUserIdx) continue;
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
            log.info("Pruned historical image payloads before new ReAct turn: messages={}, images={}",
                    messageCount, imageCount);
        }
    }

    private void injectPendingLspDiagnostics() {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        conversationHistory.add(LlmClient.Message.user(report.promptText()));
        renderer().stream().println(report.displayText());
        log.info("Injected LSP diagnostics into ReAct conversation");
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

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context", e);
            return "";
        }
    }

    private String buildProjectMemoryContext() {
        try {
            return ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
        } catch (Exception e) {
            log.warn("Failed to load PAI.md project memory", e);
            return "";
        }
    }

    /**
     * 读取 MEMORY.md 索引文件，注入到 system prompt。
     * 对齐 Claude Code：会话启动时预加载记忆索引（≤200 行 / 25KB），
     * 让 LLM 在全局层面知道有哪些已知信息域。
     */
    private String buildMemoryIndexSection() {
        try {
            java.io.File storageDir = memoryManager.getLongTermMemory().getStorageDir();
            java.io.File indexFile = new java.io.File(storageDir, "MEMORY.md");
            if (!indexFile.exists() || indexFile.length() == 0) return "";

            String content = java.nio.file.Files.readString(indexFile.toPath());
            String[] lines = content.split("\n");
            if (lines.length > 200) {
                content = String.join("\n", java.util.Arrays.copyOf(lines, 200))
                        + "\n\n<!-- 索引已截断，更多记忆通过查询检索 -->";
            }
            if (content.length() > 25000) {
                content = content.substring(0, 25000) + "\n<!-- 索引已截断 -->";
            }
            return "\n## 长期记忆索引\n" + content + "\n";
        } catch (Exception e) {
            log.warn("读取 MEMORY.md 索引失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<LlmClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public String getContextStatus() {
        com.mindcli.platform.context.ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();

        // 分类估算 token 占用
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        for (LlmClient.Message msg : conversationHistory) {
            int t = com.mindcli.capability.memory.TokenBudget.estimateMessagesTokens(java.util.List.of(msg));
            switch (msg.role()) {
                case "system" -> { systemTokens += t; systemCount++; }
                case "user" -> { userTokens += t; userCount++; }
                case "assistant" -> { assistantTokens += t; assistantCount++; }
                case "tool" -> { toolTokens += t; toolCount++; }
            }
        }
        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 Context Usage   %s   window: %s%n",
                modelLabel(), formatTokens(window)));
        sb.append("\n  ").append(progressBar(ratio, 30))
                .append(String.format("  %d%%  (%s / %s)%n",
                        (int) Math.round(ratio * 100), formatTokens(total), formatTokens(window)));
        sb.append("\n  当前占用细分:\n");
        sb.append(formatLine("System prompt",      systemTokens,    window, systemCount));
        sb.append(formatLine("Tools schema",       toolsSchemaTokens, window, -1));
        sb.append(formatLine("Conversation",       messagesTokens, window,
                userCount + assistantCount + toolCount));
        sb.append("    ─────────────────────────────────\n");
        sb.append(String.format("    合计:              %8s  (%4.1f%%)%n",
                formatTokens(total), ratio * 100));
        sb.append("  MCP resources 自动索引: ")
                .append(profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）")
                .append("\n");
        sb.append("  prompt cache: ").append(profile.promptCacheMode()).append("\n");
        sb.append("\n");
        sb.append(memoryManager.getSystemStatus());
        return sb.toString();
    }

    private String modelLabel() {
        if (llmClient == null) return "(no model)";
        return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")";
    }

    private int estimateToolsSchemaTokens() {
        try {
            return com.mindcli.capability.memory.MemoryEntry.estimateTokens(
                    new ObjectMapper().writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }

    private long estimateCurrentContextTokens() {
        long messageTokens = com.mindcli.capability.memory.TokenBudget.estimateMessagesTokens(conversationHistory);
        return Math.max(0L, messageTokens + estimateToolsSchemaTokens());
    }

    private void logRequestContext(String scope, List<LlmClient.Tool> tools) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolMessageTokens = 0;
        int imageParts = 0;
        int messages = 0;
        StringBuilder imageDetails = new StringBuilder();
        for (int messageIndex = 0; messageIndex < conversationHistory.size(); messageIndex++) {
            LlmClient.Message msg = conversationHistory.get(messageIndex);
            messages++;
            int tokens = com.mindcli.capability.memory.TokenBudget.estimateMessagesTokens(List.of(msg));
            imageParts += msg.imagePartCount();
            appendImageDetails(imageDetails, msg, messageIndex);
            switch (msg.role()) {
                case "system" -> systemTokens += tokens;
                case "user" -> userTokens += tokens;
                case "assistant" -> assistantTokens += tokens;
                case "tool" -> toolMessageTokens += tokens;
                default -> {
                }
            }
        }
        int toolsSchemaTokens = 0;
        int toolCount = tools == null ? 0 : tools.size();
        if (tools != null && !tools.isEmpty()) {
            try {
                toolsSchemaTokens = com.mindcli.capability.memory.MemoryEntry.estimateTokens(
                        new ObjectMapper().writeValueAsString(tools));
            } catch (Exception e) {
                log.debug("Failed to estimate tools schema tokens", e);
            }
        }
        int estimatedTotal = systemTokens + userTokens + assistantTokens + toolMessageTokens + toolsSchemaTokens;
        log.info("LLM request context [{}]: messages={}, images={}, systemTokens={}, userTokens={}, assistantTokens={}, toolMessageTokens={}, tools={}, toolsSchemaTokens={}, estimatedTotal={}",
                scope, messages, imageParts, systemTokens, userTokens, assistantTokens, toolMessageTokens,
                toolCount, toolsSchemaTokens, estimatedTotal);
        if (!imageDetails.isEmpty()) {
            log.info("LLM request images [{}]: {}", scope, imageDetails);
        }
    }

    private void appendImageDetails(StringBuilder sb, LlmClient.Message msg, int messageIndex) {
        if (msg == null || !msg.hasContentParts()) {
            return;
        }
        for (int partIndex = 0; partIndex < msg.contentParts().size(); partIndex++) {
            LlmClient.ContentPart part = msg.contentParts().get(partIndex);
            if (part == null || !part.isImage()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            String payload = "image_url".equals(part.type()) ? part.imageUrl() : part.imageBase64();
            sb.append("#").append(messageIndex)
                    .append(".").append(partIndex)
                    .append(" role=").append(msg.role())
                    .append(" type=").append(part.type())
                    .append(" mime=").append(part.mimeType() == null ? "-" : part.mimeType())
                    .append(" payloadChars=").append(payload == null ? 0 : payload.length())
                    .append(" sha256=").append(shortSha256(payload));
        }
    }

    private String shortSha256(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static String formatLine(String label, int tokens, int window, int count) {
        double pct = window > 0 ? (double) tokens / window * 100 : 0;
        String countLabel = count >= 0 ? String.format("  [%d 条]", count) : "";
        return String.format("    %-18s %8s  (%4.1f%%)%s%n",
                label + ":", formatTokens(tokens), pct, countLabel);
    }

    private static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append("]");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000)     return String.format("%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    /**
     * 获取工具注册表（用于同步项目路径等配置）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /** 把当前预算/耗时/HITL 状态推送给 renderer 状态栏。 */
    private void pushStatus(AgentBudget budget, long startNanos, String phase) {
        try {
            String model = llmClient == null ? "—" : llmClient.getModelName();
            long totalTokens = budget == null ? 0L
                    : (long) (budget.totalInputTokens() + budget.totalOutputTokens());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            String cost = budget == null ? null : TokenUsageFormatter.estimatedCostCny(
                    llmClient,
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
            renderer().updateStatus(StatusInfo.tokens(
                    model,
                    contextWindow,
                    estimateCurrentContextTokens(),
                    budget == null ? 0L : budget.totalInputTokens(),
                    budget == null ? 0L : budget.totalOutputTokens(),
                    budget == null ? 0L : budget.totalCachedInputTokens(),
                    cost,
                    hitl,
                    elapsed,
                    phase == null || phase.isBlank()
                            ? (totalTokens > 0 || elapsed > 0 ? "running" : "idle")
                            : phase));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
    }

    private void emitToolResultSummary(ToolOutcome result) {
        if (result == null || result.name() == null) {
            return;
        }
        String summary = switch (result.name()) {
            case "web_search" -> webSearchSummary(result);
            case "web_fetch" -> webFetchSummary(result);
            default -> "";
        };
        if (!summary.isBlank()) {
            renderer().stream().println(AnsiStyle.subtle("  → " + summary));
        }
    }

    private String webSearchSummary(ToolOutcome result) {
        String text = result.text() == null ? "" : result.text();
        boolean stepSearch = isStepSearchResult(text);
        if (text.startsWith("搜索失败") || text.startsWith("⚠️") || text.contains("未找到相关结果")) {
            return compactOneLine(text, 120);
        }
        long count = text.lines().filter(line -> line.matches("^\\d+\\.\\s+.*")).count();
        String query = extractJsonArg(result.argumentsJson(), "query");
        String label = query.isBlank() ? "搜索结果" : "搜索 \"" + query + "\"";
        if (stepSearch) {
            label = "StepSearch · " + label;
        }
        return count > 0
                ? label + " 返回 " + count + " 条结果"
                : label + " 已返回结果";
    }

    private String webFetchSummary(ToolOutcome result) {
        String text = result.text() == null ? "" : result.text();
        boolean stepSearch = isStepSearchResult(text);
        String url = extractJsonArg(result.argumentsJson(), "url");
        String target = url.isBlank() ? "页面" : compactOneLine(url.replaceFirst("^https?://", ""), 80);
        String verb = stepSearch ? "StepSearch · 抓取 " : "抓取 ";
        if (text.startsWith("抓取失败") || text.startsWith("❌")) {
            return verb + target + " 失败: " + compactOneLine(text, 100);
        }
        String title = text.lines()
                .filter(line -> line.startsWith("📄 标题:"))
                .map(line -> line.substring("📄 标题:".length()).trim())
                .findFirst()
                .orElse("");
        String length = text.lines()
                .filter(line -> line.startsWith("📏 正文"))
                .findFirst()
                .orElse("");
        if (!title.isBlank() && !length.isBlank()) {
            return verb + target + " 完成: " + title + " · " + length.replace("📏 ", "");
        }
        if (!title.isBlank()) {
            return verb + target + " 完成: " + title;
        }
        return verb + target + " 完成";
    }

    private boolean isStepSearchResult(String text) {
        return text != null && text.startsWith("🔍 [StepSearch]")
                || text != null && text.startsWith("🌐 [StepSearch]");
    }

    private String extractJsonArg(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        try {
            return new ObjectMapper().readTree(json).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String compactOneLine(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceAll("\\s+", " ");
        return value.length() > maxLength ? value.substring(0, Math.max(0, maxLength - 3)) + "..." : value;
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (!renderer().rendersReasoning() || normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "🧠 思考过程:\n" + normalizedReasoning;
        }
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n▪ " + normalizedAnswer;
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

    /**
     * 流式输出渲染器，将 reasoning_content 与 content 分区展示。
     *
     * 服务器可能把 reasoning_content 切成多段下发，甚至在 content 开始之后追加 reasoning；
     * 终端是线性的，无法回头修改已写出的文字。渲染策略：
     *
     * 1. 在 content 出现之前，只要 reasoning 有实质内容（非空白），就立刻流式打印在"🧠 思考过程"下
     *    同一次用户输入只打印一次"🧠 思考过程"标题；工具调用后的后续推理继续归在同一块下
     * 2. 仅空白的 reasoning delta 会先暂存，不触发标题——避免出现"空的思考过程"
     * 3. content 一出现就收尾 reasoning 区，用低调标记进入正文并流式输出 content
     * 4. 如果 content 启动之后又收到 reasoning（服务器把思考内容追加在答案之后），
     *    缓冲到 lateReasoning，最终在 finish() 用"🧠 补充思考"标题独立展示，不会污染回复区
     */
    private static final class StreamRenderer implements LlmClient.StreamListener {
        private final Renderer renderer;
        private final PrintStream boundOut;  // null 表示延迟读取 System.out（保持旧测试兼容）
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder visibleReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningHeadingPrinted;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean thinkingQuotePrinted;
        private boolean streamedOutput;

        StreamRenderer() {
            this.renderer = null;
            this.boundOut = null;
        }

        StreamRenderer(PrintStream out) {
            this.renderer = null;
            this.boundOut = out;
        }

        StreamRenderer(Renderer renderer) {
            this.renderer = renderer;
            this.boundOut = renderer == null ? null : renderer.stream();
        }

        private PrintStream out() {
            return boundOut != null ? boundOut : System.out;
        }

        private boolean hasThinkingPanel() {
            return renderer != null && renderer.supportsThinkingPanel();
        }

        private boolean rendersReasoning() {
            return renderer == null || renderer.rendersReasoning();
        }

        private void beginThinking() {
            if (hasThinkingPanel()) {
                renderer.beginThinking("Thinking");
            }
        }

        private void clearThinkingPanel() {
            if (hasThinkingPanel()) {
                renderer.endThinking();
                pendingReasoning.setLength(0);
            }
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!rendersReasoning()) {
                return;
            }
            if (contentStarted) {
                // content 已开始，无法回头；缓冲到"补充思考"
                lateReasoning.append(delta);
                return;
            }
            visibleReasoning.append(delta);
            if (hasThinkingPanel()) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                renderer.appendThinking(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;  // 还没攒出实质内容，等
                }
                if (!containsLineBreak(pendingReasoning)) {
                    return;  // 避免先打印一个空标题，等有完整行或迭代切换时再 flush
                }
                printReasoningHeadingIfNeeded();
                reasoningRenderer = newMarkdownRenderer();
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                if (hasThinkingPanel()) {
                    renderer.appendThinking(delta);
                } else {
                    reasoningRenderer.append(delta);
                }
            }
            out().flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (hasThinkingPanel()) {
                    finishThinkingPanelAndPrintQuote();
                } else if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out().println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    printReasoningHeadingIfNeeded();
                    TerminalMarkdownRenderer r = newMarkdownRenderer();
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out().println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                out().print(AnsiStyle.answerMarker() + " ");
                contentRenderer = newMarkdownRenderer();
                contentStarted = true;
                streamedOutput = true;
            }
            contentRenderer.append(delta);
            if (renderer != null) {
                renderer.appendAssistantContentDelta(delta);
            }
            out().flush();
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void resetBetweenIterations() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            if (renderer != null) {
                renderer.finishAssistantContent();
            }
            String late = lateReasoning.toString().trim();
            if (rendersReasoning() && !late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            visibleReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            thinkingQuotePrinted = false;
            if (streamedOutput) {
                out().println();
            }
        }

        private void finish() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            if (renderer != null) {
                renderer.finishAssistantContent();
            }
            String late = lateReasoning.toString().trim();
            if (rendersReasoning() && !late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                out().println();
            }
        }

        private boolean containsLineBreak(CharSequence content) {
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    return true;
                }
            }
            return false;
        }

        private void flushPendingReasoning() {
            String pending = pendingReasoning.toString();
            if (pending.isBlank()) {
                pendingReasoning.setLength(0);
                return;
            }
            printReasoningHeadingIfNeeded();
            TerminalMarkdownRenderer renderer = newMarkdownRenderer();
            renderer.append(pending);
            renderer.finish();
            pendingReasoning.setLength(0);
            streamedOutput = true;
        }

        private TerminalMarkdownRenderer newMarkdownRenderer() {
            if (renderer != null) {
                return new TerminalMarkdownRenderer(out(), renderer::terminalColumns);
            }
            return new TerminalMarkdownRenderer(out());
        }

        private void finishThinkingPanelAndPrintQuote() {
            if (!hasThinkingPanel()) {
                return;
            }
            if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                renderer.appendThinking(pendingReasoning.toString());
            }
            renderer.endThinking();
            pendingReasoning.setLength(0);
            printThinkingQuoteIfNeeded();
        }

        private void printThinkingQuoteIfNeeded() {
            if (thinkingQuotePrinted) {
                return;
            }
            if (!rendersReasoning()) {
                return;
            }
            String reasoning = visibleReasoning.toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim();
            if (reasoning.isEmpty()) {
                return;
            }
            out().println(AnsiStyle.thinking("Thinking..."));
            for (String line : reasoning.split("\\R+")) {
                String normalized = line.replaceAll("\\s+", " ").trim();
                if (!normalized.isEmpty()) {
                    out().println(AnsiStyle.subtle("│ " + normalized));
                }
            }
            out().println();
            thinkingQuotePrinted = true;
            streamedOutput = true;
        }

        private void printReasoningHeadingIfNeeded() {
            if (!reasoningHeadingPrinted) {
                if (!rendersReasoning()) {
                    return;
                }
                out().println(AnsiStyle.heading("🧠 思考过程"));
                reasoningHeadingPrinted = true;
            }
        }
    }
}
