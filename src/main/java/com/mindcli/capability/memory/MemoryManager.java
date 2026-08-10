package com.mindcli.capability.memory;

import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.context.ContextProfile;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Memory 管理器 - Memory 系统的门面类
 *
 * 统一管理长期记忆、事实提取和检索，
 * 为 Agent 提供简洁的记忆存取接口。
 */
public class MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private static final String AUTO_EXTRACT_PROPERTY = "mindcli.memory.autoExtract.enabled";
    private static final String AUTO_EXTRACT_ENV = "MINDCLI_MEMORY_AUTO_EXTRACT";
    private final LongTermMemory longTermMemory;
    private final MemoryExtractor extractor;
    private final MemoryRetriever retriever;
    private final MemoryProposalStore proposalStore;
    private final MemoryPolicyEngine policyEngine;
    private final MemoryAuditService auditService;
    private final List<MemoryProposal> pendingMemoryProposals;
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
        this.proposalStore = new JsonlMemoryProposalStore(resolveProposalStorePath(this.longTermMemory));
        this.policyEngine = new MemoryPolicyEngine();
        this.auditService = new MemoryAuditService(resolveAuditStorePath(this.longTermMemory));
        this.pendingMemoryProposals = Collections.synchronizedList(new ArrayList<>());
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.currentProject = defaultProjectKey();
        reloadPendingProposals();
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
     * 默认关闭自动提取；只有显式开启兼容开关时才执行。
     * 如果本轮已有手动记忆写入，跳过自动提取（互斥保护）。
     */
    public void extractFacts(List<LlmClient.Message> conversationHistory) {
        if (!isAutoExtractEnabled()) {
            log.debug("自动长期记忆提取默认关闭，跳过本轮提取");
            return;
        }
        if (memoryWrittenThisRun.getAndSet(false)) {
            log.debug("本轮已有手动记忆写入，跳过自动提取");
            return;
        }
        addPendingProposals(extractor.extractFactProposalsIncremental(conversationHistory), null, null);
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
        extractFactsIncrementalAsync(conversationHistory, null, null);
    }

    public void extractFactsIncrementalAsync(List<LlmClient.Message> conversationHistory,
                                             AgentRunContext runContext, RunStore runStore) {
        if (!isAutoExtractEnabled()) {
            log.debug("自动长期记忆提取默认关闭，跳过本轮增量提取");
            return;
        }
        CompletableFuture.runAsync(() -> {
            List<MemoryProposal> proposals = extractor.extractFactProposalsIncremental(conversationHistory);
            List<MemoryProposal> accepted = addPendingProposals(proposals, runContext, runStore);
            appendMemoryProposedEvent(runContext, runStore, accepted, "extractor");
        });
    }

    /**
     * 存储关键事实到长期记忆
     */
    public MemoryWriteResult storeFact(String fact) {
        return storeFact(fact, "project", null, null);
    }

    public MemoryWriteResult storeFact(String fact, String scope) {
        return storeFact(fact, scope, null, null);
    }

    public MemoryWriteResult storeFact(String fact, String scope, AgentRunContext runContext, RunStore runStore) {
        String normalizedScope = normalizeScope(scope);
        MemoryPolicyDecision decision = policyEngine.evaluate(fact,
                MemoryPolicyContext.manual(currentProject, normalizedScope));
        if (decision.type() == MemoryPolicyDecision.DecisionType.DENY) {
            recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_DENIED, Map.of(
                    "source", "manual",
                    "scope", normalizedScope,
                    "policyId", decision.policyId(),
                    "reason", decision.reason(),
                    "contentLength", String.valueOf(fact == null ? 0 : fact.length())
            ));
            return MemoryWriteResult.denied(decision.policyId(),
                    "保存长期记忆被策略拒绝: " + decision.policyId() + " - " + decision.reason());
        }
        Map<String, String> metadata = "global".equals(normalizedScope)
                ? Map.of("source", "fact", "scope", "global")
                : Map.of("source", "fact", "scope", "project", "project", currentProject);
        if (decision.type() == MemoryPolicyDecision.DecisionType.NEED_APPROVAL) {
            MemoryProposal proposal = MemoryProposal.proposed(
                    fact,
                    fact,
                    MemoryEntry.MemoryType.PROJECT_FACT,
                    withPolicyMetadata(metadata, decision, "manual"));
            List<MemoryProposal> accepted = addPendingProposals(List.of(proposal), runContext, runStore);
            appendMemoryProposedEvent(runContext, runStore, accepted, "manual");
            return MemoryWriteResult.proposed(proposal, decision.policyId(),
                    "已生成待确认候选记忆(" + normalizedScope + "): " + proposal.id()
                            + "，可用 /memory approve " + proposal.id() + " 批准");
        }
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.PROJECT_FACT,
                withPolicyMetadata(metadata, decision, "manual"),
                MemoryEntry.estimateTokens(fact)
        );
        storeMemoryEntry(entry, runContext, runStore, Map.of("source", "manual"));
        return MemoryWriteResult.written(entry, decision.policyId(),
                "已保存到长期记忆(" + normalizedScope + "): " + fact);
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
        return deleteLongTerm(id, null, null);
    }

    public boolean deleteLongTerm(String id, AgentRunContext runContext, RunStore runStore) {
        boolean deleted = longTermMemory.delete(id);
        if (deleted) {
            recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_DELETED, Map.of(
                    "memoryId", id == null ? "" : id,
                    "source", "manual"
            ));
        }
        return deleted;
    }

    /**
     * 构建用于 LLM 的记忆上下文
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return buildContextForQuery(query, maxTokens, Set.of(), null, null);
    }

    public String buildContextForQuery(String query, int maxTokens, AgentRunContext runContext, RunStore runStore) {
        return buildContextForQuery(query, maxTokens, Set.of(), runContext, runStore);
    }

    /**
     * 构建用于 LLM 的记忆上下文，支持工具感知过滤。
     */
    public String buildContextForQuery(String query, int maxTokens, Set<String> activeToolNames) {
        return buildContextForQuery(query, maxTokens, activeToolNames, null, null);
    }

    public String buildContextForQuery(String query, int maxTokens, Set<String> activeToolNames,
                                       AgentRunContext runContext, RunStore runStore) {
        Set<String> effectiveToolNames = activeToolNames == null ? Set.of() : activeToolNames;
        String context = retriever.buildContextForQuery(query, maxTokens, currentProject, effectiveToolNames);
        Map<String, String> attributes = Map.of(
                "queryLength", String.valueOf(query == null ? 0 : query.length()),
                "maxTokens", String.valueOf(maxTokens),
                "activeToolCount", String.valueOf(effectiveToolNames.size()),
                "contextChars", String.valueOf(context.length()),
                "injected", String.valueOf(!context.isBlank())
        );
        recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_CONTEXT_BUILT, attributes);
        if (!context.isBlank()) {
            recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_INJECTED, attributes);
        }
        return context;
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
        int count = longTermMemory.size();
        longTermMemory.clear();
        recordMemoryEvent(null, null, AgentRunEventType.MEMORY_DELETED, Map.of(
                "source", "manual",
                "action", "clear",
                "memoryCount", String.valueOf(count)
        ));
    }

    /**
     * 获取记忆系统的整体状态
     */
    public String getSystemStatus() {
        return "上下文策略: " + contextProfile.summary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    public String getPolicyStatus() {
        boolean autoExtractEnabled = isAutoExtractEnabled();
        MemoryStore store = getMemoryStore();
        String storeAdapter = store.getClass().getSimpleName();
        String storePath = store.storagePath()
                .map(Path::toString)
                .orElse("n/a");
        return "自动提取: " + (autoExtractEnabled ? "enabled" : "disabled") + "\n" +
                "自动提取模式: " + (autoExtractEnabled ? "proposal-only" : "disabled") + "\n" +
                "策略引擎: " + policyEngine.getClass().getSimpleName() + "\n" +
                "策略规则: " + policyEngine.describeRules() + "\n" +
                "待确认候选: " + listPendingMemoryProposals().size() + "\n" +
                "存储适配器: " + storeAdapter + "\n" +
                "候选存储: " + proposalStore.storagePath().map(Path::toString).orElse("n/a") + "\n" +
                "审计存储: " + auditService.auditFile() + "\n" +
                "存储路径: " + storePath + "\n" +
                "检索治理: 过滤 status=revoked/deleted/expired 和已过期 expiresAt\n" +
                "审计事件: MEMORY_CONTEXT_BUILT, MEMORY_INJECTED, MEMORY_PROPOSED, MEMORY_WRITTEN, " +
                "MEMORY_DENIED, MEMORY_APPROVED, MEMORY_REJECTED, MEMORY_DELETED, MEMORY_EXPORTED";
    }

    /**
     * 新一轮对话开始时调用，重置本轮已注入记忆的去重集合。
     */
    public void resetSurfaced() {
        retriever.resetSurfaced();
    }

    // Getters
    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public MemoryStore getMemoryStore() { return longTermMemory; }
    public MemoryRetriever getMemoryRetriever() { return retriever; }
    public MemoryAuditService getMemoryAuditService() { return auditService; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
    public ContextProfile getContextProfile() { return contextProfile; }

    public String getCurrentProject() { return currentProject; }

    public List<MemoryProposal> listPendingMemoryProposals() {
        synchronized (pendingMemoryProposals) {
            return List.copyOf(pendingMemoryProposals);
        }
    }

    public List<MemoryProposal> listMemoryProposals() {
        return proposalStore.list();
    }

    public Path exportAudit(Path exportDir) throws IOException {
        return auditService.exportMarkdown(exportDir, LocalDateTime.now());
    }

    public MemoryProposal getMemoryProposal(String id) {
        return proposalStore.findById(id).orElse(null);
    }

    public boolean approveMemoryProposal(String id) {
        return approveMemoryProposal(id, null, null);
    }

    public boolean approveMemoryProposal(String id, AgentRunContext runContext, RunStore runStore) {
        MemoryProposal proposal = proposalStore.findById(id).orElse(null);
        if (proposal == null || proposal.status() != MemoryProposal.Status.PROPOSED) {
            return false;
        }
        MemoryEntry entry = new MemoryEntry(
                proposal.id().replaceFirst("^proposal-", "fact-"),
                proposal.name(),
                proposal.content(),
                proposal.type(),
                mergeProposalMetadata(proposal, "proposal"),
                MemoryEntry.estimateTokens(proposal.content())
        );
        proposalStore.updateStatus(id, MemoryProposal.Status.APPROVED);
        recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_APPROVED, Map.of(
                "proposalId", id,
                "memoryId", entry.getId(),
                "source", "proposal"
        ));
        storeMemoryEntry(entry, runContext, runStore, Map.of(
                "source", "proposal",
                "proposalId", id
        ));
        reloadPendingProposals();
        return true;
    }

    public boolean rejectMemoryProposal(String id) {
        return rejectMemoryProposal(id, null, null);
    }

    public boolean rejectMemoryProposal(String id, AgentRunContext runContext, RunStore runStore) {
        MemoryProposal proposal = proposalStore.findById(id).orElse(null);
        if (proposal == null || proposal.status() != MemoryProposal.Status.PROPOSED) {
            return false;
        }
        proposalStore.updateStatus(id, MemoryProposal.Status.REJECTED);
        recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_REJECTED, Map.of(
                "proposalId", id,
                "source", "proposal"
        ));
        reloadPendingProposals();
        return true;
    }

    private List<MemoryProposal> addPendingProposals(List<MemoryProposal> proposals,
                                                     AgentRunContext runContext,
                                                     RunStore runStore) {
        if (proposals == null || proposals.isEmpty()) {
            return List.of();
        }
        List<MemoryProposal> accepted = new ArrayList<>();
        for (MemoryProposal proposal : proposals) {
            MemoryPolicyDecision decision = policyEngine.evaluate(proposal.content(),
                    MemoryPolicyContext.extracted(currentProject,
                            proposal.metadata().getOrDefault("scope", "project")));
            if (decision.type() == MemoryPolicyDecision.DecisionType.DENY) {
                recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_DENIED, Map.of(
                        "proposalId", proposal.id(),
                        "source", proposal.metadata().getOrDefault("source", "extractor"),
                        "policyId", decision.policyId(),
                        "reason", decision.reason()
                ));
                continue;
            }
            accepted.add(proposal);
        }
        if (accepted.isEmpty()) {
            return List.of();
        }
        synchronized (pendingMemoryProposals) {
            pendingMemoryProposals.addAll(accepted);
        }
        for (MemoryProposal proposal : accepted) {
            proposalStore.save(proposal);
        }
        log.info("生成了 {} 条待确认长期记忆候选", accepted.size());
        return List.copyOf(accepted);
    }

    private void storeMemoryEntry(MemoryEntry entry) {
        storeMemoryEntry(entry, null, null, Map.of());
    }

    private void storeMemoryEntry(MemoryEntry entry, AgentRunContext runContext, RunStore runStore,
                                  Map<String, String> extraAttributes) {
        longTermMemory.store(entry);
        memoryWrittenThisRun.set(true);
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("memoryId", entry.getId());
        attributes.put("scope", LongTermMemory.scopeOf(entry));
        attributes.put("type", entry.getType().name());
        attributes.put("policyId", entry.getMetadata().getOrDefault("policyId", ""));
        if (extraAttributes != null) {
            attributes.putAll(extraAttributes);
        }
        recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_WRITTEN, attributes);
    }

    private void appendMemoryProposedEvent(AgentRunContext runContext, RunStore runStore,
                                           List<MemoryProposal> proposals, String source) {
        if (proposals == null || proposals.isEmpty()) {
            return;
        }
        recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_PROPOSED, Map.of(
                "proposalCount", String.valueOf(proposals.size()),
                "proposalIds", proposals.stream().map(MemoryProposal::id).collect(Collectors.joining(",")),
                "proposalTypes", proposals.stream()
                        .map(proposal -> proposal.type().name())
                        .collect(Collectors.joining(",")),
                "source", source == null || source.isBlank() ? "extractor" : source
        ));
    }

    private void recordMemoryEvent(AgentRunContext runContext, RunStore runStore, AgentRunEventType type,
                                   Map<String, String> attributes) {
        try {
            auditService.record(type, attributes);
        } catch (RuntimeException e) {
            log.warn("写入本地记忆审计失败: type={}, error={}", type, e.getMessage());
        }
        appendMemoryEvent(runContext, runStore, type, attributes);
    }

    private void appendMemoryEvent(AgentRunContext runContext, RunStore runStore, AgentRunEventType type,
                                   Map<String, String> attributes) {
        if (runContext == null || runStore == null) {
            return;
        }
        try {
            runStore.append(AgentRunEvent.of(runContext, type, attributes));
        } catch (RuntimeException e) {
            log.warn("写入记忆审计事件失败: type={}, error={}", type, e.getMessage());
        }
    }

    static boolean isAutoExtractEnabled() {
        String value = System.getProperty(AUTO_EXTRACT_PROPERTY);
        if (value == null || value.isBlank()) {
            value = System.getenv(AUTO_EXTRACT_ENV);
        }
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase()) {
            case "true", "1", "yes", "on" -> true;
            default -> false;
        };
    }

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

    private void reloadPendingProposals() {
        synchronized (pendingMemoryProposals) {
            pendingMemoryProposals.clear();
            pendingMemoryProposals.addAll(proposalStore.list().stream()
                    .filter(proposal -> proposal.status() == MemoryProposal.Status.PROPOSED)
                    .toList());
        }
    }

    private static Map<String, String> mergeProposalMetadata(MemoryProposal proposal, String source) {
        Map<String, String> metadata = new java.util.LinkedHashMap<>(proposal.metadata());
        metadata.put("source", source);
        metadata.put("proposalId", proposal.id());
        metadata.putIfAbsent("scope", "project");
        return Map.copyOf(metadata);
    }

    private static Map<String, String> withPolicyMetadata(Map<String, String> metadata,
                                                          MemoryPolicyDecision decision,
                                                          String source) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        merged.put("source", source);
        merged.put("policyId", decision.policyId());
        merged.put("policyDecision", decision.type().name());
        return Map.copyOf(merged);
    }

    private static Path resolveProposalStorePath(LongTermMemory memoryStore) {
        Path memoryPath = memoryStore.storagePath().orElse(Path.of(System.getProperty("user.home"), ".mindcli", "memory"));
        return memoryPath.resolve("proposals.jsonl");
    }

    private static Path resolveAuditStorePath(LongTermMemory memoryStore) {
        Path memoryPath = memoryStore.storagePath().orElse(Path.of(System.getProperty("user.home"), ".mindcli", "memory"));
        return memoryPath.resolve("audit.jsonl");
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
