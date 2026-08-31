package com.mindcli.capability.memory;

import com.mindcli.platform.llm.GLMClient;
import com.mindcli.platform.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRetrieverTest {
    @TempDir
    Path tempDir;

    private LongTermMemory longTerm;
    private MemoryRetriever retriever;
    private StubGLMClient llmClient;

    @BeforeEach
    void setUp() {
        longTerm = new LongTermMemory(tempDir.toFile());
        llmClient = new StubGLMClient(new ArrayDeque<>());
        retriever = new MemoryRetriever(llmClient, longTerm);
    }

    @Test
    void shouldReturnAllCandidatesWhenAtOrBelowLimit() {
        longTerm.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.PROJECT_FACT, null, 10));
        longTerm.store(new MemoryEntry("f2", "项目路径: /home/user/project", MemoryEntry.MemoryType.PROJECT_FACT, null, 10));

        // 仅返回与查询匹配的条目
        var results = retriever.retrieveLongTerm("Java", 5, null);
        assertEquals(1, results.size());
    }

    @Test
    void shouldReturnMatchingEntriesWithoutLlmRouting() {
        for (int i = 1; i <= 10; i++) {
            longTerm.store(new MemoryEntry("f" + i, "记忆条目 " + i, MemoryEntry.MemoryType.PROJECT_FACT, null, 10));
        }

        var results = retriever.retrieveLongTerm("记忆条目", 3, null);
        assertEquals(3, results.size());
    }

    @Test
    void shouldReturnDeterministicallyRankedEntries() {
        Instant fixedTime = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= 10; i++) {
            longTerm.store(new MemoryEntry("f" + i, "记忆条目内容 " + i,
                    MemoryEntry.MemoryType.PROJECT_FACT, fixedTime, null, 10));
        }

        var results = retriever.retrieveLongTerm("条目内容", 3, null);
        assertEquals(3, results.size());
        assertEquals("f1", results.get(0).getId());
    }

    @Test
    void shouldReturnEmptyWhenThereIsNoTextMatch() {
        for (int i = 1; i <= 10; i++) {
            longTerm.store(new MemoryEntry("f" + i, "记忆条目内容 " + i, MemoryEntry.MemoryType.PROJECT_FACT, null, 10));
        }

        var results = retriever.retrieveLongTerm("完全不存在", 3, null);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldBuildContextForQuery() {
        longTerm.store(new MemoryEntry("f1", "项目路径: /home/dev/myapp", MemoryEntry.MemoryType.PROJECT_FACT, null, 10));

        // candidates (1) <= limit (10), bypass LLM routing, returns directly
        String context = retriever.buildContextForQuery("项目路径", 200, null);
        assertFalse(context.isEmpty());
        assertTrue(context.contains("/home/dev/myapp"));
        assertTrue(context.contains("## 相关长期记忆"));
    }

    @Test
    void shouldReturnEmptyContextForNoMatch() {
        String context = retriever.buildContextForQuery("Spring Boot", 200, null);
        assertTrue(context.isEmpty());
    }

    @Test
    void shouldFilterByProjectScope() {
        longTerm.store(new MemoryEntry("global", "项目默认用中文回答", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "global"), 10));
        longTerm.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project", "project", "/repo/current"), 10));
        longTerm.store(new MemoryEntry("other", "其他项目使用 Python", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project", "project", "/repo/other"), 10));

        var results = retriever.retrieveLongTerm("项目", 10, "/repo/current");
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(e -> e.getId().equals("global")));
        assertTrue(results.stream().anyMatch(e -> e.getId().equals("current")));
        assertTrue(results.stream().noneMatch(e -> e.getId().equals("other")));
    }

    @Test
    void shouldFilterRevokedDeletedExpiredAndPastTtlMemories() {
        longTerm.store(new MemoryEntry("legacy", "旧记忆仍可见", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "global"), 10));
        longTerm.store(new MemoryEntry("active", "活跃记忆仍可见", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "global", "status", "active"), 10));
        longTerm.store(new MemoryEntry("revoked", "撤销记忆不可见", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "global", "status", "revoked"), 10));
        longTerm.store(new MemoryEntry("deleted", "删除记忆不可见", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "global", "status", "deleted"), 10));
        longTerm.store(new MemoryEntry("expired", "状态过期记忆不可见", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "global", "status", "expired"), 10));
        longTerm.store(new MemoryEntry("past-ttl", "TTL 过期记忆不可见", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "global", "expiresAt", Instant.now().minusSeconds(60).toString()), 10));
        longTerm.store(new MemoryEntry("future-ttl", "TTL 未过期记忆可见", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "global", "expiresAt", Instant.now().plusSeconds(3600).toString()), 10));

        var results = retriever.retrieveLongTerm("记忆", 10, null);

        assertTrue(results.stream().anyMatch(e -> e.getId().equals("legacy")));
        assertTrue(results.stream().anyMatch(e -> e.getId().equals("active")));
        assertTrue(results.stream().anyMatch(e -> e.getId().equals("future-ttl")));
        assertTrue(results.stream().noneMatch(e -> e.getId().equals("revoked")));
        assertTrue(results.stream().noneMatch(e -> e.getId().equals("deleted")));
        assertTrue(results.stream().noneMatch(e -> e.getId().equals("expired")));
        assertTrue(results.stream().noneMatch(e -> e.getId().equals("past-ttl")));
    }

    @Test
    void shouldSearchWhenLlmClientIsNull() {
        MemoryRetriever noLlmRetriever = new MemoryRetriever(null, longTerm);
        for (int i = 1; i <= 10; i++) {
            longTerm.store(new MemoryEntry("f" + i, "记忆条目内容 " + i, MemoryEntry.MemoryType.PROJECT_FACT, null, 10));
        }

        var results = noLlmRetriever.retrieveLongTerm("记忆条目", 3, null);
        assertEquals(3, results.size());
    }

    // ===== Stub =====

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(Queue<ChatResponse> responses) {
            super("test-key");
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }
}
