package com.mindcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void longTermMemoryImplementsMemoryStoreContract() {
        MemoryStore store = new LongTermMemory(tempDir.toFile());
        store.store(new MemoryEntry("project", "当前项目使用 Java 17", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project", "project", "/repo/current"), 10));
        store.store(new MemoryEntry("global", "默认用中文回答", MemoryEntry.MemoryType.USER_PREFERENCE,
                Map.of("scope", "global"), 10));
        store.store(new MemoryEntry("other", "其他项目使用 Python", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project", "project", "/repo/other"), 10));

        assertEquals(2, store.getAll("/repo/current").size());
        assertEquals(1, store.search("Java", 10, "/repo/current").size());
        assertTrue(store.storagePath().isPresent());
        assertEquals(tempDir.toAbsolutePath().normalize(), store.storagePath().orElseThrow().normalize());
    }
}
