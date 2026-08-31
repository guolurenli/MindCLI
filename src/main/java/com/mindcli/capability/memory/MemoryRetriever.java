package com.mindcli.capability.memory;

import com.mindcli.platform.llm.LlmClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 确定性长期记忆检索；正文默认由 search_memory/read_memory 按需读取。 */
public class MemoryRetriever {
    private final MemoryStore memoryStore;
    private final Set<String> surfacedThisTurn = new HashSet<>();

    /** 保留旧构造签名兼容性；检索本身不再调用 LLM。 */
    public MemoryRetriever(LlmClient ignoredLlmClient, MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public void resetSurfaced() {
        surfacedThisTurn.clear();
    }

    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey) {
        return retrieveLongTerm(query, limit, projectKey, Set.of());
    }

    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey,
                                               Set<String> activeToolNames) {
        List<MemoryEntry> selected = memoryStore.search(query, limit, projectKey).stream()
                .filter(entry -> !surfacedThisTurn.contains(entry.getId()))
                .filter(entry -> !isToolRefNoise(entry, activeToolNames))
                .toList();
        surfacedThisTurn.addAll(selected.stream().map(MemoryEntry::getId).toList());
        return selected;
    }

    /**
     * @deprecated 生产路径使用 search_memory/read_memory，避免自动注入正文。
     */
    @Deprecated
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
            long daysOld = ChronoUnit.DAYS.between(entry.getTimestamp(), Instant.now());
            if (daysOld > 1) {
                context.append(" 此记忆已 ").append(daysOld).append(" 天，请以当前实际状态为准");
            }
            context.append("\n");
            usedTokens += entry.getTokenCount();
        }
        return context.append("\n").toString();
    }

    private static boolean isToolRefNoise(MemoryEntry entry, Set<String> activeToolNames) {
        if (activeToolNames == null || activeToolNames.isEmpty()
                || entry.getType() != MemoryEntry.MemoryType.REFERENCE) {
            return false;
        }
        String content = entry.getContent().toLowerCase();
        if (content.contains("警告") || content.contains("注意") || content.contains("陷阱")
                || content.contains("gotcha") || content.contains("warning")) {
            return false;
        }
        return activeToolNames.stream().anyMatch(tool -> content.contains(tool.toLowerCase()));
    }
}
