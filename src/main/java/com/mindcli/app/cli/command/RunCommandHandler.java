package com.mindcli.app.cli.command;

import com.mindcli.runtime.run.RunRecoveryPlan;
import com.mindcli.runtime.run.RunRecoveryService;
import com.mindcli.runtime.run.RunStore;

import java.io.PrintStream;

public final class RunCommandHandler {
    private RunCommandHandler() {
    }

    public static void printRunInspect(PrintStream out, RunStore runStore, String payload) {
        String normalized = payload == null ? "" : payload.trim();
        if (!normalized.regionMatches(true, 0, "inspect ", 0, 8)) {
            out.println("""
                    ❌ 用法: /run inspect <runId>
                    """.trim());
            out.println();
            return;
        }
        String runId = normalized.substring(8).trim();
        if (runId.isBlank()) {
            out.println("❌ 用法: /run inspect <runId>\n");
            return;
        }
        RunRecoveryPlan plan = new RunRecoveryService(runStore).inspect(runId);
        out.println("🧾 Run Inspect");
        out.println("   Run: " + plan.runId());
        out.println("   Status: " + plan.stateStatus());
        out.println("   Last event: " + (plan.lastEventType() == null ? "" : plan.lastEventType().name()));
        out.println("   Last completed: " + (plan.lastCompletedEventType() == null ? "" : plan.lastCompletedEventType().name()));
        out.println("   Pre-run snapshot: " + blankToNone(plan.preRunSnapshotCommitId()));
        out.println("   Post-run snapshot: " + blankToNone(plan.postRunSnapshotCommitId()));
        out.println("   Hint: " + plan.restoreHint());
        out.println();
    }

    private static String blankToNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
