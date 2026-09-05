package com.mindcli.eval;

import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamOutcomeEvalTest {

    @Test
    void routesReadStepToExplorerAndWriteStepToWorker(@TempDir Path root) throws Exception {
        AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of("input.txt", "seed"));
        AgentEvalFixture.ScriptedLlmClient llm = AgentEvalFixture.ScriptedLlmClient.sequence(
                AgentEvalFixture.response("""
                        {"schemaVersion":3,"summary":"inspect then update","tasks":[
                          {"id":"inspect","description":"read input.txt","type":"FILE_READ","dependencies":[],
                           "requiredTools":["read_file"],"riskLevel":"low"},
                          {"id":"update","description":"write output.txt","type":"FILE_WRITE","dependencies":["inspect"],
                           "requiredTools":["write_file"],"riskLevel":"medium"}
                        ]}
                        """),
                AgentEvalFixture.toolResponse("read", "explorer_read", "read_file",
                        "{\"path\":\"input.txt\"}"),
                AgentEvalFixture.response("read seed"),
                AgentEvalFixture.response("{\"approved\":true,\"summary\":\"read verified\",\"issues\":[]}"),
                AgentEvalFixture.toolResponse("write", "worker_write", "write_file",
                        "{\"path\":\"output.txt\",\"content\":\"updated\"}"),
                AgentEvalFixture.response("wrote output"),
                AgentEvalFixture.response("{\"approved\":true,\"summary\":\"write verified\",\"issues\":[]}"));

        AgentEvalFixture.AgentEvalResult result = fixture.runTeam(llm, "inspect then update");

        assertEquals("updated", result.read("output.txt"));
        assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
        assertTrue(result.events().stream().anyMatch(event ->
                event.type() == AgentRunEventType.AGENT_SELECTED
                        && "explorer".equals(event.attributes().get("role"))
                        && "step_1".equals(event.attributes().get("stepId"))));
        assertTrue(result.events().stream().anyMatch(event ->
                event.type() == AgentRunEventType.AGENT_SELECTED
                        && "worker".equals(event.attributes().get("role"))
                        && "step_2".equals(event.attributes().get("stepId"))));
        assertTrue(result.allEvents().stream().anyMatch(event ->
                event.type() == AgentRunEventType.TOOL_OUTCOME
                        && "explorer_read".equals(event.attributes().get("toolId"))
                        && "explorer#1".equals(event.attributes().get("profileName"))
                        && "COMPLETED".equals(event.attributes().get("status"))));
        assertTrue(result.allEvents().stream().anyMatch(event ->
                event.type() == AgentRunEventType.TOOL_OUTCOME
                        && "worker_write".equals(event.attributes().get("toolId"))
                        && "worker#1".equals(event.attributes().get("profileName"))
                        && "COMPLETED".equals(event.attributes().get("status"))));
        assertTrue(result.allEvents().stream().noneMatch(event ->
                event.type() == AgentRunEventType.TOOL_OUTCOME
                        && "explorer".equals(event.attributes().get("role"))
                        && "write_file".equals(event.attributes().get("toolName"))
                        && "COMPLETED".equals(event.attributes().get("status"))));
    }

    @Test
    void rejectedReviewsFailClosed(@TempDir Path root) throws Exception {
        AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of());
        AgentEvalFixture.ScriptedLlmClient llm = AgentEvalFixture.ScriptedLlmClient.sequence(
                AgentEvalFixture.response("""
                        {"schemaVersion":3,"summary":"review candidate","tasks":[
                          {"id":"candidate","description":"produce candidate output","type":"FILE_WRITE","dependencies":[],
                           "requiredTools":["write_file"],"riskLevel":"medium"}
                        ]}
                        """),
                AgentEvalFixture.response("candidate one"),
                AgentEvalFixture.response("{\"approved\":false,\"summary\":\"reject\",\"issues\":[\"missing evidence\"]}"),
                AgentEvalFixture.response("candidate two"),
                AgentEvalFixture.response("{\"approved\":false,\"summary\":\"reject\",\"issues\":[\"still missing\"]}"),
                AgentEvalFixture.response("candidate three"),
                AgentEvalFixture.response("{\"approved\":false,\"summary\":\"reject\",\"issues\":[\"not proven\"]}"));

        AgentEvalFixture.AgentEvalResult result = fixture.runTeam(llm, "produce reviewed output");

        assertNotEquals(AgentRunStatus.SUCCESS, result.runResult().status());
        assertTrue(result.allEvents().stream().anyMatch(event ->
                event.type() == AgentRunEventType.RUN_FAILED
                        && "review".equals(event.attributes().get("phase"))
                        && "false".equals(event.attributes().get("approved"))
                        && "BLOCKED".equals(event.attributes().get("businessStatus"))));
        assertTrue(result.events().stream().noneMatch(event ->
                event.type() == AgentRunEventType.RUN_FINISHED));
    }
}
