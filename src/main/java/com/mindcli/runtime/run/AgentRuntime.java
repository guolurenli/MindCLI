package com.mindcli.runtime.run;

import com.mindcli.platform.snapshot.SnapshotPhase;
import com.mindcli.platform.snapshot.SnapshotService;
import com.mindcli.platform.snapshot.TurnSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AgentRuntime {
    private final RunStore runStore;
    private final SnapshotService snapshotService;

    public AgentRuntime(RunStore runStore) {
        this(runStore, null);
    }

    public AgentRuntime(RunStore runStore, SnapshotService snapshotService) {
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.snapshotService = snapshotService;
    }

    public AgentRunResult run(AgentRunContext context, ModeAdapter adapter) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(adapter, "adapter");

        appendSnapshotCreated(context, SnapshotPhase.PRE_RUN, snapshotBeforeRun(context), null);
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
            snapshotAfterRunAsync(context, result.status());
            return result;
        } catch (Exception e) {
            AgentRunResult result = AgentRunResult.failed(context, errorMessage(e));
            append(context, AgentRunEventType.RUN_FAILED, Map.of("status", result.status().name()));
            snapshotAfterRunAsync(context, result.status());
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

    private TurnSnapshot snapshotBeforeRun(AgentRunContext context) {
        if (snapshotService == null) {
            return null;
        }
        return snapshotService.snapshotBeforeRun(context);
    }

    private void snapshotAfterRunAsync(AgentRunContext context, AgentRunStatus status) {
        if (snapshotService == null) {
            return;
        }
        snapshotService.snapshotAfterRunAsync(context, status,
                snapshot -> appendSnapshotCreated(context, SnapshotPhase.POST_RUN, snapshot, status));
    }

    private void appendSnapshotCreated(AgentRunContext context, SnapshotPhase phase,
                                       TurnSnapshot snapshot, AgentRunStatus status) {
        if (snapshot == null) {
            return;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("snapshotPhase", phase.name());
        attributes.put("snapshotCommitId", snapshot.commitId());
        attributes.put("snapshotShortCommitId", snapshot.shortCommitId());
        attributes.put("snapshotTurnId", snapshot.turnId());
        attributes.put("recoverability", recoverability(status));
        if (status != null) {
            attributes.put("status", status.name());
        }
        append(context, AgentRunEventType.SNAPSHOT_CREATED, attributes);
    }

    private static String recoverability(AgentRunStatus status) {
        if (status == null) {
            return "NONE";
        }
        return switch (status) {
            case SUCCESS -> "NONE";
            case CANCELLED, BUDGET_EXHAUSTED -> "AUTO";
            case BLOCKED, FAILED -> "MANUAL";
        };
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
