package com.mindcli.runtime.agent;

import com.mindcli.llm.LlmClient;
import com.mindcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolDispatcherTest {

    @Test
    void dispatchPreservesToolCallOrderAndArguments() {
        List<ToolRegistry.ToolInvocation> seen = new ArrayList<>();
        ToolDispatcher dispatcher = new ToolDispatcher(invocations -> {
            seen.addAll(invocations);
            return invocations.stream()
                    .map(invocation -> new ToolRegistry.ToolExecutionResult(
                            invocation.id(), invocation.name(), invocation.argumentsJson(),
                            "ok:" + invocation.name(), 5, false, List.of()))
                    .toList();
        });

        List<ToolOutcome> outcomes = dispatcher.dispatch(List.of(
                toolCall("call_1", "read_file", "{\"path\":\"a.txt\"}"),
                toolCall("call_2", "grep_code", "{\"query\":\"Agent\"}")
        ));

        assertEquals(List.of("call_1", "call_2"), outcomes.stream().map(ToolOutcome::id).toList());
        assertEquals("{\"path\":\"a.txt\"}", seen.get(0).argumentsJson());
        assertEquals("ok:grep_code", outcomes.get(1).text());
    }

    @Test
    void dispatchReturnsFailedOutcomeForEachToolCallWhenRegistryThrows() {
        ToolDispatcher dispatcher = new ToolDispatcher(invocations -> {
            throw new IllegalStateException("registry down");
        });

        List<ToolOutcome> outcomes = dispatcher.dispatch(List.of(
                toolCall("call_1", "read_file", "{}"),
                toolCall("call_2", "list_dir", "{}")
        ));

        assertEquals(2, outcomes.size());
        assertEquals(List.of(ToolOutcomeStatus.FAILED, ToolOutcomeStatus.FAILED),
                outcomes.stream().map(ToolOutcome::status).toList());
        assertEquals("registry down", outcomes.get(0).errorMessage());
        assertEquals("call_2", outcomes.get(1).id());
    }

    @Test
    void dispatchEmptyToolCallsReturnsEmptyList() {
        ToolDispatcher dispatcher = new ToolDispatcher(invocations -> {
            throw new AssertionError("should not call registry");
        });

        assertEquals(List.of(), dispatcher.dispatch(List.of()));
    }

    private static LlmClient.ToolCall toolCall(String id, String name, String args) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, args));
    }
}
