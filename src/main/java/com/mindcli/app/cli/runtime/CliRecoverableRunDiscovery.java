package com.mindcli.app.cli.runtime;

import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.recovery.RunRecoveryPlan;
import com.mindcli.runtime.run.recovery.RunRecoveryService;
import com.mindcli.runtime.run.store.JsonlRunStore;
import com.mindcli.runtime.run.store.RunStateStatus;
import com.mindcli.runtime.run.store.RunStore;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Discovers persisted recovery candidates for a startup hint without resuming them. */
public final class CliRecoverableRunDiscovery {
    private static final int STARTUP_LIMIT = 3;

    private CliRecoverableRunDiscovery() {
    }

    static List<RunRecoveryPlan> discover(RunStore runStore, int limit) {
        if (!(runStore instanceof JsonlRunStore jsonlRunStore) || limit <= 0) {
            return List.of();
        }
        RunRecoveryService recoveryService = new RunRecoveryService(runStore);
        return jsonlRunStore.topLevelRunIds().stream()
                .map(runId -> inspectSafely(recoveryService, runId))
                .filter(CliRecoverableRunDiscovery::isRecoverableCandidate)
                .sorted(Comparator.comparing(CliRecoverableRunDiscovery::latestTimestamp)
                        .reversed()
                        .thenComparing(RunRecoveryPlan::runId))
                .limit(limit)
                .toList();
    }

    public static String startupNotice(RunStore runStore) {
        List<RunRecoveryPlan> plans = discover(runStore, STARTUP_LIMIT);
        if (plans.isEmpty()) {
            return "";
        }
        String candidates = plans.stream()
                .map(plan -> plan.runId() + " (" + plan.mode().name() + ")")
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
        return "发现 " + plans.size() + " 个可恢复任务候选：" + candidates
                + "。先用 /run inspect <runId> 检查，再用 /run resume <runId> 恢复。";
    }

    private static RunRecoveryPlan inspectSafely(RunRecoveryService recoveryService, String runId) {
        try {
            return recoveryService.inspect(runId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isRecoverableCandidate(RunRecoveryPlan plan) {
        return plan != null
                && plan.stateStatus() == RunStateStatus.RESUMABLE
                && plan.resumeAvailable()
                && plan.mode() != null
                && !plan.workspace().isBlank()
                && !plan.originalInput().isBlank();
    }

    private static Instant latestTimestamp(RunRecoveryPlan plan) {
        return plan.events().stream()
                .map(AgentRunEvent::timestamp)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
    }
}
