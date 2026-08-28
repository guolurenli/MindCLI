package com.mindcli.app.cli.command;

import com.mindcli.agent.AgentRole;
import com.mindcli.agent.profile.AgentProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandHandlerTest {

    @Test
    void parseEmptyPayloadMeansList() {
        AgentCommandHandler.AgentCommandTarget target = AgentCommandHandler.parse(null);
        assertFalse(target.create());
        assertNull(target.name());
        assertNull(target.task());
    }

    @Test
    void parseCreatePayload() {
        AgentCommandHandler.AgentCommandTarget target = AgentCommandHandler.parse("create");
        assertTrue(target.create());
        assertNull(target.name());
        assertNull(target.task());
    }

    @Test
    void parseDetailPayload() {
        AgentCommandHandler.AgentCommandTarget target = AgentCommandHandler.parse("code-reviewer");
        assertFalse(target.create());
        assertEquals("code-reviewer", target.name());
        assertNull(target.task());
    }

    @Test
    void parseRunPayload() {
        AgentCommandHandler.AgentCommandTarget target = AgentCommandHandler.parse("code-reviewer 审查这个 PR");
        assertFalse(target.create());
        assertEquals("code-reviewer", target.name());
        assertEquals("审查这个 PR", target.task());
    }

    @Test
    void renderTomlEscapesQuotesAndBackslashes() {
        String toml = AgentCommandHandler.renderToml(
                "rev\"iewer", "含\"引号\"与\\反斜杠", "单行", "workspace-write", "on-request", "auto");
        assertTrue(toml.contains("name = \"rev\\\"iewer\""));
        assertTrue(toml.contains("sandbox_mode = \"workspace-write\""));
        assertTrue(toml.contains("approval_policy = \"on-request\""));
    }

    @Test
    void renderTomlEscapesNewlinesInDeveloperInstructions() {
        String toml = AgentCommandHandler.renderToml(
                "reviewer", "审查", "你是资深审查员\n只输出 JSON", "workspace-write", "on-request", "auto");
        assertTrue(toml.contains("developer_instructions = \"你是资深审查员\\n只输出 JSON\""));
    }

    @Test
    void findReturnsMatchingProfile() {
        AgentProfile profile = AgentProfile.custom("x", "d", "i", "read-only", "on-request", "auto");
        assertEquals(profile, AgentCommandHandler.find(List.of(profile), "x"));
        assertNull(AgentCommandHandler.find(List.of(profile), "missing"));
    }

    @Test
    void listIncludesBuiltinAndCustomLabels() {
        AgentProfile custom = AgentProfile.custom("code-reviewer", "审查", "i", "read-only", "on-request", "auto");
        AgentProfile worker = AgentProfile.builtinWorker("worker#1");
        String output = AgentCommandHandler.list(List.of(worker, custom));
        assertTrue(output.contains("code-reviewer"));
        assertTrue(output.contains("worker#1"));
        assertTrue(output.contains("custom"));
        assertTrue(output.contains("worker"));
    }

    @Test
    void detailShowsDeveloperInstructions() {
        AgentProfile custom = AgentProfile.custom(
                "code-reviewer", "审查", "你是资深代码审查员", "read-only", "never", "auto");
        String output = AgentCommandHandler.detail(List.of(custom), "code-reviewer");
        assertTrue(output.contains("你是资深代码审查员"));
        assertTrue(output.contains("never"));
        assertTrue(output.contains("READ_ONLY"));
    }

    @Test
    void detailReturnsNotFoundMessage() {
        String output = AgentCommandHandler.detail(List.of(), "nope");
        assertTrue(output.contains("未找到子代理"));
    }

    @Test
    void detailRoleLabelMapsToLowercase() {
        AgentProfile custom = AgentProfile.custom("c", "d", "i", "read-only", "on-request", "auto");
        assertEquals(AgentRole.CUSTOM, custom.role());
        String output = AgentCommandHandler.detail(List.of(custom), "c");
        assertTrue(output.contains("custom"));
    }
}
