package com.mindcli.runtime.run.session;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.store.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 轻量的进程内会话上下文。
 *
 * 只保存跨 run 需要继续理解的运行摘要；单个 run 的详细消息仍由对应执行器和 RunStore 管理。
 */
public final class SessionContext {
    private static final int DEFAULT_MAX_RECENT_RUNS = 3;
    private static final int DEFAULT_COMPACTED_HISTORY_TOKENS = 2_000;

    private final int maxRecentRuns;
    private final int compactedHistoryTokens;
    private final Deque<RunSummary> recentSummaries = new ArrayDeque<>();
    private String compactedSummary = "";

    public SessionContext() {
        this(DEFAULT_MAX_RECENT_RUNS, DEFAULT_COMPACTED_HISTORY_TOKENS);
    }

    public SessionContext(int maxRecentRuns, int compactedHistoryTokens) {
        if (maxRecentRuns < 1) {
            throw new IllegalArgumentException("maxRecentRuns must be positive");
        }
        if (compactedHistoryTokens < 1) {
            throw new IllegalArgumentException("compactedHistoryTokens must be positive");
        }
        this.maxRecentRuns = maxRecentRuns;
        this.compactedHistoryTokens = compactedHistoryTokens;
    }

    public synchronized void record(AgentRunResult result) {
        record(result, null);
    }

    public synchronized void record(AgentRunResult result, String contentOverride) {
        if (result == null) {
            return;
        }
        RunSummary summary = RunSummary.from(result, contentOverride);
        while (recentSummaries.size() >= maxRecentRuns) {
            compactOne(recentSummaries.removeFirst());
        }
        recentSummaries.addLast(summary);
        compactedSummary = bound(compactedSummary, compactedHistoryTokens);
    }

    public synchronized void clear() {
        recentSummaries.clear();
        compactedSummary = "";
    }

    /**
     * 返回供下一次 LLM 请求使用的会话摘要，不修改会话状态。
     */
    public synchronized String promptContext(int maxTokens) {
        if (maxTokens < 1 || (compactedSummary.isBlank() && recentSummaries.isEmpty())) {
            return "";
        }

        int maxChars = Math.max(64, maxTokens * 4);
        String header = "此前同一会话中的运行结果:\n\n";
        List<String> selected = new ArrayList<>();
        int remaining = Math.max(1, maxChars - header.length());

        // 预算不足时从最新 run 倒序选择，保证当前任务最相关的结果不会被旧摘要截断。
        List<RunSummary> recent = new ArrayList<>(recentSummaries);
        for (int i = recent.size() - 1; i >= 0 && remaining > 0; i--) {
            String section = recent.get(i).format();
            int required = section.length() + (selected.isEmpty() ? 0 : 2);
            if (required <= remaining) {
                selected.add(0, section);
                remaining -= required;
            } else if (selected.isEmpty()) {
                selected.add(truncateChars(section, remaining));
                remaining = 0;
            }
        }

        if (!compactedSummary.isBlank() && remaining > 0) {
            String section = "早期运行摘要:\n" + compactedSummary;
            int required = section.length() + (selected.isEmpty() ? 0 : 2);
            if (required <= remaining) {
                selected.add(0, section);
            } else if (selected.isEmpty()) {
                selected.add(truncateChars(section, remaining));
            }
        }

        return header + String.join("\n\n", selected);
    }

    public synchronized List<RunSummary> recentSummaries() {
        return List.copyOf(recentSummaries);
    }

    public synchronized String compactedSummary() {
        return compactedSummary;
    }

    private void compactOne(RunSummary summary) {
        String item = summary.format();
        compactedSummary = compactedSummary.isBlank()
                ? item
                : compactedSummary + "\n" + item;
    }

    private static String bound(String value, int maxTokens) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int maxChars = Math.max(64, maxTokens * 4);
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(1, maxChars - 20)).trim() + "\n...[摘要已截断]";
    }

    private static String truncateChars(String value, int maxChars) {
        if (value == null || value.isBlank() || maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(1, maxChars - 8)).trim() + "...";
    }

    public record RunSummary(
            String runId,
            AgentMode mode,
            AgentRunStatus status,
            String content,
            Map<String, String> metadata
    ) {
        public RunSummary {
            runId = runId == null ? "" : runId;
            mode = mode == null ? AgentMode.REACT : mode;
            status = status == null ? AgentRunStatus.FAILED : status;
            content = content == null ? "" : content.trim();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        static RunSummary from(AgentRunResult result, String contentOverride) {
            String text = result.isSuccess()
                    ? (contentOverride == null || contentOverride.isBlank() ? result.content() : contentOverride)
                    : result.errorMessage();
            return new RunSummary(result.runId(), result.mode(), result.status(), bound(text, 600), result.metadata());
        }

        String format() {
            String detail = content.isBlank() ? "(无文本结果)" : content;
            return "[" + mode + "] " + status + " " + runId + "\n" + detail;
        }
    }
}
