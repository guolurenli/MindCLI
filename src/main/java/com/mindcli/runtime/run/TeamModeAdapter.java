package com.mindcli.runtime.run;

import com.mindcli.agent.AgentOrchestrator;

import java.util.Objects;

public final class TeamModeAdapter implements ModeAdapter {
    private final ContextualLegacyAgentRunner runner;

    public TeamModeAdapter(AgentOrchestrator orchestrator) {
        this((ContextualLegacyAgentRunner) Objects.requireNonNull(orchestrator, "orchestrator")::run);
    }

    TeamModeAdapter(LegacyAgentRunner runner) {
        Objects.requireNonNull(runner, "runner");
        this.runner = (context, runStore) -> runner.run(context.input());
    }

    TeamModeAdapter(ContextualLegacyAgentRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public AgentMode mode() {
        return AgentMode.TEAM;
    }

    @Override
    public AgentRunResult execute(AgentRunContext context) {
        return execute(context, null);
    }

    @Override
    public AgentRunResult execute(AgentRunContext context, RunStore runStore) {
        try {
            return resultFromContent(context, runner.run(context, runStore));
        } catch (Exception e) {
            return AgentRunResult.failed(context, errorMessage(e));
        }
    }

    private static AgentRunResult resultFromContent(AgentRunContext context, String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.startsWith("⏹")) {
            return AgentRunResult.cancelled(context, content);
        }
        if (normalized.startsWith("❌")) {
            return AgentRunResult.failed(context, normalized);
        }
        if (normalized.startsWith("⚠️") || normalized.startsWith("⚠")) {
            return AgentRunResult.blocked(context, normalized);
        }
        return AgentRunResult.success(context, content);
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
