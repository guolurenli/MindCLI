package com.mindcli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRoleTest {

    @Test
    void shouldHaveThreeRoles() {
        AgentRole[] roles = AgentRole.values();
        assertEquals(3, roles.length);
    }

    @Test
    void shouldHaveCorrectDisplayNames() {
        assertEquals("探索者", AgentRole.EXPLORER.getDisplayName());
        assertEquals("执行者", AgentRole.WORKER.getDisplayName());
        assertEquals("自定义", AgentRole.CUSTOM.getDisplayName());
    }

    @Test
    void shouldHaveNonEmptyDescriptions() {
        for (AgentRole role : AgentRole.values()) {
            assertFalse(role.getDescription().isEmpty(),
                    role.name() + " should have a non-empty description");
        }
    }

    @Test
    void shouldValueOfByName() {
        assertSame(AgentRole.EXPLORER, AgentRole.valueOf("EXPLORER"));
        assertSame(AgentRole.WORKER, AgentRole.valueOf("WORKER"));
    }
}
