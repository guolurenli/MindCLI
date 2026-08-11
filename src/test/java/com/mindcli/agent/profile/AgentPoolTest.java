package com.mindcli.agent.profile;

import com.mindcli.agent.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentPoolTest {
    @Test
    void selectsPreferredAgentWhenItSatisfiesRequiredTools() {
        AgentPool pool = new AgentPool(List.of(
                AgentProfile.worker("code-reader", List.of("@read"), 2),
                AgentProfile.worker("code-writer", List.of("read_file", "write_file"), 1)
        ));

        try (AgentPool.AgentLease lease = pool.acquire(AgentRole.WORKER,
                new AgentTaskRequirements("task_1", List.of("write_file"), "code-writer", "medium"))) {
            assertEquals("code-writer", lease.profile().name());
            assertEquals("preferredAgent matched", lease.selectionReason());
        }
    }

    @Test
    void fallsBackToLeastPrivilegeAgentWhenPreferredAgentIsUnavailable() {
        AgentPool pool = new AgentPool(List.of(
                AgentProfile.worker("code-reader", List.of("@read"), 2),
                AgentProfile.worker("legacy-worker", List.of("*"), 1)
        ));

        try (AgentPool.AgentLease lease = pool.acquire(AgentRole.WORKER,
                new AgentTaskRequirements("task_1", List.of("read_file"), "missing", "low"))) {
            assertEquals("code-reader", lease.profile().name());
        }
    }

    @Test
    void failsWhenNoProfileCanSatisfyRequiredTools() {
        AgentPool pool = new AgentPool(List.of(
                AgentProfile.worker("code-reader", List.of("@read"), 2)
        ));

        assertThrows(IllegalStateException.class, () -> pool.acquire(AgentRole.WORKER,
                new AgentTaskRequirements("task_1", List.of("write_file"), "", "medium")));
    }
}
