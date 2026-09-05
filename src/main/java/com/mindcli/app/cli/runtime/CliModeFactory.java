package com.mindcli.app.cli.runtime;

import com.mindcli.agent.Agent;
import com.mindcli.agent.PlanExecuteAgent;
import com.mindcli.agent.profile.AgentProfile;
import com.mindcli.agent.team.AgentOrchestrator;
import com.mindcli.agent.team.SubAgent;
import com.mindcli.capability.mcp.McpServerManager;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.runtime.run.mode.*;

import java.io.PrintStream;
import java.util.Objects;

/** Creates CLI mode agents while keeping shared ToolRegistry/MemoryManager wiring in one place. */
public final class CliModeFactory {
    private CliModeFactory() {
    }

    public static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                                    PlanExecuteAgent.PlanReviewHandler reviewHandler,
                                                    PrintStream out) {
        Objects.requireNonNull(reactAgent, "reactAgent");
        return new PlanExecuteAgent(
                llmClient,
                reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(),
                reviewHandler,
                out == null ? System.out : out);
    }

    public static AgentOrchestrator createTeamAgent(LlmClient llmClient, Agent reactAgent, PrintStream out) {
        Objects.requireNonNull(reactAgent, "reactAgent");
        return new AgentOrchestrator(llmClient, reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(), out == null ? System.out : out);
    }

    public static SubAgent createSingleAgent(AgentProfile profile, LlmClient llmClient, Agent reactAgent,
                                             McpServerManager mcpServerManager,
                                             SkillRegistry skillRegistry) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(reactAgent, "reactAgent");
        SubAgent agent = new SubAgent(profile, llmClient, reactAgent.getToolRegistry());
        agent.setMemoryManager(reactAgent.getMemoryManager());
        if (mcpServerManager != null) {
            agent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
        }
        agent.setSkillRegistry(skillRegistry);
        return agent;
    }
}
