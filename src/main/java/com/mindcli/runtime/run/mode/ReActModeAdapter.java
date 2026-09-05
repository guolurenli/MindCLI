package com.mindcli.runtime.run.mode;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import com.mindcli.agent.Agent;

import java.util.Objects;
import java.util.List;
import com.mindcli.platform.llm.LlmClient;

public final class ReActModeAdapter implements ModeAdapter {
    private final Agent agent;
    private final LegacyAgentRunner runner;

    public ReActModeAdapter(Agent agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.runner = agent::run;
    }

    ReActModeAdapter(LegacyAgentRunner runner) {
        this.agent = null;
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public AgentMode mode() {
        return AgentMode.REACT;
    }

    @Override
    public AgentRunResult execute(AgentRunContext context) {
        return execute(context, new InMemoryRunStore());
    }

    @Override
    public AgentRunResult execute(AgentRunContext context, RunStore runStore) {
        if (agent != null) {
            return agent.run(context, runStore);
        }
        try {
            return AgentRunResult.success(context, runner.run(context.input()));
        } catch (Exception e) {
            return AgentRunResult.failed(context, errorMessage(e));
        }
    }

    public AgentRunResult executeRecovered(AgentRunContext context, RunStore runStore,
                                           List<LlmClient.Message> recoveredMessages) {
        if (agent == null) {
            return execute(context, runStore);
        }
        return agent.runRecovered(context, runStore, recoveredMessages);
    }

    public String latestAssistantResponse() {
        return agent == null ? "" : agent.latestAssistantResponse();
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
