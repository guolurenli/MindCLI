package com.mindcli.eval;

import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanOutcomeEvalTest {

    @Test
    void executesDependentTaskOnlyAfterProducerCompletes(@TempDir Path root) throws Exception {
        AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of());
        AgentEvalFixture.ScriptedLlmClient llm = AgentEvalFixture.ScriptedLlmClient.sequence(
                AgentEvalFixture.response("""
                        {"schemaVersion":2,"summary":"produce then consume","tasks":[
                          {"id":"produce","description":"write input.txt","type":"FILE_WRITE","dependencies":[]},
                          {"id":"consume","description":"read input.txt","type":"FILE_READ","dependencies":["produce"]}
                        ]}
                        """),
                AgentEvalFixture.toolResponse("write", "write_1", "write_file",
                        "{\"path\":\"input.txt\",\"content\":\"ready\"}"),
                AgentEvalFixture.response("producer done"),
                AgentEvalFixture.toolResponse("read", "read_1", "read_file",
                        "{\"path\":\"input.txt\"}"),
                AgentEvalFixture.response("consumer done"));

        AgentEvalFixture.AgentEvalResult result = fixture.runPlan(
                llm,
                "first produce an input artifact and then consume it in a dependent task");

        assertEquals("ready", result.read("input.txt"));
        assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
        assertTrue(result.firstToolOutcomeIndex("write_1") < result.firstToolOutcomeIndex("read_1"));
        assertEquals("COMPLETED", result.toolOutcome("read_1").attributes().get("status"));
        assertEquals("task_2", result.toolOutcome("read_1").attributes().get("taskId"));
    }

    @Test
    void skipsExplicitlyDegradableTaskAndContinuesDownstream(@TempDir Path root) throws Exception {
        AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of("stable.txt", "baseline"));
        AgentEvalFixture.ScriptedLlmClient llm = AgentEvalFixture.ScriptedLlmClient.steps(
                AgentEvalFixture.response("""
                        {"schemaVersion":2,"summary":"degrade safely","tasks":[
                          {"id":"optional","description":"load optional context","type":"ANALYSIS","dependencies":[],
                           "critical":false,"maxRetries":0,"degradation":"SKIP"},
                          {"id":"downstream","description":"continue with stable baseline","type":"ANALYSIS","dependencies":["optional"]}
                        ]}
                """),
                new IOException("fatal optional step"),
                AgentEvalFixture.toolResponse("read baseline", "read_stable", "read_file",
                        "{\"path\":\"stable.txt\"}"),
                AgentEvalFixture.response("downstream completed from stable baseline"));

        AgentEvalFixture.AgentEvalResult result = fixture.runPlan(
                llm,
                "load optional context first and then continue with the stable baseline");

        assertEquals("baseline", result.read("stable.txt"));
        assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
        assertEquals("COMPLETED", result.toolOutcome("read_stable").attributes().get("status"));
        assertEquals("task_2", result.toolOutcome("read_stable").attributes().get("taskId"));
        assertTrue(result.events().stream().anyMatch(event ->
                event.type() == AgentRunEventType.RUN_FINISHED));
    }
}
