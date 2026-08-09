package com.mindcli.runtime.agent;

import java.util.List;
import java.util.Objects;

public final class RunRecoveryService {
    private final RunStore runStore;
    private final RunStateProjector projector;

    public RunRecoveryService(RunStore runStore) {
        this(runStore, new RunStateProjector());
    }

    public RunRecoveryService(RunStore runStore, RunStateProjector projector) {
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    public RunRecoveryPlan inspect(String runId) {
        List<AgentRunEvent> events = runStore.events(runId);
        RunStateProjection projection = projector.project(events);
        return new RunRecoveryPlan(
                runId,
                projection.status(),
                projection.status() == RunStateStatus.RESUMABLE,
                projection.status() == RunStateStatus.TERMINAL,
                projection.status() == RunStateStatus.MANUAL,
                projection.lastEventType(),
                projection.lastCompletedEventType(),
                projection.lastCompletedAttributes(),
                projection.events());
    }
}
