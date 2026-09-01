package com.mindcli.capability.memory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 确定性长期记忆检索；正文默认由 search_memory/read_memory 按需读取。 */
public class MemoryRetriever {
    private final MemoryStore memoryStore;
    private final Set<String> surfacedThisTurn = new HashSet<>();

    public MemoryRetriever(MemoryStore memoryStore) {
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
