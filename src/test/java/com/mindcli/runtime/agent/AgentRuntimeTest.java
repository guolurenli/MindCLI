package com.mindcli.runtime.agent;

import com.mindcli.agent.Agent;
import com.mindcli.llm.LlmClient;
import com.mindcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsSuccessLifecycleEventsInOrder() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", "workspace");

        AgentRunResult result = runtime.run(context, adapterReturning(AgentMode.REACT,
                AgentRunResult.success(context, "done")));

        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals("done", result.content());
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FINISHED);
    }

    @Test
    void recordsFailureWhenAdapterReturnsFailure() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "plan it", "workspace");

        AgentRunResult result = runtime.run(context, adapterReturning(AgentMode.PLAN,
                AgentRunResult.failed(context, "no plan")));

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertEquals("no plan", result.errorMessage());
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FAILED);
    }

    @Test
    void reactAdapterRecordsLoopEventsInRuntimeRunStore() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", tempDir.toString());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "done", null, 10, 3)
        )), registry);

        AgentRunResult result = runtime.run(context, new ReActModeAdapter(agent));

        assertEquals(AgentRunStatus.SUCCESS, result.status());
        assertEquals("done", result.content());
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.LLM_RESPONSE,
                AgentRunEventType.RUN_FINISHED);
    }

    @Test
    void reactAdapterReportsLlmFailureAsRuntimeFailure() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "hello", tempDir.toString());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new ScriptedClient(new IOException("llm down")), registry);

        AgentRunResult result = runtime.run(context, new ReActModeAdapter(agent));

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("llm down"));
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FAILED);
    }

    @Test
    void convertsAdapterExceptionToFailedResultAndRunFailedEvent() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRuntime runtime = new AgentRuntime(runStore);
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "team task", "workspace");

        AgentRunResult result = runtime.run(context, new ModeAdapter() {
            @Override
            public AgentMode mode() {
                return AgentMode.TEAM;
            }

            @Override
            public AgentRunResult execute(AgentRunContext context) {
                throw new IllegalStateException("boom");
            }
        });

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("boom"));
        assertEventTypes(runStore.events(context.runId()),
                AgentRunEventType.RUN_STARTED,
                AgentRunEventType.MODE_SELECTED,
                AgentRunEventType.RUN_FAILED);
    }

    private static ModeAdapter adapterReturning(AgentMode mode, AgentRunResult result) {
        return new ModeAdapter() {
            @Override
            public AgentMode mode() {
                return mode;
            }

            @Override
            public AgentRunResult execute(AgentRunContext context) {
                return result;
            }
        };
    }

    private static void assertEventTypes(List<AgentRunEvent> events, AgentRunEventType... expected) {
        assertEquals(expected.length, events.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], events.get(i).type());
        }
    }

    private static final class ScriptedClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final IOException failure;

        private ScriptedClient(List<ChatResponse> responses) {
            this.responses.addAll(responses);
            this.failure = null;
        }

        private ScriptedClient(IOException failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            if (failure != null) {
                throw failure;
            }
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("missing response");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
