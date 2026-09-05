package com.mindcli.eval;

import com.mindcli.runtime.run.AgentRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactOutcomeEvalTest {

    @Test
    void locatesAndReadsTheMatchingImplementation(@TempDir Path root) throws Exception {
        AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of(
                "src/FastHasher.java", "class FastHasher { String marker = \"TARGET_IMPL\"; }\n",
                "src/LegacyHasher.java", "class LegacyHasher { String marker = \"legacy\"; }\n"));
        AgentEvalFixture.ScriptedLlmClient llm = AgentEvalFixture.ScriptedLlmClient.sequence(
                AgentEvalFixture.toolResponse("search", "grep_1", "grep_code",
                        "{\"query\":\"TARGET_IMPL\",\"path\":\"src\"}"),
                AgentEvalFixture.toolResponse("read", "read_1", "read_file",
                        "{\"path\":\"src/FastHasher.java\"}"),
                AgentEvalFixture.response("found"));

        AgentEvalFixture.AgentEvalResult result = fixture.runReact(llm, "find TARGET_IMPL");

        assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
        assertEquals(1, result.successfulToolCalls("grep_code"));
        assertEquals(1, result.successfulToolCalls("read_file"));
        assertTrue(result.toolOutcomes("read_file").get(0).attributes().get("text")
                .contains("TARGET_IMPL"));
    }

    @Test
    void changesOnlyTheRequestedFileAndWritesOnce(@TempDir Path root) throws Exception {
        AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of(
                "src/App.java", "class App { int port = 1; }\n",
                "src/Keep.java", "class Keep {}\n"));
        Map<String, String> before = fixture.snapshotFiles();
        AgentEvalFixture.ScriptedLlmClient llm = AgentEvalFixture.ScriptedLlmClient.sequence(
                AgentEvalFixture.toolResponse("read", "read_1", "read_file",
                        "{\"path\":\"src/App.java\"}"),
                AgentEvalFixture.toolResponse("write", "write_1", "write_file",
                        "{\"path\":\"src/App.java\",\"content\":\"class App { int port = 2; }\\n\"}"),
                AgentEvalFixture.response("updated"));

        AgentEvalFixture.AgentEvalResult result = fixture.runReact(llm, "change port to 2");

        assertEquals("class App { int port = 2; }\n", result.read("src/App.java"));
        assertEquals("class Keep {}\n", result.read("src/Keep.java"));
        assertEquals(before.keySet(), result.snapshotFiles().keySet());
        assertEquals(1, result.successfulToolCalls("write_file"));
        assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
    }
}
