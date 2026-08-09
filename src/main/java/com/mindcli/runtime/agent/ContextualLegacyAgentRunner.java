package com.mindcli.runtime.agent;

@FunctionalInterface
interface ContextualLegacyAgentRunner {
    String run(AgentRunContext context, RunStore runStore) throws Exception;
}
