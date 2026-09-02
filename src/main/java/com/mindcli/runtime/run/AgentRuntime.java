package com.mindcli.runtime.run;
import com.mindcli.runtime.run.store.RunStore;

import com.mindcli.platform.snapshot.SnapshotPhase;
import com.mindcli.platform.snapshot.SnapshotService;
import com.mindcli.platform.snapshot.TurnSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.mindcli.runtime.run.recovery.RunRecoveryPlan;
import com.mindcli.runtime.run.recovery.RunRecoveryService;
import com.mindcli.runtime.run.recovery.ReActResumeState;
import com.mindcli.runtime.run.mode.ReActModeAdapter;

public final class AgentRuntime {
    private final RunStore runStore;
    private final SnapshotService snapshotService;
    private static final ConcurrentMap<String, Object> RESUME_LOCKS = new ConcurrentHashMap<>();

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
        append(context, AgentRunEventType.RUN_STARTED, Map.of("input", context.input()));
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

    /**
     * Re-enters an interrupted run using its persisted mode, workspace and input.
     * Completed tool calls are not replayed; the adapter starts a fresh attempt and
     * all normal policy/HITL checks remain in force.
     */
    public AgentRunResult resume(String runId, ModeAdapter adapter) {
        Object lock = RESUME_LOCKS.computeIfAbsent(runId == null ? "" : runId, ignored -> new Object());
        synchronized (lock) {
            return resumeLocked(runId, adapter);
        }
    }

    private AgentRunResult resumeLocked(String runId, ModeAdapter adapter) {
        RunRecoveryPlan plan = new RunRecoveryService(runStore).inspect(runId);
        AgentRunContext context = AgentRunContext.create(
                plan.mode() == null ? AgentMode.REACT : plan.mode(),
                plan.originalInput(),
                plan.workspace());
        context = new AgentRunContext(runId, context.mode(), context.input(), context.workspace(),
                context.startedAt(), Map.of("resumed", "true"));
        if (!plan.resumeAvailable()) {
            return AgentRunResult.failed(context,
                    plan.resumable()
                            ? "Run 缺少可恢复的原始输入或上下文"
                            : "Run 当前不可恢复: " + plan.stateStatus());
        }
        if (adapter == null || adapter.mode() != plan.mode()) {
            return AgentRunResult.failed(context, "没有匹配的 mode adapter: " + plan.mode());
        }
        ReActResumeState recoveredState = null;
        if (adapter instanceof ReActModeAdapter) {
            recoveredState = new RunRecoveryService(runStore).reconstructReActState(runId);
            if (!recoveredState.available()) {
                return AgentRunResult.failed(context, "ReAct 恢复上下文不可用: " + recoveredState.reason());
            }
        }
        append(context, AgentRunEventType.RUN_RESUMED, Map.of(
                "resumedFrom", plan.lastEventType() == null ? "" : plan.lastEventType().name()));
        append(context, AgentRunEventType.MODE_SELECTED, Map.of(
                "mode", context.mode().name(), "adapterMode", adapter.mode().name()));
        try {
            AgentRunResult result;
            if (adapter instanceof ReActModeAdapter reactAdapter) {
                result = reactAdapter.executeRecovered(context, runStore, recoveredState.messages());
            } else {
                result = adapter.execute(context, runStore);
            }
            if (result == null) {
                result = AgentRunResult.failed(context, "Mode adapter returned null result");
            }
            append(context, terminalEvent(result.status()), Map.of("status", result.status().name(), "resumed", "true"));
            snapshotAfterRunAsync(context, result.status());
            return result;
        } catch (Exception e) {
            AgentRunResult result = AgentRunResult.failed(context, errorMessage(e));
            append(context, AgentRunEventType.RUN_FAILED, Map.of("status", result.status().name(), "resumed", "true"));
            snapshotAfterRunAsync(context, result.status());
            return result;
        }
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
