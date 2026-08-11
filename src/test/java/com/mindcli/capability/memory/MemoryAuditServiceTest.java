package com.mindcli.capability.memory;

import com.mindcli.runtime.run.AgentRunEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAuditServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldRecordAndExportMemoryAuditEvents() throws Exception {
        MemoryAuditService service = new MemoryAuditService(tempDir.resolve("audit.jsonl"));
        service.record(AgentRunEventType.MEMORY_WRITTEN, Map.of(
                "memoryId", "fact-1",
                "source", "manual"));
        service.record(AgentRunEventType.MEMORY_DENIED, Map.of(
                "policyId", "memory.sensitive",
                "source", "manual"));

        assertEquals(2, service.list().size());

        Path exported = service.exportMarkdown(tempDir.resolve("exports"),
                LocalDateTime.of(2026, 8, 10, 16, 30));

        String markdown = Files.readString(exported);
        assertTrue(markdown.contains("# MindCLI 记忆审计导出"), markdown);
        assertTrue(markdown.contains("MEMORY_WRITTEN"), markdown);
        assertTrue(markdown.contains("fact-1"), markdown);
        assertTrue(markdown.contains("MEMORY_DENIED"), markdown);
        assertTrue(markdown.contains("memory.sensitive"), markdown);
        assertTrue(markdown.contains("MEMORY_EXPORTED"), markdown);
        assertEquals(3, service.list().size());
    }
}
