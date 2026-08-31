package com.mindcli.agent;

import com.mindcli.capability.memory.MemoryEntry;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.platform.llm.GLMClient;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.InMemoryRunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryLayeredReadTest {
    @TempDir
    Path tempDir;

    @Test
    void reactPromptContainsMemoryCatalogButNotBodyBeforeReadTool() {
        String previousMemoryDir = System.getProperty("mindcli.memory.dir");
        System.setProperty("mindcli.memory.dir", tempDir.toString());
        try {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new SingleResponseClient(), registry, new InMemoryRunStore());
        String projectKey = agent.getMemoryManager().getCurrentProject();
        agent.getMemoryManager().getLongTermMemory().store(new MemoryEntry(
                "fact-layered", "执行任务", "敏感的完整记忆正文", MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "global"), 10));
        assertTrue(agent.getMemoryManager().searchLongTerm("敏感", 5).stream()
                .anyMatch(entry -> entry.getId().equals("fact-layered")));

        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "执行任务", tempDir.toString());
        agent.run(context, new InMemoryRunStore());

        String systemPrompt = agent.getConversationHistory().get(0).content();
        assertTrue(systemPrompt.contains("执行任务"), systemPrompt);
        assertFalse(systemPrompt.contains("敏感的完整记忆正文"), systemPrompt);
        } finally {
            if (previousMemoryDir == null) {
                System.clearProperty("mindcli.memory.dir");
            } else {
                System.setProperty("mindcli.memory.dir", previousMemoryDir);
            }
        }
    }

    private static final class SingleResponseClient extends GLMClient {
        private SingleResponseClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "已完成", null, 1, 1);
        }
    }
}
