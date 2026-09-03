package com.mindcli.runtime.run.recovery;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRecoveryServiceTest {

    @Test
    void teamExecutingReadOnlyChildReturnsToPending() {
        TeamFixture fixture = teamFixture("RUNNING", "EXECUTING", "child-read");
        appendCompleteChildToolTurn(fixture.store(), fixture.child("child-read"),
                "call-1", "read_file", "{\"path\":\"a.txt\"}");
        fixture.cancelParent();

        TeamResumeState state = new RunRecoveryService(fixture.store())
                .reconstructTeamState(fixture.parent().runId());

        assertTrue(state.available(), state.reason());
        assertEquals("PENDING", state.steps().get(0).status());
        assertEquals("", state.steps().get(0).phase());
    }

    @Test
    void teamExecutingWriteWithoutTerminalCheckpointFailsClosed() {
        TeamFixture fixture = teamFixture("RUNNING", "EXECUTING", "child-write");
        appendCompleteChildToolTurn(fixture.store(), fixture.child("child-write"),
                "call-1", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");
        fixture.cancelParent();

        RunRecoveryPlan plan = new RunRecoveryService(fixture.store())
                .inspect(fixture.parent().runId());

        assertTrue(!plan.resumeAvailable());
        assertEquals("UNKNOWN", plan.resumePlan().risk());
        assertTrue(plan.resumePlan().reason().contains("step_1"));
    }

    @Test
    void teamReviewingAndAwaitingMergeAlwaysFailClosed() {
        assertTrue(!inspectTeamAtPhase("REVIEWING").resumeAvailable());
        assertTrue(!inspectTeamAtPhase("AWAITING_MERGE").resumeAvailable());
    }

    @Test
    void completedWriteStepRequiresConfirmation() {
        TeamFixture fixture = teamFixture("COMPLETED", "", "child-completed-write", List.of("write_file"));
        fixture.cancelParent();

        RunRecoveryPlan plan = new RunRecoveryService(fixture.store())
                .inspect(fixture.parent().runId());

        assertTrue(plan.resumeAvailable(), plan.resumePlan().reason());
        assertEquals("HIGH", plan.resumePlan().risk());
        assertTrue(plan.resumePlan().requiresConfirmation());
    }

    @Test
    void reconstructsLatestPlanVersionAndRetriesSafeRunningTaskAsPending() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                Map.of("input", context.input())));
        appendPlanDefinition(runStore, context, planState(1, "old", "PENDING"));
        appendTaskCheckpoint(runStore, context, 1, "task_1", "COMPLETED", "old result");
        appendPlanDefinition(runStore, context, planState(2, "new", "PENDING"));
        appendTaskCheckpoint(runStore, context, 2, "task_1", "RUNNING", "");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, Map.of(
                "taskId", "task_1", "toolId", "read_1", "toolName", "read_file",
                "status", "COMPLETED", "text", "content")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        PlanResumeState restored = new RunRecoveryService(runStore).reconstructPlanState(context.runId());

        assertTrue(restored.available(), restored.reason());
        assertEquals(2, restored.planVersion());
        assertEquals("new", restored.summary());
        assertEquals("PENDING", restored.tasks().get(0).status());
    }

    @Test
    void planInspectionRejectsSuccessfulSideEffectWithoutTerminalTaskCheckpoint() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                Map.of("input", context.input())));
        appendPlanDefinition(runStore, context, planState(1, "plan", "RUNNING"));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, Map.of(
                "taskId", "task_1", "toolId", "write_1", "toolName", "write_file",
                "status", "COMPLETED", "text", "written")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        RunRecoveryPlan inspected = new RunRecoveryService(runStore).inspect(context.runId());

        assertTrue(!inspected.resumeAvailable());
        assertTrue(inspected.resumePlan().reason().contains("副作用"), inspected.resumePlan().reason());
    }

    @Test
    void rejectsSideEffectBeforeLatestDefinitionWhenTaskIsStillRunningInThatDefinition() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                Map.of("input", context.input())));
        appendPlanDefinition(runStore, context, planState(1, "initial", "RUNNING"));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, Map.of(
                "taskId", "task_1", "toolId", "write_parallel", "toolName", "write_file",
                "status", "COMPLETED", "text", "written")));
        appendPlanDefinition(runStore, context, planState(2, "replanned", "RUNNING"));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        PlanResumeState restored = new RunRecoveryService(runStore).reconstructPlanState(context.runId());

        assertTrue(!restored.available());
        assertTrue(restored.reason().contains("副作用"), restored.reason());
    }

    @Test
    void planInspectionKeepsCompletedSideEffectAsConfirmationRequired() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                Map.of("input", context.input())));
        appendPlanDefinition(runStore, context, planState(1, "plan", "RUNNING"));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, Map.of(
                "taskId", "task_1", "toolId", "write_1", "toolName", "write_file",
                "status", "COMPLETED", "text", "written")));
        appendTaskCheckpoint(runStore, context, 1, "task_1", "COMPLETED", "done");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        RunRecoveryPlan inspected = new RunRecoveryService(runStore).inspect(context.runId());

        assertTrue(inspected.resumeAvailable(), inspected.resumePlan().reason());
        assertEquals("HIGH", inspected.resumePlan().risk());
        assertTrue(inspected.resumePlan().requiresConfirmation());
    }

    @Test
    void rejectsPlanCheckpointThatReferencesUnknownTaskOrWrongVersion() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                Map.of("input", context.input())));
        appendPlanDefinition(runStore, context, planState(1, "plan", "PENDING"));
        appendTaskCheckpoint(runStore, context, 2, "missing", "COMPLETED", "done");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        PlanResumeState restored = new RunRecoveryService(runStore).reconstructPlanState(context.runId());

        assertTrue(!restored.available());
        assertTrue(restored.reason().contains("版本"), restored.reason());
    }

    @Test
    void oldPlanLedgerWithoutDefinitionIsNotResumeAvailable() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        RunRecoveryPlan inspected = new RunRecoveryService(runStore).inspect(context.runId());

        assertTrue(!inspected.resumeAvailable());
        assertTrue(inspected.resumePlan().reason().contains("旧 Plan run"), inspected.resumePlan().reason());
    }

    @Test
    void inspectsResumableRunFromRunStore() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        RunRecoveryPlan plan = new RunRecoveryService(runStore).inspect(context.runId());

        assertEquals(context.runId(), plan.runId());
        assertEquals(RunStateStatus.RESUMABLE, plan.stateStatus());
        assertTrue(plan.resumable());
        assertEquals(AgentMode.REACT, plan.mode());
        assertEquals("workspace", plan.workspace());
        assertEquals("hello", plan.originalInput());
        assertTrue(plan.resumeAvailable());
        assertEquals(AgentRunEventType.LLM_RESPONSE, plan.lastCompletedEventType());
        assertEquals(List.of(AgentRunEventType.RUN_STARTED, AgentRunEventType.LLM_RESPONSE, AgentRunEventType.RUN_CANCELLED),
                plan.events().stream().map(AgentRunEvent::type).toList());
    }

    @Test
    void marksRunWithoutPersistedInputAsNotResumeAvailable() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        RunRecoveryPlan plan = new RunRecoveryService(runStore).inspect(context.runId());

        assertTrue(plan.resumable());
        assertEquals("", plan.originalInput());
        assertTrue(!plan.resumeAvailable());
    }

    @Test
    void classifiesWriteToolAsConfirmationRequired() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_CALL_REQUESTED,
                java.util.Map.of("toolNames", "write_file")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME,
                java.util.Map.of("toolName", "write_file", "status", "COMPLETED")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        RunResumePlan resumePlan = new RunRecoveryService(runStore).inspect(context.runId()).resumePlan();

        assertEquals("HIGH", resumePlan.risk());
        assertTrue(resumePlan.requiresConfirmation());
        assertTrue(!resumePlan.allowed());
    }

    @Test
    void reconstructsReactMessagesFromPersistedResponsesAndToolResults() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "inspect", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE, java.util.Map.of(
                "content", "", "reasoningContent", "thinking", "toolCallCount", "1",
                "toolCallsJson", "[{\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, java.util.Map.of(
                "toolId", "call_1", "toolName", "read_file", "argumentsJson", "{\"path\":\"a.txt\"}",
                "text", "file text", "status", "COMPLETED")));

        ReActResumeState state = new RunRecoveryService(runStore).reconstructReActState(context.runId());

        assertTrue(state.available());
        assertEquals(List.of("user", "assistant", "tool"),
                state.messages().stream().map(com.mindcli.platform.llm.LlmClient.Message::role).toList());
        assertEquals("call_1", state.messages().get(2).toolCallId());
        assertEquals("file text", state.messages().get(2).content());
    }

    @Test
    void rejectsMalformedPersistedToolCallState() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "inspect", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE,
                java.util.Map.of("toolCallCount", "1", "toolCallsJson", "not-json")));

        ReActResumeState state = new RunRecoveryService(runStore).reconstructReActState(context.runId());

        assertTrue(!state.available());
    }

    @Test
    void rejectsAssistantToolCallWithoutOutcomeEvenWhenRequestEventIsMissing() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "inspect", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE,
                java.util.Map.of("content", "", "reasoningContent", "", "toolCallCount", "1",
                        "toolCallsJson", "[{\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]")));

        ReActResumeState state = new RunRecoveryService(runStore).reconstructReActState(context.runId());

        assertTrue(!state.available());
        assertTrue(state.reason().contains("工具调用"));
    }

    @Test
    void classifiesIncompleteAssistantToolCallAsUnknownDuringInspection() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "inspect", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE,
                java.util.Map.of("content", "", "reasoningContent", "", "toolCallCount", "1",
                        "toolCallsJson", "[{\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        RunResumePlan plan = new RunRecoveryService(runStore).inspect(context.runId()).resumePlan();

        assertEquals("UNKNOWN", plan.risk());
        assertTrue(plan.requiresConfirmation());
        assertTrue(!plan.allowed());
    }

    @Test
    void reconstructsMultipleResumeAttemptsAsOneCanonicalMessageHistory() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "inspect", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                java.util.Map.of("input", context.input())));
        appendCompletedToolTurn(runStore, context, "call_1", "first");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_RESUMED));
        appendCompletedToolTurn(runStore, context, "call_2", "second");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        ReActResumeState state = new RunRecoveryService(runStore).reconstructReActState(context.runId());

        assertTrue(state.available());
        assertEquals(List.of("user", "assistant", "tool", "assistant", "tool"),
                state.messages().stream().map(com.mindcli.platform.llm.LlmClient.Message::role).toList());
        assertEquals(1, state.messages().stream().filter(message -> "user".equals(message.role())).count());
        assertEquals(List.of("call_1", "call_2"), state.messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .map(com.mindcli.platform.llm.LlmClient.Message::toolCallId)
                .toList());
    }

    private static void appendCompletedToolTurn(InMemoryRunStore runStore, AgentRunContext context,
                                                 String callId, String text) {
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE, java.util.Map.of(
                "content", "", "reasoningContent", "", "toolCallCount", "1",
                "toolCallsJson", "[{\"id\":\"" + callId + "\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_CALL_REQUESTED,
                java.util.Map.of("toolCallCount", "1", "toolNames", "read_file")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, java.util.Map.of(
                "toolId", callId, "toolName", "read_file", "argumentsJson", "{}",
                "text", text, "status", "COMPLETED")));
    }

    private record TeamFixture(InMemoryRunStore store, AgentRunContext parent, String childId) {
        AgentRunContext child(String id) {
            return new AgentRunContext(id, AgentMode.TEAM, parent.input(), parent.workspace(),
                    parent.startedAt(), Map.of("parentRunId", parent.runId(), "stepId", "step_1"));
        }

        void cancelParent() {
            store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_CANCELLED));
        }
    }

    private static TeamFixture teamFixture(String status, String phase, String childId) {
        return teamFixture(status, phase, childId, List.of());
    }

    private static TeamFixture teamFixture(String status, String phase, String childId,
                                            List<String> requiredTools) {
        InMemoryRunStore store = new InMemoryRunStore();
        AgentRunContext parent = new AgentRunContext("team-parent-" + childId, AgentMode.TEAM,
                "goal", "workspace", java.time.Instant.now(), Map.of());
        store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_STARTED,
                Map.of("input", "goal")));
        TeamResumeState initial = new TeamResumeState(true, 1, 1, List.of(
                new TeamStepResumeState("step_1", "step", "ANALYSIS", List.of(), requiredTools,
                        "", "low", "PENDING", "", 0, "", "", List.of())) , "");
        TeamCheckpointCodec codec = new TeamCheckpointCodec();
        store.append(AgentRunEvent.of(parent, AgentRunEventType.TEAM_PLAN_DEFINED, Map.of(
                "schemaVersion", "1", "planVersion", "1", "planJson", codec.encodePlan(initial))));
        store.append(AgentRunEvent.of(parent, AgentRunEventType.TEAM_STEP_CHECKPOINT, Map.of(
                "schemaVersion", "1", "planVersion", "1",
                "stepIdsJson", codec.encodeStepIds(List.of("step_1")),
                "stepStatus", status, "phase", phase, "attempt", "0",
                "childRunId", childId, "result", "", "error", "")));
        return new TeamFixture(store, parent, childId);
    }

    private static void appendCompleteChildToolTurn(InMemoryRunStore store, AgentRunContext child,
                                                     String id, String name, String arguments) {
        String calls = "[{\"id\":\"" + id + "\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":"
                + com.mindcli.platform.serialization.JsonSupport.mapper().valueToTree(arguments)
                + "}}]";
        store.append(AgentRunEvent.of(child, AgentRunEventType.RUN_STARTED,
                Map.of("phase", "execute")));
        store.append(AgentRunEvent.of(child, AgentRunEventType.LLM_RESPONSE, Map.of(
                "recordKind", "turn", "iteration", "1", "toolCallCount", "1",
                "toolCallsJson", calls)));
        store.append(AgentRunEvent.of(child, AgentRunEventType.TOOL_CALL_REQUESTED, Map.of(
                "recordKind", "turn", "iteration", "1", "toolCallCount", "1",
                "toolNames", name)));
        store.append(AgentRunEvent.of(child, AgentRunEventType.TOOL_OUTCOME, Map.of(
                "toolId", id, "toolName", name, "argumentsJson", arguments,
                "status", "COMPLETED")));
        store.append(AgentRunEvent.of(child, AgentRunEventType.RUN_FINISHED,
                Map.of("phase", "execute", "status", "SUCCESS")));
    }

    private static RunRecoveryPlan inspectTeamAtPhase(String phase) {
        TeamFixture fixture = teamFixture("RUNNING", phase, "child-" + phase.toLowerCase());
        fixture.cancelParent();
        return new RunRecoveryService(fixture.store()).inspect(fixture.parent().runId());
    }

    private static PlanResumeState planState(int version, String summary, String status) {
        return new PlanResumeState(true, version, "plan-1", "goal", summary, List.of(
                new PlanTaskResumeState(
                        "task_1", "task", "ANALYSIS", List.of(), true, 1, "REPLAN",
                        List.of(), List.of(), "", "low", status, "", "", 0)), "");
    }

    private static void appendPlanDefinition(InMemoryRunStore store, AgentRunContext context,
                                             PlanResumeState state) {
        store.append(AgentRunEvent.of(context, AgentRunEventType.PLAN_DEFINED, Map.of(
                "planVersion", Integer.toString(state.planVersion()),
                "reason", state.planVersion() == 1 ? "INITIAL" : "REPLAN",
                "planJson", new PlanCheckpointCodec().encode(state))));
    }

    private static void appendTaskCheckpoint(InMemoryRunStore store, AgentRunContext context,
                                             int version, String taskId, String status, String result) {
        store.append(AgentRunEvent.of(context, AgentRunEventType.PLAN_TASK_CHECKPOINT, Map.of(
                "planVersion", Integer.toString(version),
                "taskId", taskId,
                "taskStatus", status,
                "result", result,
                "error", "",
                "retryCount", "0")));
    }

    @Test
    void exposesSnapshotCheckpointsAndRestoreHint() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.SNAPSHOT_CREATED, java.util.Map.of(
                "snapshotPhase", "PRE_RUN",
                "snapshotCommitId", "commit-pre",
                "snapshotShortCommitId", "commit-pre")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

        RunRecoveryPlan plan = new RunRecoveryService(runStore).inspect(context.runId());

        assertEquals("commit-pre", plan.preRunSnapshotCommitId());
        assertEquals("", plan.postRunSnapshotCommitId());
        assertTrue(plan.restoreHint().contains("pre-run snapshot"));
        assertTrue(plan.restoreHint().contains("commit-pre"));
    }
}
