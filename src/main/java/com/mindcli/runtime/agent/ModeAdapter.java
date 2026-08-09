package com.mindcli.runtime.agent;

public interface ModeAdapter {
    AgentMode mode();

    AgentRunResult execute(AgentRunContext context);

    default AgentRunResult execute(AgentRunContext context, RunStore runStore) {
        return execute(context);
    }
}
