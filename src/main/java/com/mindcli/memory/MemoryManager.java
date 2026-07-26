package com.mindcli.memory;

import com.mindcli.llm.LlmClient;
import com.mindcli.context.ContextProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Memory 管理器 - Memory 系统的门面类
 *
 * 统一管理长期记忆、事实提取和检索，
 * 为 Agent 提供简洁的记忆存取接口。
 */
public class MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private final LongTermMemory longTermMemory;
    private final MemoryExtractor extractor;
    private final MemoryRetriever retriever;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;
    private String currentProject;

    /** 本轮是否已有手动记忆写入（互斥保护，避免自动提取产生重复） */
    private final AtomicBoolean memoryWrittenThisRun = new AtomicBoolean(false);

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null);
    }

    /**
     * @param llmClient      LLM 客户端（用于记忆提取和检索）
     * @param contextWindow  模型上下文窗口大小
     */
    public MemoryManager(LlmClient llmClient, int contextWindow) {
        this(llmClient, ContextProfile.custom(contextWindow, contextWindow), null);
    }

    public MemoryManager(LlmClient llmClient, int contextWindow, LongTermMemory longTermMemory) {
        this(llmClient, ContextProfile.custom(contextWindow, contextWindow), longTermMemory);
    }

    private MemoryManager(LlmClient llmClient, ContextProfile contextProfile, LongTermMemory longTermMemory) {
        this.contextProfile = contextProfile;
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.extractor = new MemoryExtractor(llmClient, this.longTermMemory);
        this.retriever = new MemoryRetriever(llmClient, this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.currentProject = defaultProjectKey();
    }

    public void setLlmClient(LlmClient llmClient) {
        this.extractor.setLlmClient(llmClient);
        applyContextProfile(ContextProfile.from(llmClient));
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
    }

    public void setProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return;
        }
        this.currentProject = normalizeProjectKey(projectPath);
    }

    /**
     * 从对话历史中提取事实并存入长期记忆。
     * 替代旧版 ContextCompressor.extractFacts()。
     * 如果本轮已有手动记忆写入，跳过自动提取（互斥保护）。
     */
    public void extractFacts(List<LlmClient.Message> conversationHistory) {
        if (memoryWrittenThisRun.getAndSet(false)) {
            log.debug("本轮已有手动记忆写入，跳过自动提取");
            return;
        }
        extractor.extractFacts(conversationHistory);
    }

    /**
     * 异步提取事实（fire-and-forget），不阻塞主对话响应。
     * 对齐 Claude Code 的 stopHooks 异步模式。
     * @deprecated 改为 {@link #extractFactsIncrementalAsync}，只传本轮新增消息
     */
    public void extractFactsAsync(List<LlmClient.Message> conversationHistory) {
        CompletableFuture.runAsync(() -> {
            extractFacts(conversationHistory);
        });
    }

    /**
     * 增量异步提取 —— 只处理本轮新增的对话消息。
     * 对齐 Claude Code Stop hook：hook 每次只收到新增 exchange，不是整段历史。
     */
    public void extractFactsIncrementalAsync(List<LlmClient.Message> conversationHistory) {
        CompletableFuture.runAsync(() -> {
            extractor.extractFactsIncremental(conversationHistory);
        });
    }

    /**
     * 存储关键事实到长期记忆
     */
    public void storeFact(String fact) {
        storeFact(fact, "project");
    }

    public void storeFact(String fact, String scope) {
        String normalizedScope = normalizeScope(scope);
        Map<String, String> metadata = "global".equals(normalizedScope)
                ? Map.of("source", "fact", "scope", "global")
                : Map.of("source", "fact", "scope", "project", "project", currentProject);
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.PROJECT_FACT,
                metadata,
                MemoryEntry.estimateTokens(fact)
        );
        longTermMemory.store(entry);
        memoryWrittenThisRun.set(true);
    }

    /**
     * 检索与查询最相关的记忆（来自长期记忆）
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieveLongTerm(query, limit, currentProject);
    }

    /**
     * 检索与查询最相关的记忆，支持工具感知过滤。
     * 活跃工具的 REFERENCE 类型记忆会被过滤（保留警告/陷阱类）。
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit, Set<String> activeToolNames) {
        return retriever.retrieveLongTerm(query, limit, currentProject, activeToolNames);
    }

    public List<MemoryEntry> listLongTerm() {
        return longTermMemory.getAll();
    }

    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return longTermMemory.search(query, limit, currentProject);
    }

    public boolean deleteLongTerm(String id) {
        return longTermMemory.delete(id);
    }

    /**
     * 构建用于 LLM 的记忆上下文
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens, currentProject);
    }

    /**
     * 构建用于 LLM 的记忆上下文，支持工具感知过滤。
     */
    public String buildContextForQuery(String query, int maxTokens, Set<String> activeToolNames) {
        return retriever.buildContextForQuery(query, maxTokens, currentProject, activeToolNames);
    }

    /**
     * 记录 token 使用
     */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    /**
     * 清空长期记忆
     */
    public void clearLongTerm() {
        longTermMemory.clear();
    }

    /**
     * 获取记忆系统的整体状态
     */
    public String getSystemStatus() {
        return "上下文策略: " + contextProfile.summary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    /**
     * 新一轮对话开始时调用，重置本轮已注入记忆的去重集合。
     */
    public void resetSurfaced() {
        retriever.resetSurfaced();
    }

    // Getters
    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public MemoryRetriever getMemoryRetriever() { return retriever; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
    public ContextProfile getContextProfile() { return contextProfile; }

    public String getCurrentProject() { return currentProject; }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "project";
        }
        String normalized = scope.trim().toLowerCase();
        return "global".equals(normalized) ? "global" : "project";
    }

    private static String defaultProjectKey() {
        return normalizeProjectKey(System.getProperty("user.dir"));
    }

    private static String normalizeProjectKey(String path) {
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            return candidate.toString();
        } catch (Exception e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }
}
