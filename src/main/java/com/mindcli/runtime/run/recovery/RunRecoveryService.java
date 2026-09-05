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
    private static final Set<String> PLAN_READ_ONLY_TOOLS = Set.of(
            "read_file", "list_dir", "glob_files", "grep_code",
            "web_search", "web_fetch", "search_memory", "read_memory");
    private static final Set<String> PLAN_TASK_STATUSES = Set.of(
            "PENDING", "RUNNING", "COMPLETED", "FAILED", "SKIPPED");
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
        if (resumeAvailable && mode == AgentMode.PLAN) {
            PlanResumeState planState = reconstructPlanState(runId);
            if (!planState.available()) {
                resumeAvailable = false;
                resumePlan = new RunResumePlan(
                        false, true, "UNKNOWN", planState.reason(), resumePlan.toolNames());
            } else {
                resumePlan = classifyPlan(events);
            }
        }
        if (resumeAvailable && mode == AgentMode.TEAM) {
            TeamRecoveryProjection team = projectTeam(runId);
            if (!team.state().available()) {
                resumeAvailable = false;
                resumePlan = new RunResumePlan(false, true, "UNKNOWN", team.reason(), team.toolNames());
            } else if (team.completedSideEffect()) {
                resumePlan = new RunResumePlan(false, true, "HIGH",
                        "已完成 Team step 包含可能产生副作用的工具调用", team.toolNames());
            } else {
                resumePlan = new RunResumePlan(true, false, "LOW",
                        "Team checkpoint 完整，仅继续安全的未完成步骤", team.toolNames());
            }
        }
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

    public PlanResumeState reconstructPlanState(String runId) {
        List<AgentRunEvent> events = runStore.events(runId);
        int definitionIndex = lastIndexOf(events, AgentRunEventType.PLAN_DEFINED);
        if (definitionIndex < 0) {
            return PlanResumeState.unavailable("旧 Plan run 缺少精确恢复 checkpoint");
        }

        AgentRunEvent definition = events.get(definitionIndex);
        Integer planVersion = parsePositiveInt(definition.attributes().get("planVersion"));
        PlanResumeState decoded = new PlanCheckpointCodec().decode(definition.attributes().get("planJson"));
        if (planVersion == null) {
            return PlanResumeState.unavailable("Plan checkpoint 版本非法");
        }
        if (!decoded.available()) {
            return decoded;
        }
        if (decoded.planVersion() != planVersion) {
            return PlanResumeState.unavailable("Plan checkpoint 版本不一致");
        }

        Map<String, PlanTaskResumeState> tasks = new LinkedHashMap<>();
        for (PlanTaskResumeState task : decoded.tasks()) {
            tasks.put(task.id(), task);
        }
        Map<String, List<String>> completedSideEffects = new LinkedHashMap<>();
        for (int i = 0; i < definitionIndex; i++) {
            AgentRunEvent event = events.get(i);
            if (event == null || event.type() != AgentRunEventType.TOOL_OUTCOME
                    || !ToolOutcomeStatus.COMPLETED.name().equalsIgnoreCase(
                    event.attributes().getOrDefault("status", ""))) {
                continue;
            }
            String taskId = event.attributes().getOrDefault("taskId", "").trim();
            String toolName = event.attributes().getOrDefault("toolName", "").trim();
            if (tasks.containsKey(taskId) && isPlanSideEffect(toolName)) {
                completedSideEffects.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add(toolName);
            }
        }
        for (int i = definitionIndex + 1; i < events.size(); i++) {
            AgentRunEvent event = events.get(i);
            if (event == null) continue;
            if (event.type() == AgentRunEventType.PLAN_TASK_CHECKPOINT) {
                PlanResumeState error = applyTaskCheckpoint(event, planVersion, tasks);
                if (error != null) return error;
            } else if (event.type() == AgentRunEventType.TOOL_OUTCOME
                    && ToolOutcomeStatus.COMPLETED.name().equalsIgnoreCase(
                    event.attributes().getOrDefault("status", ""))) {
                String toolName = event.attributes().getOrDefault("toolName", "").trim();
                if (isPlanSideEffect(toolName)) {
                    String taskId = event.attributes().getOrDefault("taskId", "").trim();
                    if (taskId.isEmpty() || !tasks.containsKey(taskId)) {
                        return PlanResumeState.unavailable("Plan checkpoint 后存在无法归属任务的成功副作用");
                    }
                    completedSideEffects.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add(toolName);
                }
            }
        }

        for (Map.Entry<String, List<String>> entry : completedSideEffects.entrySet()) {
            PlanTaskResumeState task = tasks.get(entry.getKey());
            if (!isTerminalPlanTask(task.status())) {
                return PlanResumeState.unavailable(
                        "任务 " + task.id() + " 在终态 checkpoint 前已产生成功副作用: "
                                + String.join(",", entry.getValue()));
            }
        }

        List<PlanTaskResumeState> restored = tasks.values().stream()
                .map(task -> "RUNNING".equals(task.status())
                        ? withCheckpoint(task, "PENDING", task.result(), task.error(), task.retryCount())
                        : task)
                .toList();
        return new PlanResumeState(
                true,
                decoded.planVersion(),
                decoded.planId(),
                decoded.goal(),
                decoded.summary(),
                restored,
                "");
    }

    public TeamResumeState reconstructTeamState(String runId) {
        return projectTeam(runId).state();
    }

    private TeamRecoveryProjection projectTeam(String runId) {
        List<AgentRunEvent> events = runStore.events(runId);
        List<Integer> definitions = indexesOf(events, AgentRunEventType.TEAM_PLAN_DEFINED);
        if (definitions.size() > 1) {
            return TeamRecoveryProjection.unavailable("Team run 包含重复的 TEAM_PLAN_DEFINED");
        }
        int definitionIndex = definitions.isEmpty() ? -1 : definitions.get(0);
        if (definitionIndex < 0) {
            return TeamRecoveryProjection.unavailable("旧 Team run 缺少精确恢复 checkpoint");
        }
        AgentRunEvent definition = events.get(definitionIndex);
        Integer schemaVersion = parsePositiveInt(definition.attributes().get("schemaVersion"));
        Integer planVersion = parsePositiveInt(definition.attributes().get("planVersion"));
        TeamResumeState decoded = new TeamCheckpointCodec().decodePlan(
                definition.attributes().get("planJson"));
        if (schemaVersion == null || planVersion == null
                || schemaVersion != decoded.schemaVersion() || planVersion != decoded.planVersion()) {
            return TeamRecoveryProjection.unavailable("Team checkpoint 版本非法或不一致");
        }
        if (!decoded.available()) {
            return TeamRecoveryProjection.unavailable(decoded.reason());
        }

        Map<String, TeamStepResumeState> steps = new LinkedHashMap<>();
        for (TeamStepResumeState step : decoded.steps()) {
            steps.put(step.id(), step);
        }
        for (int i = definitionIndex + 1; i < events.size(); i++) {
            AgentRunEvent event = events.get(i);
            if (event == null || event.type() != AgentRunEventType.TEAM_STEP_CHECKPOINT) {
                continue;
            }
            TeamResumeState error = applyTeamCheckpoint(event, planVersion, steps);
            if (error != null) {
                return TeamRecoveryProjection.unavailable(error.reason());
            }
        }

        LinkedHashSet<String> toolNames = new LinkedHashSet<>();
        boolean completedSideEffect = false;
        List<TeamStepResumeState> restored = new ArrayList<>();
        for (TeamStepResumeState step : steps.values()) {
            toolNames.addAll(step.requiredTools());
            ChildEvidence evidence = inspectChildren(step, toolNames);
            if (!evidence.available()) {
                return new TeamRecoveryProjection(
                        TeamResumeState.unavailable(evidence.reason()), List.copyOf(toolNames),
                        false, evidence.reason());
            }
            if (isTerminalTeamStep(step.status())) {
                completedSideEffect |= evidence.completedSideEffect()
                        || step.requiredTools().stream().anyMatch(RunRecoveryService::isTeamSideEffectTool);
                restored.add(step);
                continue;
            }
            if ("REVIEWING".equals(step.phase()) || "AWAITING_MERGE".equals(step.phase())) {
                return TeamRecoveryProjection.unavailable(
                        "Team step " + step.id() + " 处于 " + step.phase() + "，无法判断副作用");
            }
            if (!"EXECUTING".equals(step.phase()) && "RUNNING".equals(step.status())) {
                return TeamRecoveryProjection.unavailable(
                        "Team step " + step.id() + " 的运行阶段缺失");
            }
            if (evidence.completedSideEffect()) {
                return TeamRecoveryProjection.unavailable(
                        "Team step " + step.id() + " 在终态 checkpoint 前已产生成功副作用: "
                                + String.join(",", evidence.toolNames()));
            }
            restored.add(withTeamCheckpoint(step, "PENDING", "", step.result(), step.error(), step.attempt(),
                    step.childRunIds()));
        }
        TeamResumeState state = new TeamResumeState(true, decoded.schemaVersion(), decoded.planVersion(), restored, "");
        return new TeamRecoveryProjection(state, List.copyOf(toolNames), completedSideEffect, "");
    }

    private TeamResumeState applyTeamCheckpoint(AgentRunEvent event, int planVersion,
                                                 Map<String, TeamStepResumeState> steps) {
        Map<String, String> attributes = event.attributes();
        Integer schemaVersion = parsePositiveInt(attributes.get("schemaVersion"));
        Integer checkpointVersion = parsePositiveInt(attributes.get("planVersion"));
        if (schemaVersion == null || checkpointVersion == null || checkpointVersion != planVersion) {
            return TeamResumeState.unavailable("Team step checkpoint 版本不一致");
        }
        List<String> ids;
        try {
            ids = new TeamCheckpointCodec().decodeStepIds(attributes.get("stepIdsJson"));
        } catch (IllegalArgumentException e) {
            return TeamResumeState.unavailable("Team step checkpoint 的 stepIdsJson 非法");
        }
        String status = attributes.getOrDefault("stepStatus", "").trim().toUpperCase();
        String phase = attributes.getOrDefault("phase", "").trim().toUpperCase();
        Integer attempt = parseNonNegativeInt(attributes.get("attempt"));
        if (!PLAN_TASK_STATUSES.contains(status) || attempt == null
                || !attributes.containsKey("result") || !attributes.containsKey("error")) {
            return TeamResumeState.unavailable("Team step checkpoint 字段非法");
        }
        if (!Set.of("", "EXECUTING", "REVIEWING", "AWAITING_MERGE").contains(phase)) {
            return TeamResumeState.unavailable("Team step checkpoint phase 非法");
        }
        String childRunId = attributes.getOrDefault("childRunId", "").trim();
        if (!childRunId.isEmpty() && !safeRunId(childRunId)) {
            return TeamResumeState.unavailable("Team checkpoint childRunId 不安全: " + childRunId);
        }
        for (String id : ids) {
            TeamStepResumeState previous = steps.get(id);
            if (previous == null) {
                return TeamResumeState.unavailable("Team step checkpoint 引用了未知步骤: " + id);
            }
            if (!fingerprintMatches(previous, attributes)) {
                return TeamResumeState.unavailable("Team step checkpoint 与步骤定义不匹配: " + id);
            }
        }
        TeamStepResumeState first = steps.get(ids.get(0));
        if (ids.stream().map(steps::get).anyMatch(step -> !sameFingerprint(first, step))) {
            return TeamResumeState.unavailable("Team step checkpoint 包含不同执行指纹");
        }
        if (!validStatusPhase(status, phase)) {
            return TeamResumeState.unavailable("Team step checkpoint 的 status/phase 组合非法");
        }
        for (String id : ids) {
            TeamStepResumeState previous = steps.get(id);
            if (attempt < previous.attempt()) {
                return TeamResumeState.unavailable("Team step checkpoint attempt 倒退: " + id);
            }
            if (isTerminalTeamStep(previous.status())) {
                boolean idempotent = previous.status().equals(status)
                        && previous.phase().isEmpty()
                        && phase.isEmpty()
                        && previous.attempt() == attempt
                        && Objects.equals(previous.result(), attributes.get("result"))
                        && Objects.equals(previous.error(), attributes.get("error"));
                if (!idempotent) {
                    return TeamResumeState.unavailable("Team step checkpoint 在终态后继续推进: " + id);
                }
            }
            if (attempt == previous.attempt() && "RUNNING".equals(status)
                    && !validPhaseTransition(previous.phase(), phase)) {
                return TeamResumeState.unavailable("Team step checkpoint phase 递进非法: " + id);
            }
            List<String> childIds = new ArrayList<>(previous.childRunIds());
            if (!childRunId.isEmpty() && !childIds.contains(childRunId)) {
                childIds.add(childRunId);
            }
            steps.put(id, withTeamCheckpoint(previous, status, phase,
                    attributes.get("result"), attributes.get("error"), attempt, childIds));
        }
        return null;
    }

    private ChildEvidence inspectChildren(TeamStepResumeState step, Set<String> toolNames) {
        List<String> observed = new ArrayList<>();
        boolean sideEffect = false;
        for (String childId : step.childRunIds()) {
            List<AgentRunEvent> events = runStore.events(childId);
            if (events == null || events.isEmpty()) {
                continue;
            }
            ChildEvidence evidence = inspectChild(events, step.id());
            observed.addAll(evidence.toolNames());
            toolNames.addAll(evidence.toolNames());
            sideEffect |= evidence.completedSideEffect();
            if (!evidence.available()) {
                return new ChildEvidence(false, observed, sideEffect, evidence.reason());
            }
        }
        return new ChildEvidence(true, List.copyOf(observed), sideEffect, "");
    }

    private ChildEvidence inspectChild(List<AgentRunEvent> events, String stepId) {
        Map<String, LlmClient.ToolCall> pending = new LinkedHashMap<>();
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        boolean started = false;
        boolean terminal = false;
        boolean sideEffect = false;
        boolean requestSeenForPending = false;
        for (AgentRunEvent event : events) {
            if (event == null) continue;
            if (event.type() == AgentRunEventType.RUN_STARTED) {
                started = true;
                String recordedStep = event.attributes().getOrDefault("stepId", "");
                if (!recordedStep.isBlank() && !recordedStep.equals(stepId)) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 与步骤不匹配: " + stepId);
                }
            } else if (event.type() == AgentRunEventType.LLM_RESPONSE
                    && "turn".equals(event.attributes().getOrDefault("recordKind", ""))) {
                if (!pending.isEmpty()) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 存在未完成的工具调用");
                }
                Integer count = parseNonNegativeInt(event.attributes().get("toolCallCount"));
                List<LlmClient.ToolCall> calls = parseToolCalls(event.attributes().get("toolCallsJson"));
                if (count == null || calls == null || count != calls.size()) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 工具调用记录不完整");
                }
                for (LlmClient.ToolCall call : calls) {
                    if (call == null || call.id() == null || call.id().isBlank()
                            || call.function() == null || call.function().name().isBlank()
                            || pending.put(call.id(), call) != null) {
                        return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                                "Team child 工具调用记录不完整");
                    }
                    tools.add(call.function().name());
                }
                requestSeenForPending = calls.isEmpty();
            } else if (event.type() == AgentRunEventType.TOOL_CALL_REQUESTED) {
                if (pending.isEmpty()) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 缺少对应的 LLM 工具调用");
                }
                Integer count = parseNonNegativeInt(event.attributes().get("toolCallCount"));
                if (count != null && count != pending.size()) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 工具请求数量不匹配");
                }
                String ids = event.attributes().getOrDefault("toolIds", "");
                if (!ids.isBlank() && !parseCsvList(ids).equals(List.copyOf(pending.keySet()))) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 工具请求 ID 不匹配");
                }
                String names = event.attributes().getOrDefault("toolNames", "");
                if (!names.isBlank() && !parseCsvList(names).equals(pending.values().stream()
                        .map(call -> call.function().name()).toList())) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 工具请求名称不匹配");
                }
                requestSeenForPending = true;
            } else if (event.type() == AgentRunEventType.TOOL_OUTCOME) {
                if (!requestSeenForPending) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 缺少 TOOL_CALL_REQUESTED 证据");
                }
                String id = event.attributes().getOrDefault("toolId", "");
                LlmClient.ToolCall call = pending.remove(id);
                String name = event.attributes().getOrDefault("toolName", "");
                if (call == null || !name.equals(call.function().name())
                        || !event.attributes().getOrDefault("argumentsJson", "")
                        .equals(call.function().arguments())) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 工具结果与请求不匹配");
                }
                String status = event.attributes().getOrDefault("status", "");
                if (!Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "DENIED_BY_POLICY", "DENIED_BY_USER")
                        .contains(status.toUpperCase())) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 工具结果状态非法");
                }
                if (!"COMPLETED".equalsIgnoreCase(status)) {
                    return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                            "Team child 工具结果非 COMPLETED，无法安全恢复");
                }
                sideEffect |= "COMPLETED".equalsIgnoreCase(status) && !PLAN_READ_ONLY_TOOLS.contains(name);
                if (pending.isEmpty()) {
                    requestSeenForPending = false;
                }
            } else if (event.type() == AgentRunEventType.RUN_FINISHED
                    || event.type() == AgentRunEventType.RUN_FAILED
                    || event.type() == AgentRunEventType.RUN_CANCELLED
                    || event.type() == AgentRunEventType.BUDGET_EXHAUSTED) {
                terminal = true;
            }
        }
        if (!pending.isEmpty()) {
            return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                    "Team child 存在未完成的工具调用");
        }
        if (started && !terminal) {
            return new ChildEvidence(false, List.copyOf(tools), sideEffect,
                    "Team child 已启动但没有终态事件");
        }
        return new ChildEvidence(true, List.copyOf(tools), sideEffect, "");
    }

    private static boolean fingerprintMatches(TeamStepResumeState step, Map<String, String> attributes) {
        return matchesOptional(attributes, "stepType", step.type())
                && matchesOptional(attributes, "description", step.description())
                && matchesOptional(attributes, "preferredAgent", step.preferredAgent())
                && matchesOptional(attributes, "riskLevel", step.riskLevel())
                && matchesOptional(attributes, "requiredTools", String.join(",", step.requiredTools().stream().sorted().toList()))
                && matchesOptional(attributes, "dependencies", String.join(",", step.dependencies().stream().sorted().toList()));
    }

    private static boolean sameFingerprint(TeamStepResumeState left, TeamStepResumeState right) {
        return left.type().equals(right.type())
                && left.description().equals(right.description())
                && left.requiredTools().stream().sorted().toList()
                .equals(right.requiredTools().stream().sorted().toList())
                && left.preferredAgent().equals(right.preferredAgent())
                && left.riskLevel().equals(right.riskLevel())
                && left.dependencies().stream().sorted().toList()
                .equals(right.dependencies().stream().sorted().toList());
    }

    private static boolean validStatusPhase(String status, String phase) {
        if ("RUNNING".equals(status)) {
            return Set.of("EXECUTING", "REVIEWING", "AWAITING_MERGE").contains(phase);
        }
        return Set.of("PENDING", "COMPLETED", "FAILED", "SKIPPED").contains(status) && phase.isEmpty();
    }

    private static boolean validPhaseTransition(String previous, String next) {
        if (previous == null || previous.isEmpty()) {
            return "EXECUTING".equals(next);
        }
        if (previous.equals(next)) {
            return true;
        }
        return ("EXECUTING".equals(previous) && "REVIEWING".equals(next))
                || ("REVIEWING".equals(previous) && "AWAITING_MERGE".equals(next));
    }

    private static boolean matchesOptional(Map<String, String> attributes, String key, String expected) {
        String actual = attributes.get(key);
        return actual == null || actual.isBlank() || actual.equals(expected);
    }

    private static TeamStepResumeState withTeamCheckpoint(TeamStepResumeState step, String status, String phase,
                                                          String result, String error, int attempt,
                                                          List<String> childRunIds) {
        return new TeamStepResumeState(step.id(), step.description(), step.type(), step.dependencies(),
                step.requiredTools(), step.preferredAgent(), step.riskLevel(), status, phase, attempt,
                result, error, childRunIds);
    }

    private static boolean isTerminalTeamStep(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "SKIPPED".equals(status);
    }

    private static boolean safeRunId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._-]*") && !id.contains("..");
    }

    private static List<String> parseCsvList(String ids) {
        List<String> parsed = new ArrayList<>();
        for (String id : ids.split(",")) {
            String value = id.trim();
            if (value.isEmpty()) {
                return List.of("<invalid>");
            }
            parsed.add(value);
        }
        return List.copyOf(parsed);
    }

    private static boolean isTeamSideEffectTool(String toolName) {
        return toolName == null || toolName.isBlank() || !PLAN_READ_ONLY_TOOLS.contains(toolName);
    }

    private record ChildEvidence(boolean available, List<String> toolNames,
                                 boolean completedSideEffect, String reason) {
        private ChildEvidence {
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
            reason = reason == null ? "" : reason;
        }
    }

    private record TeamRecoveryProjection(TeamResumeState state, List<String> toolNames,
                                           boolean completedSideEffect, String reason) {
        private TeamRecoveryProjection {
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
            reason = reason == null ? "" : reason;
        }

        private static TeamRecoveryProjection unavailable(String reason) {
            return new TeamRecoveryProjection(TeamResumeState.unavailable(reason), List.of(), false, reason);
        }
    }

    private static PlanResumeState applyTaskCheckpoint(AgentRunEvent event, int planVersion,
                                                       Map<String, PlanTaskResumeState> tasks) {
        Map<String, String> attributes = event.attributes();
        Integer checkpointVersion = parsePositiveInt(attributes.get("planVersion"));
        if (checkpointVersion == null || checkpointVersion != planVersion) {
            return PlanResumeState.unavailable("Plan task checkpoint 版本不一致");
        }
        String taskId = attributes.getOrDefault("taskId", "").trim();
        PlanTaskResumeState previous = tasks.get(taskId);
        if (previous == null) {
            return PlanResumeState.unavailable("Plan task checkpoint 引用了未知任务: " + taskId);
        }
        String status = attributes.getOrDefault("taskStatus", "").trim().toUpperCase();
        Integer retryCount = parseNonNegativeInt(attributes.get("retryCount"));
        if (!PLAN_TASK_STATUSES.contains(status) || retryCount == null
                || !attributes.containsKey("result") || !attributes.containsKey("error")) {
            return PlanResumeState.unavailable("Plan task checkpoint 字段非法: " + taskId);
        }
        tasks.put(taskId, withCheckpoint(
                previous, status, attributes.get("result"), attributes.get("error"), retryCount));
        return null;
    }

    private static PlanTaskResumeState withCheckpoint(PlanTaskResumeState task, String status,
                                                       String result, String error, int retryCount) {
        return new PlanTaskResumeState(
                task.id(), task.description(), task.type(), task.dependencies(), task.critical(),
                task.maxRetries(), task.degradation(), task.expectedEvidence(), task.requiredTools(),
                task.preferredAgent(), task.riskLevel(), status, result, error, retryCount);
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

    private static Integer parsePositiveInt(String value) {
        Integer parsed = parseNonNegativeInt(value);
        return parsed == null || parsed == 0 ? null : parsed;
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

    private static RunResumePlan classifyPlan(List<AgentRunEvent> events) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        boolean sideEffect = false;
        for (AgentRunEvent event : events) {
            if (event == null) continue;
            if (event.type() == AgentRunEventType.TOOL_CALL_REQUESTED) {
                for (String name : event.attributes().getOrDefault("toolNames", "").split(",")) {
                    if (!name.isBlank()) tools.add(name.trim());
                }
            } else if (event.type() == AgentRunEventType.TOOL_OUTCOME) {
                String name = event.attributes().getOrDefault("toolName", "").trim();
                if (!name.isEmpty()) tools.add(name);
                if (ToolOutcomeStatus.COMPLETED.name().equalsIgnoreCase(
                        event.attributes().getOrDefault("status", ""))) {
                    sideEffect |= isPlanSideEffect(name);
                }
            }
        }
        if (sideEffect) {
            return new RunResumePlan(false, true, "HIGH", "已完成任务包含可能产生副作用的工具调用", List.copyOf(tools));
        }
        return new RunResumePlan(true, false, "LOW", "Plan checkpoint 完整，仅继续安全的未完成任务", List.copyOf(tools));
    }

    private static boolean isPlanSideEffect(String toolName) {
        return toolName == null || toolName.isBlank() || !PLAN_READ_ONLY_TOOLS.contains(toolName);
    }

    private static boolean isTerminalPlanTask(String status) {
        return "COMPLETED".equals(status) || "SKIPPED".equals(status);
    }

    private static int lastIndexOf(List<AgentRunEvent> events, AgentRunEventType type) {
        if (events == null) return -1;
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentRunEvent event = events.get(i);
            if (event != null && event.type() == type) return i;
        }
        return -1;
    }

    private static List<Integer> indexesOf(List<AgentRunEvent> events, AgentRunEventType type) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) != null && events.get(i).type() == type) {
                indexes.add(i);
            }
        }
        return indexes;
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
