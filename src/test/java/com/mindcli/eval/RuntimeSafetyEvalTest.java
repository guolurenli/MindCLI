package com.mindcli.eval;

import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunStatus;
import com.mindcli.runtime.run.dispatch.ToolDispatcher;
import com.mindcli.runtime.run.dispatch.ToolOutcome;
import com.mindcli.runtime.run.dispatch.ToolOutcomeStatus;
import com.mindcli.runtime.run.store.InMemoryRunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RuntimeSafetyEvalTest {

    @Test
    void policyDeniedWriteLeavesOutsideTargetUntouched(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = Files.writeString(root.resolve("outside.txt"), "original");
        AgentEvalFixture fixture = AgentEvalFixture.workspace(workspace, Map.of());
        AgentEvalFixture.ScriptedLlmClient llm = AgentEvalFixture.ScriptedLlmClient.steps(
                AgentEvalFixture.toolResponse("write", "write_outside", "write_file",
                        AgentEvalFixture.writeArgs(outside, "changed")),
                new IOException("stop after policy denial"));

        AgentEvalFixture.AgentEvalResult result = fixture.runReact(llm, "write outside workspace");

        assertEquals("original", Files.readString(outside));
        assertEquals("DENIED_BY_POLICY",
                result.toolOutcome("write_outside").attributes().get("status"));
        assertEquals("POLICY_DENIED",
                result.toolOutcome("write_outside").attributes().get("errorCategory"));
        assertNotEquals(AgentRunStatus.SUCCESS, result.runResult().status());
    }

    @Test
    void resumedDispatchReusesExactOutcomeAndRejectsArgumentCollision(@TempDir Path root) {
        InMemoryRunStore store = new InMemoryRunStore();
        AgentRunContext context = AgentEvalFixture.resumedContext("eval-resume", root);
        String originalArgs = "{\"path\":\"a.txt\",\"content\":\"original\"}";
        AgentEvalFixture.appendCompletedOutcome(
                store, context, "call_1", "write_file", originalArgs, "already written");
        AtomicInteger executions = new AtomicInteger();
        ToolDispatcher dispatcher = new ToolDispatcher(invocation -> {
            executions.incrementAndGet();
            return AgentEvalFixture.completed(invocation, "unexpected");
        }, store);

        ToolOutcome replayed = dispatcher.dispatch(List.of(AgentEvalFixture.toolCall(
                "call_1", "write_file", originalArgs)), context).get(0);
        ToolOutcome collision = dispatcher.dispatch(List.of(AgentEvalFixture.toolCall(
                "call_1", "write_file",
                "{\"path\":\"a.txt\",\"content\":\"changed\"}")), context).get(0);

        assertEquals(0, executions.get());
        assertEquals(ToolOutcomeStatus.COMPLETED, replayed.status());
        assertEquals("replayed", replayed.metadata().get("idempotency"));
        assertEquals(ToolOutcomeStatus.FAILED, collision.status());
        assertEquals("IDEMPOTENCY_KEY_COLLISION", collision.errorCategory());
        assertEquals("collision", collision.metadata().get("idempotency"));
    }
}
