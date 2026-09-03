package com.mindcli.eval;

import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvalFixtureTest {

    @Test
    void createsIsolatedWorkspaceAndRunsReactThroughAgentRuntime(@TempDir Path root) throws Exception {
        AgentEvalFixture fixture = AgentEvalFixture.workspace(
                root,
                Map.of("src/App.java", "class App {}\n"));
        AgentEvalFixture.ScriptedLlmClient llm = AgentEvalFixture.ScriptedLlmClient.sequence(
                AgentEvalFixture.response("done"));

        AgentEvalFixture.AgentEvalResult result = fixture.runReact(llm, "inspect app");

        assertEquals("class App {}\n", result.read("src/App.java"));
        assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
        List<AgentRunEventType> eventTypes = result.events().stream()
                .map(event -> event.type())
                .toList();
        assertTrue(eventTypes.contains(AgentRunEventType.RUN_STARTED));
        assertTrue(eventTypes.contains(AgentRunEventType.MODE_SELECTED));
        assertTrue(eventTypes.contains(AgentRunEventType.LLM_RESPONSE));
        assertTrue(eventTypes.contains(AgentRunEventType.RUN_FINISHED));
    }
}
