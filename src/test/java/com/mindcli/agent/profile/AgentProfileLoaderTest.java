package com.mindcli.agent.profile;

import com.mindcli.agent.AgentRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProfileLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsBuiltinExplorerAndWorkerDefaults() {
        List<AgentProfile> profiles = AgentProfileLoader.load(tempDir);

        assertEquals(List.of("explorer#1", "explorer#2", "worker#1"),
                profiles.stream().map(AgentProfile::name).toList());
        assertEquals(2, profiles.stream().filter(profile -> profile.role() == AgentRole.EXPLORER).count());
        assertEquals(1, profiles.stream().filter(profile -> profile.role() == AgentRole.WORKER).count());
    }

    @Test
    void builtinExplorerIsReadOnly() {
        AgentProfile explorer = AgentProfile.builtinExplorer("explorer#1");
        assertEquals(AgentRole.EXPLORER, explorer.role());
        assertEquals("READ_ONLY", explorer.permissionMode());
        assertTrue(explorer.allowsTool("grep_code"));
        assertFalse(explorer.allowsTool("write_file"));
    }

    @Test
    void builtinWorkerHasFullToolAccess() {
        AgentProfile worker = AgentProfile.builtinWorker("worker#1");
        assertEquals(AgentRole.WORKER, worker.role());
        assertEquals("LEGACY_COMPAT", worker.permissionMode());
        assertTrue(worker.allowsTool("write_file"));
    }

    @Test
    void builtinDefaultsAlwaysContainsWorker() {
        List<AgentProfile> profiles = AgentProfileLoader.builtinDefaults();
        assertTrue(profiles.stream().anyMatch(profile -> profile.role() == AgentRole.WORKER));
    }

    @Test
    void loadsCustomAgentFromToml() throws Exception {
        Path agentsDir = tempDir.resolve(".mindcli").resolve("agents");
        java.nio.file.Files.createDirectories(agentsDir);
        java.nio.file.Files.writeString(agentsDir.resolve("code-reviewer.toml"), """
                name = "code-reviewer"
                description = "审查代码质量"
                developer_instructions = "你是资深代码审查员"
                sandbox_mode = "read-only"
                approval_policy = "never"
                model = "auto"
                """);

        List<AgentProfile> profiles = AgentProfileLoader.load(tempDir);

        AgentProfile custom = profiles.stream()
                .filter(profile -> profile.name().equals("code-reviewer"))
                .findFirst()
                .orElse(null);
        assertTrue(custom != null, "custom agent should be loaded");
        assertEquals(AgentRole.CUSTOM, custom.role());
        assertEquals("审查代码质量", custom.description());
        assertEquals("READ_ONLY", custom.permissionMode());
        assertEquals("never", custom.approvalPolicy());
        assertTrue(custom.allowsTool("grep_code"));
        assertFalse(custom.allowsTool("write_file"));
    }

    @Test
    void mapsWorkspaceWriteSandboxToFullAccess() {
        AgentProfile profile = AgentProfile.custom(
                "writer", "desc", "instructions", "workspace-write", "on-request", "auto");
        assertEquals(AgentRole.CUSTOM, profile.role());
        assertEquals("LEGACY_COMPAT", profile.permissionMode());
        assertTrue(profile.allowsTool("write_file"));
    }

    @Test
    void mapsDangerFullAccessSandbox() {
        AgentProfile profile = AgentProfile.custom(
                "danger", "desc", "instructions", "danger-full-access", "untrusted", "auto");
        assertEquals("DANGER_FULL_ACCESS", profile.permissionMode());
        assertEquals("untrusted", profile.approvalPolicy());
        assertTrue(profile.allowsTool("execute_command"));
    }

    @Test
    void skipsCustomAgentWithMissingDescription() throws Exception {
        Path agentsDir = tempDir.resolve(".mindcli").resolve("agents");
        java.nio.file.Files.createDirectories(agentsDir);
        java.nio.file.Files.writeString(agentsDir.resolve("bad.toml"), """
                name = "bad"
                developer_instructions = "缺少 description"
                """);

        List<AgentProfile> profiles = AgentProfileLoader.load(tempDir);

        assertTrue(profiles.stream().noneMatch(profile -> profile.name().equals("bad")));
    }

    @Test
    void skipsCustomAgentWithInvalidName() throws Exception {
        Path agentsDir = tempDir.resolve(".mindcli").resolve("agents");
        java.nio.file.Files.createDirectories(agentsDir);
        java.nio.file.Files.writeString(agentsDir.resolve("Bad_Name.toml"), """
                name = "Bad_Name"
                description = "非法名"
                developer_instructions = "x"
                """);

        List<AgentProfile> profiles = AgentProfileLoader.load(tempDir);

        assertTrue(profiles.stream().noneMatch(profile -> profile.name().equals("Bad_Name")));
    }

    @Test
    void skipsDuplicateCustomAgentName() throws Exception {
        Path agentsDir = tempDir.resolve(".mindcli").resolve("agents");
        java.nio.file.Files.createDirectories(agentsDir);
        String toml = """
                name = "dup"
                description = "重复"
                developer_instructions = "x"
                """;
        java.nio.file.Files.writeString(agentsDir.resolve("a.toml"), toml);
        java.nio.file.Files.writeString(agentsDir.resolve("b.toml"), toml);

        List<AgentProfile> profiles = AgentProfileLoader.load(tempDir);

        assertEquals(1, profiles.stream().filter(profile -> profile.name().equals("dup")).count());
    }
}
