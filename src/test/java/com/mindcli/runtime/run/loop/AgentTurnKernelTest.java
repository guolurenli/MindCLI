package com.mindcli.runtime.run.loop;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import com.mindcli.agent.AgentBudget;
import com.mindcli.capability.tool.ToolOutput;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.ToolExecution;
import com.mindcli.platform.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTurnKernelTest {

    @Test
    void completesTurnAndAppendsAssistantMessage() {
        FakeLlmClient llm = new FakeLlmClient(new LlmClient.ChatResponse("assistant", "done", null, 4, 2));
        List<LlmClient.Message> messages = new ArrayList<>(List.of(LlmClient.Message.user("hello")));
        AgentTurnKernel kernel = new AgentTurnKernel(llm, dispatcher());

        AgentTurnResult result = kernel.run(context(messages));

        assertTrue(result.completed());
        assertEquals("done", result.response().content());
        assertEquals(List.of("user", "assistant"), messages.stream().map(LlmClient.Message::role).toList());
    }

    @Test
    void dispatchesToolCallsAndAppendsResultsToHistory() {
        LlmClient.ToolCall call = new LlmClient.ToolCall("call-1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"));
        FakeLlmClient llm = new FakeLlmClient(new LlmClient.ChatResponse("assistant", "", List.of(call), 4, 2));
        List<LlmClient.Message> messages = new ArrayList<>(List.of(LlmClient.Message.user("inspect")));
        AgentTurnKernel kernel = new AgentTurnKernel(llm, dispatcher());

        AgentTurnResult result = kernel.run(context(messages));

        assertTrue(result.hasToolCalls());
        assertEquals(1, result.toolOutcomes().size());
        assertEquals(List.of("user", "assistant", "tool"), messages.stream().map(LlmClient.Message::role).toList());
        assertEquals("call-1", messages.get(2).toolCallId());
    }

    private AgentTurnContext context(List<LlmClient.Message> messages) {
        return new AgentTurnContext(
                AgentRunContext.create(AgentMode.REACT, "hello", "workspace"),
                messages,
                List.of(),
                new AgentLoopPolicy("test", true),
                new AgentBudget(1_000, 3, 20),
                LlmClient.StreamListener.NO_OP,
                AgentLoopObserver.NO_OP);
    }

    private ToolDispatcher dispatcher() {
        return new ToolDispatcher(invocation -> ToolExecution.completed(
                ToolOutput.text("tool-result"), invocation.argumentsJson()));
    }

    private static final class FakeLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();

        private FakeLlmClient(ChatResponse response) {
            responses.add(response);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return responses.remove();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return responses.remove();
        }

        @Override
        public String getModelName() { return "fake"; }

        @Override
        public String getProviderName() { return "fake"; }
    }
}
