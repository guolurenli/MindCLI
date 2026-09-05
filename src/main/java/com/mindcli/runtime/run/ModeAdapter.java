package com.mindcli.runtime.run;
import com.mindcli.runtime.run.store.RunStore;

public interface ModeAdapter {
    AgentMode mode();

    AgentRunResult execute(AgentRunContext context);

    default AgentRunResult execute(AgentRunContext context, RunStore runStore) {
        return execute(context);
    }
}
