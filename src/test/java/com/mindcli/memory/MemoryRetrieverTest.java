package com.mindcli.memory;

import com.mindcli.llm.GLMClient;
import com.mindcli.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
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
        longTerm.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));
        longTerm.store(new MemoryEntry("f2", "项目路径: /home/user/project", MemoryEntry.MemoryType.FACT, null, 10));

        // candidates (2) <= limit (5), bypass LLM routing
        var results = retriever.retrieveLongTerm("Java", 5, null);
        assertEquals(2, results.size());
    }

    @Test
    void shouldReturnEmptyOnLlmFailure() {
        // 存储超过 limit 的条目迫使走 LLM 路由
        for (int i = 1; i <= 10; i++) {
            longTerm.store(new MemoryEntry("f" + i, "记忆条目 " + i, MemoryEntry.MemoryType.FACT, null, 10));
        }

        // StubGLMClient 没有预设响应，将抛出异常 → LLM 失败 → 返回空
        var results = retriever.retrieveLongTerm("查询", 3, null);
        assertTrue(results.isEmpty(), "LLM 失败应静默返回空列表");
    }

    @Test
    void shouldReturnLlmSelectedEntries() throws Exception {
        // 存储超过 limit 的条目
        for (int i = 1; i <= 10; i++) {
            longTerm.store(new MemoryEntry("f" + i, "记忆条目内容 " + i, MemoryEntry.MemoryType.FACT, null, 10));
        }

        // 预设 LLM 响应：选中 f3, f7
        llmClient.responses.add(new LlmClient.ChatResponse("assistant", "f3\nf7", null, 30, 10));
        MemoryRetriever r = new MemoryRetriever(llmClient, longTerm);

        var results = r.retrieveLongTerm("查询条目3和7", 3, null);
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(e -> e.getId().equals("f3")));
        assertTrue(results.stream().anyMatch(e -> e.getId().equals("f7")));
    }

    @Test
    void shouldReturnEmptyWhenLlmRespondsNone() throws Exception {
        for (int i = 1; i <= 10; i++) {
            longTerm.store(new MemoryEntry("f" + i, "记忆条目内容 " + i, MemoryEntry.MemoryType.FACT, null, 10));
        }

        llmClient.responses.add(new LlmClient.ChatResponse("assistant", "NONE", null, 30, 10));
        MemoryRetriever r = new MemoryRetriever(llmClient, longTerm);

        var results = r.retrieveLongTerm("查询", 3, null);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldBuildContextForQuery() {
        longTerm.store(new MemoryEntry("f1", "项目路径: /home/dev/myapp", MemoryEntry.MemoryType.FACT, null, 10));

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
        longTerm.store(new MemoryEntry("global", "默认用中文回答", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 10));
        longTerm.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"), 10));
        longTerm.store(new MemoryEntry("other", "其他项目使用 Python", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/other"), 10));

        // candidates (3) <= limit (10), bypass LLM routing, returns project-filtered directly
        var results = retriever.retrieveLongTerm("项目", 10, "/repo/current");
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(e -> e.getId().equals("global")));
        assertTrue(results.stream().anyMatch(e -> e.getId().equals("current")));
        assertTrue(results.stream().noneMatch(e -> e.getId().equals("other")));
    }

    @Test
    void shouldReturnEmptyWhenLlmClientIsNull() {
        MemoryRetriever noLlmRetriever = new MemoryRetriever(null, longTerm);
        for (int i = 1; i <= 10; i++) {
            longTerm.store(new MemoryEntry("f" + i, "记忆条目内容 " + i, MemoryEntry.MemoryType.FACT, null, 10));
        }

        var results = noLlmRetriever.retrieveLongTerm("查询", 3, null);
        assertTrue(results.isEmpty(), "无 LLM 客户端时无法路由，应返回空");
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
