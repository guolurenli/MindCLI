package com.mindcli.runtime.run;

import com.mindcli.agent.AgentBudget;
import com.mindcli.platform.llm.LlmClient;

import java.util.List;
import java.util.Objects;

public record AgentLoopContext(
        AgentRunContext runContext,
        List<LlmClient.Message> messages,
        List<LlmClient.Tool> tools,
        AgentLoopPolicy policy,
        AgentBudget budget,
        LlmClient.StreamListener streamListener,
        AgentLoopObserver observer
) {
    public AgentLoopContext {
        runContext = Objects.requireNonNull(runContext, "runContext");
        messages = Objects.requireNonNull(messages, "messages");
        tools = tools == null ? List.of() : tools;
        policy = policy == null ? new AgentLoopPolicy("agent-loop", true) : policy;
        budget = Objects.requireNonNull(budget, "budget");
        streamListener = streamListener == null ? LlmClient.StreamListener.NO_OP : streamListener;
        observer = observer == null ? AgentLoopObserver.NO_OP : observer;
    }

    public List<LlmClient.Tool> effectiveTools() {
        return policy.toolsEnabled() ? tools : null;
    }
}
