package com.mindcli.runtime.agent;

import java.util.Map;
import java.util.Objects;

public final class AgentRuntime {
    private final RunStore runStore;

    public AgentRuntime(RunStore runStore) {
        this.runStore = Objects.requireNonNull(runStore, "runStore");
    }

    public AgentRunResult run(AgentRunContext context, ModeAdapter adapter) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(adapter, "adapter");

        append(context, AgentRunEventType.RUN_STARTED);
        append(context, AgentRunEventType.MODE_SELECTED, Map.of(
                "mode", context.mode().name(),
                "adapterMode", adapter.mode().name()));

        try {
            AgentRunResult result = adapter.execute(context, runStore);
            if (result == null) {
                result = AgentRunResult.failed(context, "Mode adapter returned null result");
            }
            append(context, terminalEvent(result.status()),
                    Map.of("status", result.status().name()));
            return result;
        } catch (Exception e) {
            AgentRunResult result = AgentRunResult.failed(context, errorMessage(e));
            append(context, AgentRunEventType.RUN_FAILED, Map.of("status", result.status().name()));
            return result;
        }
    }

    public RunStore runStore() {
        return runStore;
    }

    private void append(AgentRunContext context, AgentRunEventType type) {
        append(context, type, Map.of());
    }

    private void append(AgentRunContext context, AgentRunEventType type, Map<String, String> attributes) {
        runStore.append(AgentRunEvent.of(context, type, attributes));
    }

    private static AgentRunEventType terminalEvent(AgentRunStatus status) {
        return switch (status) {
            case SUCCESS -> AgentRunEventType.RUN_FINISHED;
            case CANCELLED -> AgentRunEventType.RUN_CANCELLED;
            case BUDGET_EXHAUSTED -> AgentRunEventType.BUDGET_EXHAUSTED;
            case FAILED, BLOCKED -> AgentRunEventType.RUN_FAILED;
        };
    }
    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
