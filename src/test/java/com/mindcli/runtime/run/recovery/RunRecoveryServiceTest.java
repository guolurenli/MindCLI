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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRecoveryServiceTest {

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
