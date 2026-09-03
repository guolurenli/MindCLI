package com.mindcli.app.cli.runtime;

import com.mindcli.agent.Agent;
import com.mindcli.agent.PlanExecuteAgent;
import com.mindcli.agent.team.AgentOrchestrator;
import com.mindcli.capability.mcp.McpServerManager;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.render.Renderer;
import com.mindcli.platform.snapshot.SnapshotService;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunResult;
import com.mindcli.runtime.run.AgentRuntime;
import com.mindcli.runtime.run.ModeAdapter;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.RunRecoveryPlan;
import com.mindcli.runtime.run.recovery.RunRecoveryService;
import com.mindcli.runtime.run.session.SessionContext;
import com.mindcli.runtime.run.store.RunStore;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.nio.file.Path;

/** Builds the recorded mode adapter and performs a guarded CLI run resume. */
public final class CliRunResumer {
    private CliRunResumer() {
    }

    public static String resume(String runId, Agent reactAgent, LlmClient llmClient,
                                Terminal terminal, LineReader lineReader, PrintStream out,
                                McpServerManager mcpServerManager, SkillRegistry skillRegistry,
                                SessionContext sessionContext) {
        RunStore store = reactAgent.runStore();
        RunRecoveryPlan plan = new RunRecoveryService(store).inspect(runId);
        if (!plan.resumeAvailable()) {
            return "❌ 无法恢复: " + (plan.resumable() ? "历史 run 缺少原始输入或工作区信息" : plan.stateStatus());
        }
        Path currentWorkspace = Path.of(reactAgent.getToolRegistry().getProjectPath()).toAbsolutePath().normalize();
        Path recordedWorkspace = Path.of(plan.workspace()).toAbsolutePath().normalize();
        if (!currentWorkspace.equals(recordedWorkspace)) {
            return "❌ 无法恢复: run workspace 与当前项目不一致（" + plan.workspace() + "）";
        }
        ModeAdapter adapter;
        if (plan.mode() == AgentMode.REACT) {
            adapter = new ReActModeAdapter(reactAgent);
        } else if (plan.mode() == AgentMode.PLAN) {
            PlanExecuteAgent agent = CliModeFactory.createPlanAgent(llmClient, reactAgent,
                    com.mindcli.app.cli.Main.createPlanReviewHandlerForRuntime(terminal, lineReader, out), out);
            agent.setSessionContext(sessionContext);
            agent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
            agent.setSkillRegistry(skillRegistry);
            adapter = new PlanModeAdapter(agent);
        } else if (plan.mode() == AgentMode.TEAM) {
            AgentOrchestrator orchestrator = CliModeFactory.createTeamAgent(llmClient, reactAgent, out);
            orchestrator.setSessionContext(sessionContext);
            orchestrator.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
            orchestrator.setSkillSystem(skillRegistry);
            adapter = new TeamModeAdapter(orchestrator);
        } else {
            return "❌ 不支持恢复 mode: " + plan.mode();
        }
        AgentRunResult result = new AgentRuntime(store, reactAgent.getToolRegistry().getSnapshotService())
                .resume(runId, adapter);
        if (sessionContext != null) {
            sessionContext.record(result, adapter instanceof ReActModeAdapter react
                    ? react.latestAssistantResponse() : null);
        }
        return CliRuntimeCoordinator.userFacingContent(result);
    }
}
