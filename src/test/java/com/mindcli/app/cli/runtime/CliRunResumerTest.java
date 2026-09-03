package com.mindcli.app.cli.runtime;

import com.mindcli.agent.Agent;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.platform.llm.GLMClient;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.store.InMemoryRunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRunResumerTest {
    @Test
    void rejectsRunWithoutOriginalInputBeforeBuildingModeAdapters(@TempDir Path tempDir) {
        InMemoryRunStore store = new InMemoryRunStore();
        AgentRunContext context = new AgentRunContext("run_missing_input", AgentMode.REACT, "", tempDir.toString(),
                Instant.now(), Map.of());
        store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new GLMClient("test-key"), tools, store);

        String result = CliRunResumer.resume("run_missing_input", agent, new GLMClient("test-key"),
                null, null, System.out, null, null, null);

        assertTrue(result.contains("无法恢复"));
        assertTrue(store.events(context.runId()).stream()
                .noneMatch(event -> event.type() == AgentRunEventType.RUN_RESUMED));
    }

    @Test
    void reportsMissingPlanCheckpointInsteadOfMissingInput(@TempDir Path tempDir) {
        InMemoryRunStore store = new InMemoryRunStore();
        AgentRunContext context = new AgentRunContext(
                "run_legacy_plan", AgentMode.PLAN, "plan it", tempDir.toString(), Instant.now(), Map.of());
        store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED,
                Map.of("input", context.input())));
        store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new GLMClient("test-key"), tools, store);

        String result = CliRunResumer.resume("run_legacy_plan", agent, new GLMClient("test-key"),
                null, null, System.out, null, null, null);

        assertTrue(result.contains("旧 Plan run 缺少精确恢复 checkpoint"), result);
        assertTrue(store.events(context.runId()).stream()
                .noneMatch(event -> event.type() == AgentRunEventType.RUN_RESUMED));
    }
}
