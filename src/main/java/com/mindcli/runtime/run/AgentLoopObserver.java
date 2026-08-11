package com.mindcli.runtime.run;

import com.mindcli.platform.llm.LlmClient;

import java.util.List;

public interface AgentLoopObserver {
    AgentLoopObserver NO_OP = new AgentLoopObserver() {
    };

    default void beforeIteration(int iteration, List<LlmClient.Message> messages, List<LlmClient.Tool> tools) {
    }

    default void afterLlmResponse(int iteration, LlmClient.ChatResponse response) {
    }

    default void beforeToolDispatch(int iteration, List<LlmClient.ToolCall> toolCalls) {
    }

    default void afterToolDispatch(int iteration, List<ToolOutcome> outcomes) {
    }
}
