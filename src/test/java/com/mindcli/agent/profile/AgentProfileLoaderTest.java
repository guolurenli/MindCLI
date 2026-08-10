package com.mindcli.agent.profile;

import com.mindcli.agent.AgentRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProfileLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsCompatDefaultsWhenProjectConfigIsMissing() {
        List<AgentProfile> profiles = AgentProfileLoader.load(tempDir);

        assertFalse(profiles.isEmpty());
        assertTrue(profiles.stream().anyMatch(profile -> profile.role() == AgentRole.PLANNER));
        assertTrue(profiles.stream().anyMatch(profile -> profile.role() == AgentRole.WORKER));
        AgentProfile reviewer = profiles.stream()
                .filter(profile -> profile.role() == AgentRole.REVIEWER)
                .findFirst()
                .orElseThrow();
        assertEquals("READ_ONLY", reviewer.permissionMode());
        assertFalse(reviewer.allowsTool("write_file"));
    }

    @Test
    void loadsProjectProfilesFromMindcliAgentsJson() throws Exception {
        Path configDir = tempDir.resolve(".mindcli");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("agents.json"), """
                {
                  "schemaVersion": 1,
                  "profiles": [
                    {
                      "name": "code-reader",
                      "role": "WORKER",
                      "description": "只读代码分析",
                      "tools": ["@read"],
                      "maxConcurrency": 2,
                      "permissionMode": "READ_ONLY"
                    },
                    {
                      "name": "verifier",
                      "role": "REVIEWER",
                      "tools": ["read_file", "execute_command"],
                      "commandAllowlist": ["git status --short"],
                      "permissionMode": "READ_ONLY"
                    }
                  ]
                }
                """);

        List<AgentProfile> profiles = AgentProfileLoader.load(tempDir);

        assertEquals(List.of("code-reader", "verifier"),
                profiles.stream().map(AgentProfile::name).toList());
        AgentProfile reader = profiles.get(0);
        assertEquals(AgentRole.WORKER, reader.role());
        assertEquals(2, reader.maxConcurrency());
        assertTrue(reader.allowsTool("read_file"));
        assertFalse(reader.allowsTool("write_file"));
    }

    @Test
    void fallsBackToDefaultsWhenConfigJsonIsInvalid() throws Exception {
        Path configDir = tempDir.resolve(".mindcli");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("agents.json"), "not json");

        List<AgentProfile> profiles = AgentProfileLoader.load(tempDir);

        assertTrue(profiles.stream().anyMatch(profile -> "worker-1".equals(profile.name())));
        assertTrue(profiles.stream().anyMatch(profile -> profile.role() == AgentRole.REVIEWER));
    }
}
