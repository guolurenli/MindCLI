package com.mindcli.runtime.agent;

import com.mindcli.agent.AgentBudget;
import com.mindcli.llm.LlmClient;
import com.mindcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopExecutorTest {

    @Test
    void returnsCompletedWhenModelHasNoToolCalls() {
        FakeLlmClient llm = new FakeLlmClient(List.of(
                new LlmClient.ChatResponse("assistant", "final", null, 10, 3)
        ));
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentLoopExecutor executor = new AgentLoopExecutor(llm, new ToolDispatcher(invocations -> List.of()), runStore);
        AgentRunContext runContext = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        List<LlmClient.Message> messages = new ArrayList<>(List.of(LlmClient.Message.user("hello")));

        AgentLoopResult result = executor.execute(loopContext(runContext, messages));

        assertEquals(AgentLoopStatus.COMPLETED, result.status());
        assertEquals("final", result.content());
        assertEquals(2, messages.size());
        assertEquals("assistant", messages.get(1).role());
        assertEventTypes(runStore.events(runContext.runId()), AgentRunEventType.LLM_RESPONSE);
    }

    @Test
    void appendsToolMessagesAndContinuesUntilFinalAnswer() {
        LlmClient.ToolCall toolCall = toolCall("call_1", "read_file", "{\"path\":\"a.txt\"}");
        FakeLlmClient llm = new FakeLlmClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(toolCall), 10, 2),
                new LlmClient.ChatResponse("assistant", "final after tool", null, 8, 4)
        ));
        ToolDispatcher dispatcher = new ToolDispatcher(invocations -> List.of(
                new ToolRegistry.ToolExecutionResult("call_1", "read_file", "{\"path\":\"a.txt\"}",
                        "file text", 7, false, List.of())
        ));
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentLoopExecutor executor = new AgentLoopExecutor(llm, dispatcher, runStore);
        AgentRunContext runContext = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        List<LlmClient.Message> messages = new ArrayList<>(List.of(LlmClient.Message.user("hello")));

        AgentLoopResult result = executor.execute(loopContext(runContext, messages));

        assertEquals(AgentLoopStatus.COMPLETED, result.status());
        assertEquals("final after tool", result.content());
        assertEquals(List.of("user", "assistant", "tool", "assistant"),
                messages.stream().map(LlmClient.Message::role).toList());
        assertEquals("call_1", messages.get(2).toolCallId());
        assertEventTypes(runStore.events(runContext.runId()),
                AgentRunEventType.LLM_RESPONSE,
                AgentRunEventType.TOOL_CALL_REQUESTED,
                AgentRunEventType.TOOL_OUTCOME,
                AgentRunEventType.LLM_RESPONSE);
    }

    @Test
    void returnsBudgetExhaustedWhenBudgetStopsBeforeModelCall() {
        FakeLlmClient llm = new FakeLlmClient(List.of());
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentLoopExecutor executor = new AgentLoopExecutor(llm, new ToolDispatcher(invocations -> List.of()), runStore);
        AgentRunContext runContext = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        AgentBudget budget = new AgentBudget(100, 3, 1);
        budget.beginIteration();

        AgentLoopResult result = executor.execute(new AgentLoopContext(
                runContext,
                new ArrayList<>(List.of(LlmClient.Message.user("hello"))),
                List.of(),
                new AgentLoopPolicy("react", true),
                budget,
                LlmClient.StreamListener.NO_OP,
                AgentLoopObserver.NO_OP));

        assertEquals(AgentLoopStatus.BUDGET_EXHAUSTED, result.status());
        assertEventTypes(runStore.events(runContext.runId()), AgentRunEventType.BUDGET_EXHAUSTED);
    }

    @Test
    void returnsFailedWhenLlmCallFails() {
        FakeLlmClient llm = new FakeLlmClient(new IOException("llm down"));
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentLoopExecutor executor = new AgentLoopExecutor(llm, new ToolDispatcher(invocations -> List.of()), runStore);
        AgentRunContext runContext = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");

        AgentLoopResult result = executor.execute(loopContext(runContext,
                new ArrayList<>(List.of(LlmClient.Message.user("hello")))));

        assertEquals(AgentLoopStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("llm down"));
    }

    private static AgentLoopContext loopContext(AgentRunContext runContext, List<LlmClient.Message> messages) {
        return new AgentLoopContext(
                runContext,
                messages,
                List.of(),
                new AgentLoopPolicy("react", true),
                new AgentBudget(1_000_000, 3, 50),
                LlmClient.StreamListener.NO_OP,
                AgentLoopObserver.NO_OP);
    }

    private static LlmClient.ToolCall toolCall(String id, String name, String args) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, args));
    }

    private static void assertEventTypes(List<AgentRunEvent> events, AgentRunEventType... expected) {
        assertEquals(expected.length, events.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], events.get(i).type());
        }
    }

    private static final class FakeLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final IOException failure;

        private FakeLlmClient(List<ChatResponse> responses) {
            this.responses.addAll(responses);
            this.failure = null;
        }

        private FakeLlmClient(IOException failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            if (failure != null) {
                throw failure;
            }
            if (responses.isEmpty()) {
                throw new IOException("no response");
            }
            return responses.remove();
        }

        @Override
        public String getModelName() {
            return "fake";
        }

        @Override
        public String getProviderName() {
            return "fake";
        }
    }
}
