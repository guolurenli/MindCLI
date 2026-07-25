package com.mindcli.memory;

import com.mindcli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器 - LLM 路由替代关键词匹配
 *
 * 对齐 Claude Code 的 LLM-routed 检索方案（src/memdir/findRelevantMemories.ts）：
 * 1. 扫描所有长期记忆，构建候选清单（只发 id + 摘要行，不发全文，同 scanMemoryFiles）
 * 2. 向 LLM 发轻量侧查询："哪 5 条最相关？"（同 selectRelevantMemories via sideQuery）
 * 3. 按 LLM 选择过滤，返回结果
 * 4. LLM 调用失败时静默跳过，不注入记忆（对齐 Claude Code —— 无降级兜底，
 *    因为注入错误记忆比不注入记忆更糟）
 */
public class MemoryRetriever {
    private static final Logger log = LoggerFactory.getLogger(MemoryRetriever.class);

    private final LlmClient llmClient;
    private final LongTermMemory longTermMemory;

    public MemoryRetriever(LlmClient llmClient, LongTermMemory longTermMemory) {
        this.llmClient = llmClient;
        this.longTermMemory = longTermMemory;
    }

    /**
     * 检索与查询最相关的长期记忆（LLM 路由方式）。
     * LLM 调用失败时静默返回空列表，不降级兜底（对齐 Claude Code）。
     *
     * @param query      用户查询文本
     * @param limit      返回条数上限（建议 ≤ 10）
     * @param projectKey 项目路径（用于作用域过滤）
     * @return 按相关度排序的记忆条目列表，失败时返回空
     */
    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey) {
        List<MemoryEntry> candidates = longTermMemory.getAll().stream()
                .filter(e -> LongTermMemory.isVisibleInProject(e, projectKey))
                .collect(Collectors.toList());

        if (candidates.size() <= limit) return candidates;
        if (llmClient == null) return List.of();

        try {
            return llmRoutedRetrieve(candidates, query, limit);
        } catch (Exception e) {
            log.warn("LLM 路由检索失败，静默跳过: {}", e.getMessage());
            return List.of(); // Claude Code 策略：无降级，注入错误记忆比不注入更糟
        }
    }

    /**
     * 构建上下文：将相关记忆组装为 system prompt 注入文本
     */
    public String buildContextForQuery(String query, int maxTokens, String projectKey) {
        List<MemoryEntry> relevant = retrieveLongTerm(query, 10, projectKey);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder("## 相关长期记忆\n\n");
        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;
            context.append("- [").append(entry.getType()).append("] ")
                   .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
        }
        context.append("\n");
        return context.toString();
    }

    // ===== LLM 路由核心逻辑 =====

    private List<MemoryEntry> llmRoutedRetrieve(
            List<MemoryEntry> candidates, String query, int limit) throws IOException {

        String manifest = buildManifest(candidates);
        String prompt = String.format("""
            以下是记忆条目的索引，每行格式为：id [类型] 内容摘要

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

        LlmClient.ChatResponse response = llmClient.chat(request, null);
        String result = response.content();
        if (result == null || "NONE".equals(result.trim())) {
            return List.of();
        }

        Set<String> selectedIds = result.lines()
                .map(String::trim)
                .filter(id -> !id.isEmpty() && !"NONE".equals(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<MemoryEntry> selected = candidates.stream()
                .filter(e -> selectedIds.contains(e.getId()))
                .limit(limit)
                .collect(Collectors.toList());

        return selected;
    }

    /**
     * 构建候选清单：只发 id + 第一句 + 类型，不发全文以节省检索 token
     */
    private String buildManifest(List<MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : entries) {
            String preview = e.getContent().length() > 80
                    ? e.getContent().substring(0, 80).replace("\n", " ") + "..."
                    : e.getContent().replace("\n", " ");
            sb.append(e.getId())
              .append(" [").append(e.getType()).append("] ")
              .append(preview).append("\n");
        }
        return sb.toString();
    }
}
