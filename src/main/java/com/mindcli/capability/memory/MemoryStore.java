package com.mindcli.capability.memory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 长期记忆存储 adapter seam。
 */
public interface MemoryStore extends Memory {
    List<MemoryEntry> search(String query, int limit, String projectKey);

    List<MemoryEntry> getAll(String projectKey);

    Optional<Path> storagePath();
}
