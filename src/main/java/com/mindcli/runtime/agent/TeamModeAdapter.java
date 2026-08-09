package com.mindcli.runtime.agent;

import com.mindcli.agent.AgentOrchestrator;

import java.util.Objects;

public final class TeamModeAdapter implements ModeAdapter {
    private final LegacyAgentRunner runner;

    public TeamModeAdapter(AgentOrchestrator orchestrator) {
        this(Objects.requireNonNull(orchestrator, "orchestrator")::run);
    }

    TeamModeAdapter(LegacyAgentRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public AgentMode mode() {
        return AgentMode.TEAM;
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
