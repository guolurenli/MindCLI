package com.mindcli.app.cli;

import com.mindcli.agent.Agent;
import com.mindcli.agent.PlanExecuteAgent;
import com.mindcli.platform.llm.GLMClient;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.capability.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertSame;

class MainPlanAgentFactoryTest {

    @Test
    void planModeReusesReactToolRegistryAndMemoryManager() throws Exception {
        LlmClient llmClient = new GLMClient("test-key");
        ToolRegistry sharedToolRegistry = new ToolRegistry();
        Agent reactAgent = new Agent(llmClient, sharedToolRegistry);
        MemoryManager sharedMemoryManager = reactAgent.getMemoryManager();

        PlanExecuteAgent planAgent = Main.createPlanAgent(
                llmClient,
                reactAgent,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel()
        );

        assertSame(sharedToolRegistry, readField(planAgent, "toolRegistry"));
        assertSame(sharedMemoryManager, readField(planAgent, "memoryManager"));
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
