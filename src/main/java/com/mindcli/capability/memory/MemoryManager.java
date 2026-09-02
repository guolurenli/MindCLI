package com.mindcli.capability.memory;

import com.mindcli.capability.memory.policy.MemoryPolicyContext;
import com.mindcli.capability.memory.policy.MemoryPolicyDecision;
import com.mindcli.capability.memory.policy.MemoryPolicyEngine;
import com.mindcli.platform.config.ConfigValueResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.context.ContextProfile;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.store.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private static final ObjectMapper TOOL_MAPPER = com.mindcli.platform.serialization.JsonSupport.mapper();
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
        this.retriever = new MemoryRetriever(this.longTermMemory);
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
     * 增量异步提取 —— 只处理本轮新增的对话消息。
     * 对齐 Claude Code Stop hook：hook 每次只收到新增 exchange，不是整段历史。
     */
    public CompletableFuture<Void> extractFactsIncrementalAsync(List<LlmClient.Message> conversationHistory) {
        return extractFactsIncrementalAsync(conversationHistory, null, null);
    }

    public CompletableFuture<Void> extractFactsIncrementalAsync(List<LlmClient.Message> conversationHistory,
                                                                AgentRunContext runContext, RunStore runStore) {
        if (!isAutoExtractEnabled()) {
            log.debug("自动长期记忆提取默认关闭，跳过本轮增量提取");
            return CompletableFuture.completedFuture(null);
        }
        return runBackground("增量长期记忆候选提取", () -> {
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

    /** 构建当前项目可见的短记忆目录，供 system prompt 注入。 */
    public String buildMemoryIndex(int maxLines, int maxChars) {
        return longTermMemory.buildIndex(currentProject, maxLines, maxChars);
    }

    /** 为 search_memory 工具返回不含正文的结构化摘要。 */
    public String searchMemory(String query, int limit) {
        return searchMemory(query, limit, null, null);
    }

    public String searchMemory(String query, int limit, AgentRunContext runContext, RunStore runStore) {
        List<MemoryEntry> results = longTermMemory.search(query, limit, currentProject);
        List<Map<String, String>> summaries = results.stream().map(entry -> {
            Map<String, String> summary = new LinkedHashMap<>();
            summary.put("id", entry.getId());
            summary.put("name", entry.getName());
            summary.put("type", entry.getType().name());
            summary.put("scope", LongTermMemory.scopeOf(entry));
            summary.put("snippet", snippet(entry.getContent()));
            summary.put("updatedAt", entry.getTimestamp().toString());
            return summary;
        }).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("candidates", summaries);
        response.put("candidateCount", results.size());
        response.put("multipleCandidates", results.size() > 1);
        response.put("guidance", results.size() > 1
                ? "这些是可能相关的记忆候选，不代表已验证事实；请先用 read_memory 比较内容。若内容影响当前代码，再用 glob_files、grep_code、read_file 检查当前代码和配置，以当前任务相关的最新证据为准。"
                : "搜索结果只是定位信息，不代表已验证事实；需要使用时请用 read_memory 读取记忆内容，涉及当前代码时以实时代码和配置为准。");
        Map<String, String> audit = Map.of(
                "queryLength", String.valueOf(query == null ? 0 : query.length()),
                "limit", String.valueOf(limit),
                "resultCount", String.valueOf(results.size()),
                "project", currentProject
        );
        recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_SEARCHED, audit);
        try {
            return TOOL_MAPPER.writeValueAsString(response);
        } catch (IOException e) {
            return "检索长期记忆失败: 无法序列化检索结果";
        }
    }

    /** 按 ID 读取当前项目可见的单条记忆正文。 */
    public String readMemory(String id) {
        return readMemory(id, null, null);
    }

    public String readMemory(String id, AgentRunContext runContext, RunStore runStore) {
        String normalizedId = id == null ? "" : id.trim();
        MemoryEntry entry = longTermMemory.retrieve(normalizedId)
                .filter(candidate -> LongTermMemory.isVisible(candidate, currentProject))
                .orElse(null);
        Map<String, String> audit = Map.of(
                "memoryId", normalizedId,
                "found", String.valueOf(entry != null),
                "project", currentProject
        );
        recordMemoryEvent(runContext, runStore, AgentRunEventType.MEMORY_READ, audit);
        if (entry == null) {
            return "读取长期记忆失败: 未找到或当前项目不可见的记忆 " + normalizedId;
        }
        return "## 长期记忆 " + entry.getId() + "\n"
                + "name: " + entry.getName() + "\n"
                + "type: " + entry.getType() + "\n"
                + "scope: " + LongTermMemory.scopeOf(entry) + "\n"
                + "updatedAt: " + entry.getTimestamp() + "\n\n"
                + entry.getContent();
    }

    private static String snippet(String content) {
        String normalized = content == null ? "" : content.replace('\n', ' ').trim();
        // 搜索阶段只用于定位候选；短正文不重复回显，避免摘要被误认为已读取事实。
        if (normalized.isEmpty()) return "";
        return normalized.length() <= 80
                ? "短记忆，请使用 read_memory 读取"
                : normalized.substring(0, 80) + "...";
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
        for (MemoryProposal proposal : accepted) {
            proposalStore.save(proposal);
        }
        synchronized (pendingMemoryProposals) {
            pendingMemoryProposals.addAll(accepted);
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
        return ConfigValueResolver.current().resolveBoolean(AUTO_EXTRACT_PROPERTY, AUTO_EXTRACT_ENV, false);
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

    private static CompletableFuture<Void> runBackground(String operation, Runnable task) {
        return CompletableFuture.runAsync(task).whenComplete((ignored, error) -> {
            if (error != null) {
                log.warn("{}失败", operation, error);
            }
        });
    }

    private static String normalizeProjectKey(String path) {
        try {
            Path input = Path.of(path.trim());
            Path candidate = input.toAbsolutePath().normalize();
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            // 不存在的路径可能是测试或外部传入的逻辑项目键，不要在 Windows
            // 上擅自拼接当前盘符，确保同一逻辑键可以稳定匹配记忆作用域。
            String raw = path.trim().replace('\\', '/');
            // Unix 风格的 /repo/key 在 Windows 上也可能只是逻辑项目键；
            // 只有带盘符的路径才按本机绝对路径处理。
            return raw.startsWith("/") && !raw.matches("^[A-Za-z]:/.*") ? raw : candidate.toString();
        } catch (Exception e) {
            return path.trim().replace('\\', '/');
        }
    }
}
