package com.mindcli.runtime.run.recovery;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import com.mindcli.platform.hitl.ApprovalPolicy;
import com.mindcli.runtime.run.dispatch.ToolOutcomeStatus;
import com.mindcli.platform.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;

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
        AgentMode mode = firstMode(events);
        String workspace = firstAttribute(events, "workspace");
        String originalInput = firstAttribute(events, "input");
        boolean resumeAvailable = projection.status() == RunStateStatus.RESUMABLE && !originalInput.isBlank()
                && mode != null && !workspace.isBlank();
        RunResumePlan resumePlan = classify(events, resumeAvailable);
        if (resumeAvailable && mode == AgentMode.REACT && hasEvent(events, AgentRunEventType.LLM_RESPONSE)) {
            ReActResumeState reactState = reconstructReActState(runId);
            if (!reactState.available()) {
                resumePlan = new RunResumePlan(false, true, "UNKNOWN", reactState.reason(), resumePlan.toolNames());
            }
        }
        String preRunSnapshot = latestSnapshotCommit(events, "PRE_RUN");
        String postRunSnapshot = latestSnapshotCommit(events, "POST_RUN");
        return new RunRecoveryPlan(
                runId,
                mode,
                workspace,
                originalInput,
                projection.status(),
                projection.status() == RunStateStatus.RESUMABLE,
                resumeAvailable,
                resumePlan,
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

    public ReActResumeState reconstructReActState(String runId) {
        List<AgentRunEvent> events = runStore.events(runId);
        String input = firstAttribute(events, "input");
        if (input.isBlank()) {
            return new ReActResumeState(false, List.of(), "缺少原始输入");
        }
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.user(input));
        Map<String, LlmClient.ToolCall> pendingCalls = new LinkedHashMap<>();
        Set<String> completedCallIds = new LinkedHashSet<>();
        for (AgentRunEvent event : events) {
            if (event == null) continue;
            if (event.type() == AgentRunEventType.LLM_RESPONSE) {
                if (!pendingCalls.isEmpty()) {
                    return unavailable("上一个 assistant 工具调用尚未完成");
                }
                String content = event.attributes().getOrDefault("content", "");
                String reasoning = event.attributes().getOrDefault("reasoningContent", "");
                String countValue = event.attributes().get("toolCallCount");
                String callsJson = event.attributes().get("toolCallsJson");
                if (countValue == null || callsJson == null) {
                    return unavailable("LLM 工具调用记录不完整");
                }
                Integer expectedCalls = parseNonNegativeInt(countValue);
                List<LlmClient.ToolCall> calls = parseToolCalls(callsJson);
                if (expectedCalls == null || calls == null || calls.size() != expectedCalls) {
                    return unavailable("LLM 工具调用记录不完整");
                }
                for (LlmClient.ToolCall call : calls) {
                    if (call == null || call.id() == null || call.id().isBlank()
                            || call.function() == null || call.function().name() == null
                            || call.function().name().isBlank() || pendingCalls.put(call.id(), call) != null
                            || completedCallIds.contains(call.id())) {
                        return unavailable("LLM 工具调用记录不完整");
                    }
                }
                messages.add(calls.isEmpty()
                        ? LlmClient.Message.assistant(reasoning, content)
                        : LlmClient.Message.assistant(reasoning, content, calls));
            } else if (event.type() == AgentRunEventType.TOOL_OUTCOME) {
                String id = event.attributes().getOrDefault("toolId", "");
                String text = event.attributes().getOrDefault("text", "");
                if (id.isBlank() || !pendingCalls.containsKey(id)
                        || !ToolOutcomeStatus.COMPLETED.name().equalsIgnoreCase(
                        event.attributes().getOrDefault("status", ""))) {
                    return unavailable("工具调用结果不完整或顺序非法");
                }
                LlmClient.ToolCall call = pendingCalls.remove(id);
                String recordedName = event.attributes().getOrDefault("toolName", "");
                String expectedName = call.function() == null ? "" : call.function().name();
                if (!recordedName.isBlank() && !recordedName.equals(expectedName)) {
                    return unavailable("工具调用结果与请求不匹配");
                }
                String recordedArgs = event.attributes().getOrDefault("argumentsJson", "");
                String expectedArgs = call.function() == null ? "" : call.function().arguments();
                if (!recordedArgs.isBlank() && !expectedArgs.equals(recordedArgs)) {
                    return unavailable("工具调用参数与请求不匹配");
                }
                completedCallIds.add(id);
                messages.add(LlmClient.Message.tool(id, text));
            } else if (event.type() == AgentRunEventType.TOOL_CALL_REQUESTED) {
                Integer requestedCount = parseNonNegativeInt(event.attributes().get("toolCallCount"));
                if (requestedCount != null && requestedCount != pendingCalls.size()) {
                    return unavailable("工具调用请求数量与 LLM 响应不匹配");
                }
            }
        }
        if (!pendingCalls.isEmpty()) {
            return unavailable("存在未完成的 assistant 工具调用");
        }
        return new ReActResumeState(true, messages, "");
    }

    private static ReActResumeState unavailable(String reason) {
        return new ReActResumeState(false, List.of(), reason);
    }

    private static Integer parseNonNegativeInt(String value) {
        try {
            if (value == null || value.isBlank()) return null;
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<LlmClient.ToolCall> parseToolCalls(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = com.mindcli.platform.serialization.JsonSupport.mapper().readTree(json);
            if (!node.isArray()) return null;
            List<LlmClient.ToolCall> calls = new ArrayList<>();
            for (JsonNode item : node) {
                String id = item.path("id").asText("");
                JsonNode fn = item.path("function");
                calls.add(new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(
                        fn.path("name").asText(""), fn.path("arguments").asText(""))));
            }
            return List.copyOf(calls);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static RunResumePlan classify(List<AgentRunEvent> events, boolean contextAvailable) {
        Set<String> requested = new LinkedHashSet<>();
        Set<String> completed = new LinkedHashSet<>();
        boolean incomplete = false;
        boolean sideEffect = false;
        int requestedCount = 0;
        int outcomeCount = 0;
        if (events != null) {
            for (AgentRunEvent event : events) {
                if (event == null) continue;
                if (event.type() == AgentRunEventType.TOOL_CALL_REQUESTED) {
                    try {
                        requestedCount += Integer.parseInt(event.attributes().getOrDefault("toolCallCount", "0"));
                    } catch (NumberFormatException ignored) {
                        incomplete = true;
                    }
                    String names = event.attributes().getOrDefault("toolNames", "");
                    for (String name : names.split(",")) if (!name.isBlank()) requested.add(name.trim());
                } else if (event.type() == AgentRunEventType.TOOL_OUTCOME) {
                    outcomeCount++;
                    String name = event.attributes().getOrDefault("toolName", "");
                    if (!name.isBlank()) {
                        String status = event.attributes().getOrDefault("status", "");
                        if (ToolOutcomeStatus.COMPLETED.name().equalsIgnoreCase(status)) completed.add(name);
                        else incomplete = true;
                        sideEffect |= ApprovalPolicy.requiresApproval(name);
                    }
                }
            }
        }
        if (requestedCount > outcomeCount) incomplete = true;
        for (String name : requested) {
            sideEffect |= ApprovalPolicy.requiresApproval(name);
            if (!completed.contains(name)) incomplete = true;
        }
        List<String> tools = new ArrayList<>(requested);
        tools.addAll(completed.stream().filter(name -> !requested.contains(name)).toList());
        if (!contextAvailable) return new RunResumePlan(false, true, "UNKNOWN", "缺少原始执行上下文", tools);
        if (incomplete) return new RunResumePlan(false, true, "UNKNOWN", "存在未完成或非成功的工具调用", tools);
        if (sideEffect) return new RunResumePlan(false, true, "HIGH", "包含可能产生副作用的工具调用", tools);
        return new RunResumePlan(true, false, "LOW", "仅包含可安全重试的只读或无工具步骤", tools);
    }

    private static AgentMode firstMode(List<AgentRunEvent> events) {
        String value = firstAttribute(events, "mode");
        if (value.isBlank()) {
            return null;
        }
        try {
            return AgentMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String firstAttribute(List<AgentRunEvent> events, String key) {
        if (events == null) {
            return "";
        }
        for (AgentRunEvent event : events) {
            if (event != null) {
                String value = event.attributes().get(key);
                if (value != null) {
                    return value;
                }
            }
        }
        return "";
    }

    private static boolean hasEvent(List<AgentRunEvent> events, AgentRunEventType type) {
        if (events == null || type == null) {
            return false;
        }
        return events.stream().anyMatch(event -> event != null && event.type() == type);
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
