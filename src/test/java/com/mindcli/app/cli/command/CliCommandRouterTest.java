package com.mindcli.app.cli.command;

import com.mindcli.agent.Agent;
import com.mindcli.app.cli.CliCommandParser;
import com.mindcli.platform.hitl.SwitchableHitlHandler;
import com.mindcli.platform.render.Renderer;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunResult;
import com.mindcli.runtime.run.session.SessionContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CliCommandRouterTest {

    @Test
    void supportsLowRiskCommandsMovedOutOfMain() {
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.MEMORY_STATUS));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.SNAPSHOT));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.RESTORE_SNAPSHOT));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.RUN_INSPECT));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.EXPORT));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.SKILL_LIST));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.WECHAT));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.CLEAR));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.COMPACT));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.HISTORY_CLEAR));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.INIT_PROJECT_MEMORY));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.CONTEXT_STATUS));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.SWITCH_HITL));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.POLICY_STATUS));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.AUDIT_TAIL));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.BROWSER));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.CONFIG));
        assertTrue(CliCommandRouter.supports(CliCommandParser.CommandType.AGENT));
        assertFalse(CliCommandRouter.supports(CliCommandParser.CommandType.SWITCH_MODEL));
    }

    @Test
    void clearCommandClearsReactAndCrossRunSessionState() {
        Agent agent = mock(Agent.class);
        SwitchableHitlHandler hitlHandler = mock(SwitchableHitlHandler.class);
        SessionContext sessionContext = populatedSession();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        List<String> phases = new ArrayList<>();
        CliCommandRouter router = new CliCommandRouter(context(
                sink, agent, null, sessionContext, hitlHandler, phases));

        assertTrue(router.dispatch(new CliCommandParser.ParsedCommand(
                CliCommandParser.CommandType.CLEAR, null)));

        verify(agent).clearHistory();
        verify(hitlHandler).clearApprovedAll();
        assertTrue(sessionContext.recentSummaries().isEmpty());
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("长期记忆保持不变"));
        assertTrue(phases.contains("idle"));
    }

    @Test
    void compactCommandKeepsStatusAndResultFormattingBehindRouter() {
        Agent agent = mock(Agent.class);
        Renderer renderer = mock(Renderer.class);
        when(agent.compactHistoryNow()).thenReturn(new Agent.CompactionResult(true, 100, 40, null));
        when(renderer.supportsActivityPanel()).thenReturn(false);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        List<String> phases = new ArrayList<>();
        CliCommandRouter router = new CliCommandRouter(context(
                sink, agent, renderer, new SessionContext(), null, phases));

        assertTrue(router.dispatch(new CliCommandParser.ParsedCommand(
                CliCommandParser.CommandType.COMPACT, null)));

        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("100 -> 40 tokens"));
        assertTrue(phases.equals(List.of("compacting", "idle")), phases.toString());
    }

    @Test
    void hitlCommandUpdatesPolicyAndStatusThroughRouter() {
        SwitchableHitlHandler hitlHandler = mock(SwitchableHitlHandler.class);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        List<String> phases = new ArrayList<>();
        CliCommandRouter router = new CliCommandRouter(context(
                sink, null, null, new SessionContext(), hitlHandler, phases));

        assertTrue(router.dispatch(new CliCommandParser.ParsedCommand(
                CliCommandParser.CommandType.SWITCH_HITL, "on")));

        verify(hitlHandler).setEnabled(true);
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("HITL 审批已启用"));
        assertTrue(phases.equals(List.of("idle")), phases.toString());
    }

    private static CliCommandRouter.Context context(ByteArrayOutputStream sink,
                                                    Agent agent,
                                                    Renderer renderer,
                                                    SessionContext sessionContext,
                                                    SwitchableHitlHandler hitlHandler,
                                                    List<String> phases) {
        return new CliCommandRouter.Context(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                agent,
                renderer,
                null,
                null,
                null,
                null,
                null,
                null,
                sessionContext,
                hitlHandler,
                phases::add,
                null,
                null,
                null,
                null,
                null);
    }

    private static SessionContext populatedSession() {
        SessionContext sessionContext = new SessionContext();
        AgentRunContext runContext = new AgentRunContext(
                "run-clear-test",
                AgentMode.REACT,
                "test",
                ".",
                Instant.parse("2026-09-01T10:00:00Z"),
                Map.of());
        sessionContext.record(AgentRunResult.success(runContext, "done"));
        return sessionContext;
    }
}
