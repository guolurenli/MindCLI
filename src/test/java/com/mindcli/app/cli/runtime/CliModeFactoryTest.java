package com.mindcli.app.cli.runtime;

import com.mindcli.agent.Agent;
import com.mindcli.agent.PlanExecuteAgent;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.platform.llm.GLMClient;
import com.mindcli.platform.llm.LlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class CliModeFactoryTest {
    @Test
    void planFactoryReusesReactToolRegistryAndMemoryManager() {
        LlmClient client = new GLMClient("test-key");
        ToolRegistry tools = new ToolRegistry();
        Agent react = new Agent(client, tools);
        MemoryManager memory = react.getMemoryManager();

        PlanExecuteAgent plan = CliModeFactory.createPlanAgent(client, react,
                (goal, executionPlan) -> PlanExecuteAgent.PlanReviewDecision.cancel(), System.out);

        assertSame(tools, readField(plan, "toolRegistry"));
        assertSame(memory, readField(plan, "memoryManager"));
    }

    private static Object readField(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
