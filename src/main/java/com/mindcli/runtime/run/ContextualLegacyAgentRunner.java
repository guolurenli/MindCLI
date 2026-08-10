package com.mindcli.runtime.run;

@FunctionalInterface
interface ContextualLegacyAgentRunner {
    String run(AgentRunContext context, RunStore runStore) throws Exception;
}
