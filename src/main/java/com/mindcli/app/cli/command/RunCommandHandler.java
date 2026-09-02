package com.mindcli.app.cli.command;

import com.mindcli.runtime.run.recovery.RunRecoveryPlan;
import com.mindcli.runtime.run.recovery.RunRecoveryService;
import com.mindcli.runtime.run.store.RunStore;

import java.io.PrintStream;
import java.util.function.Function;

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
        out.println("   Mode: " + (plan.mode() == null ? "unknown" : plan.mode().name()));
        out.println("   Resume available: " + plan.resumeAvailable());
        out.println("   Status: " + plan.stateStatus());
        out.println("   Last event: " + (plan.lastEventType() == null ? "" : plan.lastEventType().name()));
        out.println("   Last completed: " + (plan.lastCompletedEventType() == null ? "" : plan.lastCompletedEventType().name()));
        out.println("   Pre-run snapshot: " + blankToNone(plan.preRunSnapshotCommitId()));
        out.println("   Post-run snapshot: " + blankToNone(plan.postRunSnapshotCommitId()));
        out.println("   Hint: " + plan.restoreHint());
        out.println();
    }

    public static void printRunResume(PrintStream out, RunStore runStore, String payload,
                                      Function<String, String> resumer) {
        String normalized = payload == null ? "" : payload.trim();
        boolean confirmed = normalized.matches("(?i).*\\s--confirm$");
        String runId = confirmed ? normalized.replaceFirst("(?i)\\s+--confirm$", "").trim() : normalized;
        if (runId.isBlank()) {
            out.println("❌ 用法: /run resume <runId>\n");
            return;
        }
        if (resumer == null) {
            out.println("❌ 当前运行环境不支持恢复执行\n");
            return;
        }
        RunRecoveryPlan plan = new RunRecoveryService(runStore).inspect(runId);
        var resumePlan = plan.resumePlan();
        out.println("🧭 恢复风险: " + resumePlan.risk());
        out.println("   原因: " + resumePlan.reason());
        if (!plan.resumeAvailable()) {
            out.println("❌ 无法恢复: " + (plan.resumable() ? "历史 run 缺少原始输入或工作区信息" : plan.stateStatus()) + "\n");
            return;
        }
        if ("UNKNOWN".equalsIgnoreCase(resumePlan.risk())) {
            out.println("❌ 无法恢复: 恢复点存在未完成或不确定的工具调用，必须先人工检查\n");
            return;
        }
        if (resumePlan.requiresConfirmation() && !confirmed) {
            out.println("⚠️ 此恢复需要确认，可能重复执行副作用操作；如确认，请使用 /run resume " + runId + " --confirm\n");
            return;
        }
        out.println("▶️ 正在恢复 run: " + runId);
        String result;
        try {
            result = resumer.apply(runId);
        } catch (RuntimeException e) {
            result = "❌ 恢复失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (result != null && !result.isBlank()) {
            out.println(result);
        }
        out.println();
    }

    private static String blankToNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
