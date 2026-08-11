package com.mindcli.capability.memory;

import com.mindcli.platform.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void ignoresSyntheticLspUserMessagesWhenCountingIncrementalUserTurns() {
        CountingClient llmClient = new CountingClient();
        MemoryExtractor extractor = new MemoryExtractor(llmClient, new LongTermMemory(tempDir.toFile()));
        String longDiagnostic = "[LSP 诊断注入]\n" + "Broken.java:1:1 [error] ".repeat(40);

        extractor.extractFactsIncremental(List.of(
                LlmClient.Message.system("system"),
                LlmClient.Message.user("写一个有语法问题的 Java 文件"),
                LlmClient.Message.assistant("", List.of(
                        new LlmClient.ToolCall("call_1",
                                new LlmClient.ToolCall.Function("write_file", "{\"path\":\"Broken.java\"}")))),
                LlmClient.Message.tool("call_1", "文件已写入: Broken.java"),
                LlmClient.Message.user(longDiagnostic),
                LlmClient.Message.assistant("已看到诊断。")
        ));

        assertEquals(0, llmClient.callCount);
    }

    private static final class CountingClient implements LlmClient {
        private int callCount;

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            callCount++;
            return new ChatResponse("assistant", "NO_FACTS", null, 1, 1);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "counting";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
