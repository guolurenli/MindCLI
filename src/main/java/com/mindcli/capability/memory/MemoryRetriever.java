package com.mindcli.capability.memory;

import com.mindcli.platform.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器 - LLM 路由替代关键词匹配
 *
 * 对齐 Claude Code 的 LLM-routed 检索方案（src/memdir/findRelevantMemories.ts）：
 * 1. 扫描所有长期记忆，构建候选清单（只发 id + name 摘要，不发全文，同 scanMemoryFiles）
 * 2. 向 LLM 发轻量侧查询（lightQuery）："哪 5 条最相关？"
 * 3. 按 LLM 选择过滤，返回结果
 * 4. LLM 调用失败时静默跳过（对齐 Claude Code —— 注入错误记忆比不注入更糟）
 * 5. 本轮已展示过的记忆不再重复注入（alreadySurfaced 去重）
 * 6. >1 天旧记忆注入时附加时效警告
 */
public class MemoryRetriever {
    private static final Logger log = LoggerFactory.getLogger(MemoryRetriever.class);

    private final LlmClient llmClient;
    private final MemoryStore memoryStore;

    /** 本轮对话中已注入的记忆 id 集合，防止同一条记忆反复注入 */
    private final Set<String> surfacedThisTurn = new HashSet<>();

    public MemoryRetriever(LlmClient llmClient, MemoryStore memoryStore) {
        this.llmClient = llmClient;
        this.memoryStore = memoryStore;
    }

    /** 新一轮对话开始时调用，重置去重集合 */
    public void resetSurfaced() {
        surfacedThisTurn.clear();
    }

    /**
     * 检索与查询最相关的长期记忆（LLM 路由方式）。
     */
    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey) {
        return retrieveLongTerm(query, limit, projectKey, Set.of());
    }

    /**
     * 检索与查询最相关的长期记忆，支持工具感知过滤。
     *
     * @param query           用户查询文本
     * @param limit           返回条数上限
     * @param projectKey      项目路径（用于作用域过滤）
     * @param activeToolNames 当前活跃的工具名（用于过滤参考文档类记忆）
     */
    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey,
                                               Set<String> activeToolNames) {
        List<MemoryEntry> candidates = memoryStore.getAll(projectKey).stream()
                .filter(e -> !isGovernedOut(e))
                .filter(e -> !surfacedThisTurn.contains(e.getId()))
                .filter(e -> !isToolRefNoise(e, activeToolNames))
                .collect(Collectors.toList());

        if (candidates.size() <= limit) {
            markSurfaced(candidates);
            return candidates;
        }
        if (llmClient == null) return List.of();

        try {
            List<MemoryEntry> selected = llmRoutedRetrieve(candidates, query, limit);
            markSurfaced(selected);
            return selected;
        } catch (Exception e) {
            log.warn("LLM 路由检索失败，静默跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 构建上下文：将相关记忆组装为 system prompt 注入文本，附带时效警告。
     */
    public String buildContextForQuery(String query, int maxTokens, String projectKey) {
        return buildContextForQuery(query, maxTokens, projectKey, Set.of());
    }

    public String buildContextForQuery(String query, int maxTokens, String projectKey,
                                        Set<String> activeToolNames) {
        List<MemoryEntry> relevant = retrieveLongTerm(query, 10, projectKey, activeToolNames);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder("## 相关长期记忆\n\n");
        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;
            context.append("- [").append(entry.getType()).append("] ")
                   .append(entry.getContent());
            // 时效警告：>1 天的记忆附加提示
            long daysOld = ChronoUnit.DAYS.between(entry.getTimestamp(), Instant.now());
            if (daysOld > 1) {
                context.append(" ⚠️ 此记忆已 ").append(daysOld)
                       .append(" 天，请以当前实际状态为准");
            }
            context.append("\n");
            usedTokens += entry.getTokenCount();
        }
        context.append("\n");
        return context.toString();
    }

    // ===== 内部实现 =====

    private void markSurfaced(List<MemoryEntry> entries) {
        for (MemoryEntry e : entries) {
            surfacedThisTurn.add(e.getId());
        }
    }

    /** 过滤活跃工具的 REFERENCE 类型记忆（噪声），但保留警告/陷阱类 */
    private static boolean isToolRefNoise(MemoryEntry entry, Set<String> activeToolNames) {
        if (activeToolNames.isEmpty()) return false;
        if (entry.getType() != MemoryEntry.MemoryType.REFERENCE) return false;
        String content = entry.getContent().toLowerCase();
        // 如果包含警告/陷阱关键词，即使工具活跃也保留
        if (content.contains("警告") || content.contains("注意") || content.contains("陷阱")
                || content.contains("gotcha") || content.contains("warning")) {
            return false;
        }
        return activeToolNames.stream().anyMatch(tool -> content.contains(tool.toLowerCase()));
    }

    /** 过滤被治理状态排除或 TTL 已过期的记忆。 */
    private static boolean isGovernedOut(MemoryEntry entry) {
        Map<String, String> metadata = entry.getMetadata();
        String status = metadata.get("status");
        if (status != null) {
            switch (status.trim().toLowerCase()) {
                case "revoked", "deleted", "expired" -> {
                    return true;
                }
                default -> {
                    // keep legacy/active/unknown statuses visible in Phase 4
                }
            }
        }

        String expiresAt = metadata.get("expiresAt");
        if (expiresAt == null || expiresAt.isBlank()) {
            return false;
        }
        try {
            return !Instant.parse(expiresAt.trim()).isAfter(Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<MemoryEntry> llmRoutedRetrieve(
            List<MemoryEntry> candidates, String query, int limit) throws IOException {

        String manifest = buildManifest(candidates);
        String prompt = String.format("""
            以下是记忆条目的索引，每行格式为：id [类型] 标题

            %s

            用户查询："%s"

            请从以上索引中选出与用户查询最相关的记忆条目，最多 %d 条。
            只输出条目 id，每行一个，不要加任何解释。
            如果没有相关的，输出 NONE。
            """, manifest, query, limit);

        List<LlmClient.Message> request = List.of(
                LlmClient.Message.system("你是一个记忆检索助手，只输出相关的条目 id。"),
                LlmClient.Message.user(prompt)
        );

        // 使用轻量模型进行路由，节省主模型 token
        LlmClient.ChatResponse response = llmClient.lightQuery(request, 256);
        String result = response.content();
        if (result == null || "NONE".equals(result.trim())) {
            return List.of();
        }

        Set<String> selectedIds = result.lines()
                .map(String::trim)
                .filter(id -> !id.isEmpty() && !"NONE".equals(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return candidates.stream()
                .filter(e -> selectedIds.contains(e.getId()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 构建候选清单：优先使用 name（专用摘要），缺失时 fallback 到 content 截断。
     */
    private String buildManifest(List<MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : entries) {
            String preview = e.getName() != null && !e.getName().isBlank()
                    ? e.getName()
                    : (e.getContent().length() > 80
                        ? e.getContent().substring(0, 80).replace("\n", " ") + "..."
                        : e.getContent().replace("\n", " "));
            sb.append(e.getId())
              .append(" [").append(e.getType()).append("] ")
              .append(preview).append("\n");
        }
        return sb.toString();
    }
}
