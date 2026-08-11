package com.mindcli.capability.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryTest {
    @TempDir
    Path tempDir;

    private LongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new LongTermMemory(tempDir.toFile());
    }

    @Test
    void shouldStoreAndRetrieve() {
        MemoryEntry entry = new MemoryEntry("fact-1", "项目使用Java 17", MemoryEntry.MemoryType.PROJECT_FACT, null, 10);
        memory.store(entry);

        assertTrue(memory.retrieve("fact-1").isPresent());
        assertEquals("项目使用Java 17", memory.retrieve("fact-1").get().getContent());
    }

    @Test
    void shouldDeduplicateSameContent() {
        MemoryEntry entry1 = new MemoryEntry("fact-1", "相同内容", MemoryEntry.MemoryType.PROJECT_FACT, null, 5);
        MemoryEntry entry2 = new MemoryEntry("fact-2", "相同内容", MemoryEntry.MemoryType.PROJECT_FACT, null, 5);

        memory.store(entry1);
        memory.store(entry2);

        assertEquals(1, memory.size());
    }

    @Test
    void shouldSearchBySubstring() {
        memory.store(new MemoryEntry("f1", "用户偏好使用IntelliJ IDEA", MemoryEntry.MemoryType.PROJECT_FACT, null, 10));
        memory.store(new MemoryEntry("f2", "项目路径: /home/user/project", MemoryEntry.MemoryType.PROJECT_FACT, null, 10));

        var results = memory.search("IntelliJ", 5);
        assertEquals(1, results.size());
    }

    @Test
    void shouldSearchChineseWithoutRelyingOnTokenizers() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.PROJECT_FACT, null, 10));

        // 新 search() 使用简单子串匹配，"偏好使用"是原文本的子串
        var results = memory.search("偏好使用", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldSearchChineseContentDirectly() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.PROJECT_FACT, null, 10));

        // 新 search() 使用简单子串匹配，"Java开发"是原文本的子串
        var results = memory.search("Java开发", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldDeleteEntry() {
        memory.store(new MemoryEntry("f1", "测试内容", MemoryEntry.MemoryType.PROJECT_FACT, null, 5));
        assertTrue(memory.delete("f1"));
        assertEquals(0, memory.size());
    }

    @Test
    void deleteKeepsTombstoneFileOutOfReloadedActiveMemory() throws Exception {
        memory.store(new MemoryEntry("f1", "测试内容", MemoryEntry.MemoryType.PROJECT_FACT, null, 5));

        assertTrue(memory.delete("f1"));

        Path tombstone = tempDir.resolve("f1.md");
        assertTrue(Files.exists(tombstone));
        String raw = Files.readString(tombstone);
        assertTrue(raw.contains("status: deleted"), raw);
        assertTrue(raw.contains("deletedAt:"), raw);

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(0, reloaded.size());
        assertTrue(reloaded.search("测试内容", 5).isEmpty());
    }

    @Test
    void shouldFilterByType() {
        memory.store(new MemoryEntry("f1", "事实1", MemoryEntry.MemoryType.PROJECT_FACT, null, 5));
        memory.store(new MemoryEntry("s1", "偏好1", MemoryEntry.MemoryType.USER_PREFERENCE, null, 5));

        var facts = memory.getByType(MemoryEntry.MemoryType.PROJECT_FACT);
        assertEquals(1, facts.size());
    }

    @Test
    void shouldPersistAndReload() {
        memory.store(new MemoryEntry("f1", "持久化测试内容", MemoryEntry.MemoryType.PROJECT_FACT, null, 10));
        memory.store(new MemoryEntry("s1", "摘要测试", MemoryEntry.MemoryType.PROJECT_FACT, null, 8));

        // 创建新实例，从磁盘加载
        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.retrieve("f1").isPresent());
    }

    @Test
    void shouldPreserveTimestampAfterReload() {
        Instant timestamp = Instant.parse("2026-04-20T12:34:56Z");
        memory.store(new MemoryEntry("f1", "带时间戳的事实", MemoryEntry.MemoryType.PROJECT_FACT, timestamp, null, 10));

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(timestamp, reloaded.retrieve("f1").orElseThrow().getTimestamp());
    }

    @Test
    void shouldFilterProjectScopedMemories() {
        memory.store(new MemoryEntry("global", "默认用中文回答", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "global"), 10));
        memory.store(new MemoryEntry("project-a", "项目A使用 Java 17", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project", "project", "/repo/a"), 10));
        memory.store(new MemoryEntry("project-b", "项目B使用 Python", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project", "project", "/repo/b"), 10));

        var visible = memory.getAll("/repo/a");

        assertEquals(2, visible.size());
        assertTrue(visible.stream().anyMatch(entry -> entry.getId().equals("global")));
        assertTrue(visible.stream().anyMatch(entry -> entry.getId().equals("project-a")));
        assertTrue(visible.stream().noneMatch(entry -> entry.getId().equals("project-b")));
    }

    @Test
    void legacyMemoriesWithoutScopeRemainGlobal() {
        MemoryEntry legacy = new MemoryEntry("legacy", "历史偏好", MemoryEntry.MemoryType.PROJECT_FACT, null, 10);

        assertEquals("global", LongTermMemory.scopeOf(legacy));
        assertTrue(LongTermMemory.isVisibleInProject(legacy, "/repo/current"));
    }
}
