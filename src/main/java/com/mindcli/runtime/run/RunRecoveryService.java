package com.mindcli.runtime.run;

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
        String preRunSnapshot = latestSnapshotCommit(events, "PRE_RUN");
        String postRunSnapshot = latestSnapshotCommit(events, "POST_RUN");
        return new RunRecoveryPlan(
                runId,
                projection.status(),
                projection.status() == RunStateStatus.RESUMABLE,
                projection.status() == RunStateStatus.TERMINAL,
                projection.status() == RunStateStatus.MANUAL,
                projection.lastEventType(),
                projection.lastCompletedEventType(),
                projection.lastCompletedAttributes(),
                projection.events(),
                preRunSnapshot,
                postRunSnapshot,
                restoreHint(projection.status(), preRunSnapshot, postRunSnapshot));
    }

    private static String latestSnapshotCommit(List<AgentRunEvent> events, String phase) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentRunEvent event = events.get(i);
            if (event.type() == AgentRunEventType.SNAPSHOT_CREATED
                    && phase.equals(event.attributes().get("snapshotPhase"))) {
                return event.attributes().getOrDefault("snapshotCommitId", "");
            }
        }
        return "";
    }

    private static String restoreHint(RunStateStatus status, String preRunSnapshot, String postRunSnapshot) {
        if (status == RunStateStatus.TERMINAL) {
            return postRunSnapshot == null || postRunSnapshot.isBlank()
                    ? "Run 已结束；未找到 post-run snapshot。"
                    : "Run 已结束；post-run snapshot: " + postRunSnapshot;
        }
        if (preRunSnapshot == null || preRunSnapshot.isBlank()) {
            return "未找到 pre-run snapshot；只能检查 run ledger，无法直接定位工作区回滚点。";
        }
        return switch (status) {
            case RESUMABLE -> "Run 可检查恢复；pre-run snapshot: " + preRunSnapshot;
            case MANUAL -> "Run 需要人工介入；可参考 pre-run snapshot: " + preRunSnapshot;
            case RUNNING -> "Run 仍在运行；pre-run snapshot: " + preRunSnapshot;
            case TERMINAL -> "Run 已结束；post-run snapshot: " + postRunSnapshot;
        };
    }
}
