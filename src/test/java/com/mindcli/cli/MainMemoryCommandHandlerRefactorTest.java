package com.mindcli.cli;

import com.mindcli.cli.command.MemoryCommandHandler;
import com.mindcli.memory.MemoryEntry;
import com.mindcli.memory.MemoryProposal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainMemoryCommandHandlerRefactorTest {

    @Test
    void parsesSavePayloadScope() {
        assertEquals(new MemoryCommandHandler.MemorySaveRequest("默认中文回答", "global"),
                MemoryCommandHandler.parseSave("--global 默认中文回答"));
        assertEquals(new MemoryCommandHandler.MemorySaveRequest("项目使用 Java 17", "project"),
                MemoryCommandHandler.parseSave("--project 项目使用 Java 17"));
        assertEquals(new MemoryCommandHandler.MemorySaveRequest("普通事实", "project"),
                MemoryCommandHandler.parseSave("普通事实"));
        assertEquals(new MemoryCommandHandler.MemorySaveRequest("", "global"),
                MemoryCommandHandler.parseSave("--global"));
    }

    @Test
    void formatsMemoryEntriesWithScopeAndShortProjectPath() {
        MemoryEntry entry = new MemoryEntry(
                "fact-1",
                "项目默认使用 Maven quick profile",
                MemoryEntry.MemoryType.PROJECT_FACT,
                Instant.parse("2026-08-10T10:15:30Z"),
                Map.of("scope", "project", "project", "D:\\work\\team\\MindCLI"),
                8);

        String output = MemoryCommandHandler.formatEntries("📋 长期记忆列表", List.of(entry));

        assertTrue(output.contains("fact-1 [project]"));
        assertTrue(output.contains("team"));
        assertTrue(output.contains("MindCLI"));
        assertTrue(output.contains("2026-08-10T10:15:30Z"));
        assertTrue(output.contains("项目默认使用 Maven quick profile"));
    }

    @Test
    void formatsMemoryProposalsWithPreview() {
        MemoryProposal proposal = new MemoryProposal(
                "proposal-1",
                "用户偏好",
                "用户偏好使用中文回答\n并且希望先写技术文档",
                MemoryEntry.MemoryType.USER_PREFERENCE,
                Map.of("scope", "global"),
                Instant.parse("2026-08-10T11:00:00Z"),
                MemoryProposal.Status.PROPOSED);

        String output = MemoryCommandHandler.formatProposals("📋 待确认候选记忆", List.of(proposal));

        assertTrue(output.contains("proposal-1 [PROPOSED] USER_PREFERENCE"));
        assertTrue(output.contains("2026-08-10T11:00:00Z"));
        assertTrue(output.contains("用户偏好"));
        assertTrue(output.contains("用户偏好使用中文回答 并且希望先写技术文档"));
    }
}
