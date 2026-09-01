package com.mindcli.capability.memory;

import com.mindcli.platform.llm.GLMClient;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.InMemoryRunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldClearLongTermMemoryOnlyWhenExplicitlyRequested() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);

        memoryManager.storeFact("用户偏好使用中文交流");
        memoryManager.storeFact("项目路径: /tmp/demo");
        assertEquals(2, longTermMemory.size());

        memoryManager.clearLongTerm();

        assertEquals(0, longTermMemory.size());
    }

    @Test
    void shouldStoreProjectScopedFactsByDefault() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);
        memoryManager.setProjectPath("/repo/current");

        memoryManager.storeFact("当前项目使用 Java 17");

        MemoryEntry projectEntry = longTermMemory.search("Java", 5, memoryManager.getCurrentProject()).get(0);
        assertEquals("project", projectEntry.getMetadata().get("scope"));
        assertTrue(projectEntry.getMetadata().get("project").replace("\\", "/").endsWith("/repo/current"));
    }

    @Test
    void shouldRouteGlobalManualSaveToProposalApproval() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);

        MemoryWriteResult result = memoryManager.storeFact("默认用中文回答", "global");

        assertEquals(MemoryWriteResult.Status.PROPOSED, result.status());
        assertEquals(0, longTermMemory.size());
        assertEquals(1, memoryManager.listPendingMemoryProposals().size());
        MemoryProposal proposal = memoryManager.listPendingMemoryProposals().get(0);
        assertEquals("global", proposal.metadata().get("scope"));
        assertEquals("manual", proposal.metadata().get("source"));
        assertTrue(result.message().contains("已生成待确认候选记忆(global)"), result.message());
        assertTrue(result.message().contains(proposal.id()), result.message());
        assertTrue(result.message().contains("/memory approve " + proposal.id()), result.message());
    }

    @Test
    void shouldDenySensitiveManualSave() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);

        MemoryWriteResult result = memoryManager.storeFact("API_KEY=sk-1234567890abcdefghijklmnopqrst", "project");

        assertEquals(MemoryWriteResult.Status.DENIED, result.status());
        assertEquals(0, longTermMemory.size());
        assertEquals(0, memoryManager.listPendingMemoryProposals().size());
        assertEquals("memory.sensitive", result.policyId());
        assertTrue(result.message().contains("保存长期记忆被策略拒绝"), result.message());
        assertTrue(result.message().contains("memory.sensitive"), result.message());
    }

    @Test
    void shouldSearchOnlyCurrentProjectAndGlobalFacts() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);
        memoryManager.setProjectPath("/repo/current");
        longTermMemory.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.PROJECT_FACT,
                java.util.Map.of("scope", "project", "project", memoryManager.getCurrentProject()), 10));
        longTermMemory.store(new MemoryEntry("other", "其他项目使用 Java 8", MemoryEntry.MemoryType.PROJECT_FACT,
                java.util.Map.of("scope", "project", "project", "/repo/other"), 10));

        List<MemoryEntry> results = memoryManager.searchLongTerm("Java", 10);

        assertEquals(1, results.size());
        assertEquals("current", results.get(0).getId());
    }

    @Test
    void shouldSearchMemoryWithSummariesAndReadByVisibleId() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);
        memoryManager.setProjectPath("/repo/current");
        longTermMemory.store(new MemoryEntry("current", "测试命令", "完整正文: mvn test -Pquick",
                MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project", "project", "/repo/current"), 10));
        longTermMemory.store(new MemoryEntry("other", "测试命令", "其他项目正文",
                MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project", "project", "/repo/other"), 10));

        String results = memoryManager.searchMemory("测试命令", 5);
        String body = memoryManager.readMemory("current");

        assertTrue(results.contains("current"), results);
        assertTrue(results.contains("测试命令"), results);
        assertFalse(results.contains("完整正文"), "搜索结果不应默认返回完整正文");
        assertFalse(results.contains("other"), "搜索结果不应泄露其他项目记忆");
        assertTrue(body.contains("完整正文: mvn test -Pquick"), body);
        assertTrue(body.contains("current"), body);
        assertTrue(memoryManager.readMemory("other").contains("不可见"));
    }

    @Test
    void shouldWarnWhenSearchReturnsMultipleMemoryCandidates() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);
        memoryManager.setProjectPath("/repo/current");
        longTermMemory.store(new MemoryEntry("stack-old", "技术栈", "项目使用 Java 17",
                MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project", "project", "/repo/current"), 10));
        longTermMemory.store(new MemoryEntry("stack-new", "技术栈升级记录", "项目已升级到 Java 21",
                MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project", "project", "/repo/current"), 10));

        String results = memoryManager.searchMemory("Java", 5);

        assertTrue(results.contains("stack-old"), results);
        assertTrue(results.contains("stack-new"), results);
        assertTrue(results.contains("multipleCandidates"), results);
        assertTrue(results.contains("read_memory"), results);
        assertTrue(results.contains("当前代码"), results);
    }

    @Test
    void shouldSearchMemoryWithUntitledEntryWithoutSerializationFailure() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);
        longTermMemory.store(new MemoryEntry("untitled", "Java 21 配置",
                MemoryEntry.MemoryType.PROJECT_FACT, Map.of("scope", "global"), 10));

        String results = memoryManager.searchMemory("Java", 5);

        assertTrue(results.contains("untitled"), results);
        assertFalse(results.contains("检索长期记忆失败"), results);
    }

    @Test
    void shouldNotAutoExtractLongTermMemoryByDefault() throws Exception {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        CountingMemoryClient llmClient = new CountingMemoryClient(new LlmClient.ChatResponse(
                "assistant",
                "[中文]: [USER_PREFERENCE] 用户偏好使用中文回答",
                null,
                10,
                5
        ));
        MemoryManager memoryManager = new MemoryManager(llmClient, 128000, longTermMemory);

        memoryManager.extractFactsIncrementalAsync(memoryExtractionMessages());

        assertFalse(llmClient.awaitChatCall(300), "默认关闭时不应调用 LLM 自动提取长期记忆");
        assertEquals(0, llmClient.callCount());
        assertEquals(0, memoryManager.listPendingMemoryProposals().size());
        assertEquals(0, longTermMemory.size());
    }

    @Test
    void shouldCreatePendingProposalInsteadOfLongTermMemoryWhenAutoExtractEnabled() throws Exception {
        String previous = System.getProperty("mindcli.memory.autoExtract.enabled");
        System.setProperty("mindcli.memory.autoExtract.enabled", "true");
        try {
            LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
            CountingMemoryClient llmClient = new CountingMemoryClient(new LlmClient.ChatResponse(
                    "assistant",
                    "[中文]: [USER_PREFERENCE] 用户偏好使用中文回答",
                    null,
                    10,
                    5
            ));
            MemoryManager memoryManager = new MemoryManager(llmClient, 128000, longTermMemory);

            CompletableFuture<Void> extraction = memoryManager.extractFactsIncrementalAsync(memoryExtractionMessages());
            extraction.get(2, TimeUnit.SECONDS);

            assertTrue(llmClient.awaitChatCall(1_000), "显式开启后应保留旧自动提取路径");
            assertEquals(1, memoryManager.listPendingMemoryProposals().size());
            assertEquals(0, longTermMemory.size());
            MemoryProposal proposal = memoryManager.listPendingMemoryProposals().get(0);
            assertEquals(MemoryProposal.Status.PROPOSED, proposal.status());
            assertEquals(MemoryEntry.MemoryType.USER_PREFERENCE, proposal.type());
            assertEquals("用户偏好使用中文回答", proposal.content());
        assertEquals("extractor", proposal.metadata().get("source"));
        } finally {
            if (previous == null) {
                System.clearProperty("mindcli.memory.autoExtract.enabled");
            } else {
                System.setProperty("mindcli.memory.autoExtract.enabled", previous);
            }
        }
    }

    @Test
    void shouldNotPublishProposalWhenPersistenceFails() throws Exception {
        String previous = System.getProperty("mindcli.memory.autoExtract.enabled");
        System.setProperty("mindcli.memory.autoExtract.enabled", "true");
        try {
            LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
            CountingMemoryClient llmClient = new CountingMemoryClient(new LlmClient.ChatResponse(
                    "assistant",
                    "[中文]: [USER_PREFERENCE] 用户偏好使用中文回答",
                    null,
                    10,
                    5
            ));
            MemoryManager memoryManager = new MemoryManager(llmClient, 128000, longTermMemory);
            Files.createDirectory(tempDir.resolve("proposals.jsonl"));

            CompletableFuture<Void> extraction = memoryManager.extractFactsIncrementalAsync(memoryExtractionMessages());

            assertThrows(ExecutionException.class, () -> extraction.get(2, TimeUnit.SECONDS));
            assertEquals(0, memoryManager.listPendingMemoryProposals().size());
            assertEquals(0, memoryManager.listMemoryProposals().size());
        } finally {
            if (previous == null) {
                System.clearProperty("mindcli.memory.autoExtract.enabled");
            } else {
                System.setProperty("mindcli.memory.autoExtract.enabled", previous);
            }
        }
    }

    @Test
    void shouldReloadPendingProposalsFromProposalStore() throws Exception {
        String previous = System.getProperty("mindcli.memory.autoExtract.enabled");
        System.setProperty("mindcli.memory.autoExtract.enabled", "true");
        try {
            LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
            CountingMemoryClient llmClient = new CountingMemoryClient(new LlmClient.ChatResponse(
                    "assistant",
                    "[中文]: [USER_PREFERENCE] 用户偏好使用中文回答",
                    null,
                    10,
                    5
            ));
            MemoryManager first = new MemoryManager(llmClient, 128000, longTermMemory);

            first.extractFactsIncrementalAsync(memoryExtractionMessages());

            assertTrue(llmClient.awaitChatCall(1_000));
            waitUntilProposalCount(first, 1);

            MemoryManager reloaded = new MemoryManager(new StubGLMClient(List.of()), 128000,
                    new LongTermMemory(tempDir.toFile()));

            assertEquals(1, reloaded.listPendingMemoryProposals().size());
            assertEquals(MemoryProposal.Status.PROPOSED, reloaded.listPendingMemoryProposals().get(0).status());
        } finally {
            if (previous == null) {
                System.clearProperty("mindcli.memory.autoExtract.enabled");
            } else {
                System.setProperty("mindcli.memory.autoExtract.enabled", previous);
            }
        }
    }

    @Test
    void shouldApprovePendingProposalIntoLongTermMemory() throws Exception {
        String previous = System.getProperty("mindcli.memory.autoExtract.enabled");
        System.setProperty("mindcli.memory.autoExtract.enabled", "true");
        try {
            LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
            CountingMemoryClient llmClient = new CountingMemoryClient(new LlmClient.ChatResponse(
                    "assistant",
                    "[中文]: [USER_PREFERENCE] 用户偏好使用中文回答",
                    null,
                    10,
                    5
            ));
            MemoryManager memoryManager = new MemoryManager(llmClient, 128000, longTermMemory);

            memoryManager.extractFactsIncrementalAsync(memoryExtractionMessages());

            assertTrue(llmClient.awaitChatCall(1_000));
            waitUntilProposalCount(memoryManager, 1);

            MemoryProposal proposal = memoryManager.listPendingMemoryProposals().get(0);
            assertTrue(memoryManager.approveMemoryProposal(proposal.id()));
            assertEquals(MemoryProposal.Status.APPROVED, memoryManager.getMemoryProposal(proposal.id()).status());
            assertEquals(1, longTermMemory.size());
            assertEquals(0, memoryManager.listPendingMemoryProposals().stream()
                    .filter(p -> p.status() == MemoryProposal.Status.PROPOSED)
                    .count());
        } finally {
            if (previous == null) {
                System.clearProperty("mindcli.memory.autoExtract.enabled");
            } else {
                System.setProperty("mindcli.memory.autoExtract.enabled", previous);
            }
        }
    }

    @Test
    void shouldRejectPendingProposalWithoutWritingLongTermMemory() throws Exception {
        String previous = System.getProperty("mindcli.memory.autoExtract.enabled");
        System.setProperty("mindcli.memory.autoExtract.enabled", "true");
        try {
            LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
            CountingMemoryClient llmClient = new CountingMemoryClient(new LlmClient.ChatResponse(
                    "assistant",
                    "[中文]: [USER_PREFERENCE] 用户偏好使用中文回答",
                    null,
                    10,
                    5
            ));
            MemoryManager memoryManager = new MemoryManager(llmClient, 128000, longTermMemory);

            memoryManager.extractFactsIncrementalAsync(memoryExtractionMessages());

            assertTrue(llmClient.awaitChatCall(1_000));
            waitUntilProposalCount(memoryManager, 1);

            MemoryProposal proposal = memoryManager.listPendingMemoryProposals().get(0);
            assertTrue(memoryManager.rejectMemoryProposal(proposal.id()));
            assertEquals(MemoryProposal.Status.REJECTED, memoryManager.getMemoryProposal(proposal.id()).status());
            assertEquals(0, longTermMemory.size());
        } finally {
            if (previous == null) {
                System.clearProperty("mindcli.memory.autoExtract.enabled");
            } else {
                System.setProperty("mindcli.memory.autoExtract.enabled", previous);
            }
        }
    }

    @Test
    void shouldAuditManualWriteAndPolicyDenialWhenRunContextExists() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext runContext = AgentRunContext.create(AgentMode.REACT, "记忆审计", "/repo/current");

        MemoryWriteResult written = memoryManager.storeFact("当前项目使用 Java 17", "project", runContext, runStore);
        MemoryWriteResult denied = memoryManager.storeFact(
                "API_KEY=sk-1234567890abcdefghijklmnopqrst", "project", runContext, runStore);

        List<AgentRunEventType> types = runStore.events(runContext.runId()).stream()
                .map(AgentRunEvent::type)
                .toList();
        assertEquals(MemoryWriteResult.Status.WRITTEN, written.status());
        assertEquals(MemoryWriteResult.Status.DENIED, denied.status());
        assertTrue(types.contains(AgentRunEventType.MEMORY_WRITTEN), types.toString());
        assertTrue(types.contains(AgentRunEventType.MEMORY_DENIED), types.toString());
        assertTrue(memoryManager.getMemoryAuditService().list().stream()
                .anyMatch(record -> "MEMORY_DENIED".equals(record.type())
                        && "memory.sensitive".equals(record.attributes().get("policyId"))));
    }

    @Test
    void shouldAuditProposalApprovalRejectionAndDeletionWhenRunContextExists() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext runContext = AgentRunContext.create(AgentMode.REACT, "审批审计", "/repo/current");

        MemoryWriteResult approveCandidate = memoryManager.storeFact("默认用中文回答", "global", runContext, runStore);
        MemoryWriteResult rejectCandidate = memoryManager.storeFact("默认使用 Maven quick profile", "global", runContext, runStore);

        assertTrue(memoryManager.approveMemoryProposal(approveCandidate.proposal().id(), runContext, runStore));
        String memoryId = longTermMemory.getAll().get(0).getId();
        assertTrue(memoryManager.rejectMemoryProposal(rejectCandidate.proposal().id(), runContext, runStore));
        assertTrue(memoryManager.deleteLongTerm(memoryId, runContext, runStore));

        List<AgentRunEventType> types = runStore.events(runContext.runId()).stream()
                .map(AgentRunEvent::type)
                .toList();
        assertTrue(types.contains(AgentRunEventType.MEMORY_PROPOSED), types.toString());
        assertTrue(types.contains(AgentRunEventType.MEMORY_APPROVED), types.toString());
        assertTrue(types.contains(AgentRunEventType.MEMORY_REJECTED), types.toString());
        assertTrue(types.contains(AgentRunEventType.MEMORY_DELETED), types.toString());
    }

    @Test
    void shouldAppendMemoryProposedEventWhenAudited() throws Exception {
        String previous = System.getProperty("mindcli.memory.autoExtract.enabled");
        System.setProperty("mindcli.memory.autoExtract.enabled", "true");
        try {
            LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
            CountingMemoryClient llmClient = new CountingMemoryClient(new LlmClient.ChatResponse(
                    "assistant",
                    "[中文]: [USER_PREFERENCE] 用户偏好使用中文回答",
                    null,
                    10,
                    5
            ));
            MemoryManager memoryManager = new MemoryManager(llmClient, 128000, longTermMemory);
            InMemoryRunStore runStore = new InMemoryRunStore();
            AgentRunContext runContext = AgentRunContext.create(AgentMode.REACT, "记忆测试", "/repo/current");

            memoryManager.extractFactsIncrementalAsync(memoryExtractionMessages(), runContext, runStore);

            assertTrue(llmClient.awaitChatCall(1_000));
            waitUntilProposalCount(memoryManager, 1);
            AgentRunEvent event = runStore.events(runContext.runId()).get(0);
            assertEquals(AgentRunEventType.MEMORY_PROPOSED, event.type());
            assertEquals("1", event.attributes().get("proposalCount"));
            assertEquals("extractor", event.attributes().get("source"));
        } finally {
            if (previous == null) {
                System.clearProperty("mindcli.memory.autoExtract.enabled");
            } else {
                System.setProperty("mindcli.memory.autoExtract.enabled", previous);
            }
        }
    }

    @Test
    void policyStatusReportsDefaultGovernance() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);

        String status = memoryManager.getPolicyStatus();

        assertTrue(status.contains("自动提取: disabled"), status);
        assertTrue(status.contains("自动提取模式: disabled"), status);
        assertTrue(status.contains("待确认候选: 0"), status);
        assertTrue(status.contains("存储适配器: LongTermMemory"), status);
        assertTrue(status.contains(tempDir.toString()), status);
        assertTrue(status.contains("status=revoked/deleted/expired"), status);
        assertTrue(status.contains("expiresAt"), status);
        assertTrue(status.contains("MEMORY_CONTEXT_BUILT"), status);
        assertTrue(status.contains("MEMORY_PROPOSED"), status);
    }

    @Test
    void policyStatusReportsProposalOnlyModeWhenAutoExtractEnabled() {
        String previous = System.getProperty("mindcli.memory.autoExtract.enabled");
        System.setProperty("mindcli.memory.autoExtract.enabled", "true");
        try {
            LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
            MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 128000, longTermMemory);

            String status = memoryManager.getPolicyStatus();

            assertTrue(status.contains("自动提取: enabled"), status);
            assertTrue(status.contains("自动提取模式: proposal-only"), status);
        } finally {
            if (previous == null) {
                System.clearProperty("mindcli.memory.autoExtract.enabled");
            } else {
                System.setProperty("mindcli.memory.autoExtract.enabled", previous);
            }
        }
    }

    @Test
    void compressionTriggerRatioAppliesToAllModelsUniformly() {
        // 验证：长 window 模型也使用自动压缩阈值，没有"长模式不压缩"的二元开关
        MemoryManager memoryManager = new MemoryManager(new GLMClient("test-key"));

        assertEquals(0.835, memoryManager.getContextProfile().compressionTriggerRatio(), 0.001);
        assertEquals(200000, memoryManager.getTokenBudget().getContextWindow());
        assertEquals(167000, memoryManager.getContextProfile().compressionTriggerTokens());
    }

    private static List<LlmClient.Message> memoryExtractionMessages() {
        String stablePreference = "用户明确表达了跨会话稳定偏好: 以后默认使用中文回答。"
                + "这是一个长期偏好, 不是临时任务说明。".repeat(20);
        String stableFeedback = "用户再次确认这个偏好长期有效: 默认中文回答, 并希望后续会话继续遵守。"
                + "这是第二条真实用户消息, 用于满足增量提取的轮次门槛。".repeat(20);
        return List.of(
                LlmClient.Message.user(stablePreference),
                LlmClient.Message.assistant("好的, 我会记住这个长期偏好。"),
                LlmClient.Message.user(stableFeedback),
                LlmClient.Message.assistant("明白。")
        );
    }

    private static void waitUntilProposalCount(MemoryManager memoryManager, int expectedSize)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline
                && memoryManager.listPendingMemoryProposals().size() != expectedSize) {
            Thread.sleep(20);
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
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

    private static final class CountingMemoryClient extends GLMClient {
        private final Queue<ChatResponse> responses;
        private final CountDownLatch chatCalled = new CountDownLatch(1);
        private int callCount;

        private CountingMemoryClient(ChatResponse response) {
            super("test-key");
            this.responses = new ArrayDeque<>(List.of(response));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            callCount++;
            chatCalled.countDown();
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        private boolean awaitChatCall(long timeoutMillis) throws InterruptedException {
            return chatCalled.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private synchronized int callCount() {
            return callCount;
        }
    }
}
