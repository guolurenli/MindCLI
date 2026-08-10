package com.mindcli.agent.profile;

import com.mindcli.agent.AgentRole;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.capability.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolPolicyTest {
    @Test
    void readOnlyReviewerCannotWriteEvenIfWriteToolIsConfigured() {
        AgentProfile reviewer = new AgentProfile(
                "verifier",
                AgentRole.REVIEWER,
                "review",
                List.of("read_file", "write_file"),
                List.of(),
                List.of(),
                "auto",
                1,
                "READ_ONLY",
                "PARENT_SUMMARY",
                "balanced");

        AgentToolPolicy.Decision decision = AgentToolPolicy.evaluate(reviewer,
                new ToolRegistry.ToolInvocation("call_1", "write_file", "{\"path\":\"a.txt\"}"));

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("read-only"));
    }

    @Test
    void executeCommandRequiresCommandAllowlistMatch() {
        AgentProfile verifier = new AgentProfile(
                "verifier",
                AgentRole.REVIEWER,
                "review",
                List.of("execute_command"),
                List.of(),
                List.of("git status --short"),
                "auto",
                1,
                "READ_ONLY",
                "PARENT_SUMMARY",
                "balanced");

        assertTrue(AgentToolPolicy.evaluate(verifier,
                new ToolRegistry.ToolInvocation("call_1", "execute_command",
                        "{\"command\":\"git status --short\"}")).allowed());
        assertFalse(AgentToolPolicy.evaluate(verifier,
                new ToolRegistry.ToolInvocation("call_2", "execute_command",
                        "{\"command\":\"mvn test -Pquick\"}")).allowed());
    }

    @Test
    void contextMetadataPolicyDeniesToolsBeforeDispatch() {
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "task", "workspace", Map.of(
                "profileName", "code-reader",
                "profileRole", "WORKER",
                "permissionMode", "READ_ONLY",
                "allowedTools", "read_file,grep_code"));

        AgentToolPolicy.Decision decision = AgentToolPolicy.evaluate(context,
                new ToolRegistry.ToolInvocation("call_1", "write_file", "{\"path\":\"a.txt\"}"));

        assertFalse(decision.allowed());
        assertTrue(decision.metadata().containsKey("profileName"));
        assertTrue(decision.reason().contains("code-reader"));
    }
}
