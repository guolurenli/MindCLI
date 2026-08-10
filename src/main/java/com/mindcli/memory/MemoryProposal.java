package com.mindcli.memory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 自动提取产生的待确认长期记忆候选。
 */
public record MemoryProposal(
        String id,
        String name,
        String content,
        MemoryEntry.MemoryType type,
        Map<String, String> metadata,
        Instant createdAt,
        Status status
) {
    public enum Status {
        PROPOSED
    }

    public static MemoryProposal proposed(String name, String content, MemoryEntry.MemoryType type,
                                          Map<String, String> metadata) {
        return new MemoryProposal(
                "proposal-" + UUID.randomUUID().toString().substring(0, 8),
                name == null ? "" : name,
                content,
                type == null ? MemoryEntry.MemoryType.PROJECT_FACT : type,
                metadata == null ? Map.of() : Map.copyOf(metadata),
                Instant.now(),
                Status.PROPOSED
        );
    }
}
