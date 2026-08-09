package com.mindcli.runtime.agent;

import com.mindcli.agent.PlanExecuteAgent;

import java.util.Objects;

public final class PlanModeAdapter implements ModeAdapter {
    private final LegacyAgentRunner runner;

    public PlanModeAdapter(PlanExecuteAgent agent) {
        this(Objects.requireNonNull(agent, "agent")::run);
    }

    PlanModeAdapter(LegacyAgentRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public AgentMode mode() {
        return AgentMode.PLAN;
    }

    @Override
    public AgentRunResult execute(AgentRunContext context) {
        try {
            return AgentRunResult.success(context, runner.run(context.input()));
        } catch (Exception e) {
            return AgentRunResult.failed(context, errorMessage(e));
        }
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
