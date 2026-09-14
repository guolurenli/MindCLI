package com.mindcli.agent;

import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.GLMClient;
import com.mindcli.platform.config.ConfigValueResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBudgetTest {

    @Test
    void taskBudgetsShareDeadlineButKeepTokenAndIterationStateIndependent() {
        var context = com.mindcli.runtime.run.AgentRunContext.create(
                com.mindcli.runtime.run.AgentMode.PLAN, "goal", "workspace",
                java.util.Map.of("runDeadlineEpochMillis", "1"));
        AgentBudget first = AgentBudget.forRun(null, context);
        AgentBudget second = AgentBudget.forRun(null, context);
        first.beginIteration();
        first.recordTokens(10, 5);
        assertEquals(0, second.iteration());
        assertEquals(0, second.totalInputTokens());
        assertEquals(AgentBudget.ExitReason.RUN_TIMEOUT, first.check());
        assertEquals(AgentBudget.ExitReason.RUN_TIMEOUT, second.check());
    }

    @Test
    void runWithDisabledDeadlineDoesNotAcquireANewTaskTimeout() {
        String key = "mindcli.react.max.duration.seconds";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "1");
            var context = new com.mindcli.runtime.run.AgentRunContext("run-disabled",
                    com.mindcli.runtime.run.AgentMode.PLAN, "goal", "workspace",
                    java.time.Instant.EPOCH, java.util.Map.of("runDeadlineEpochMillis", "0"));
            assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, AgentBudget.forRun(null, context).check());
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @TempDir
    Path tempDir;

    @Test
    void initiallyWithinBudget() {
        AgentBudget budget = new AgentBudget(1000, 3, 50);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void tokenBudgetExceededAfterAccumulation() {
        AgentBudget budget = new AgentBudget(100, 3, 50);
        budget.recordTokens(60, 30);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.recordTokens(20, 0);
        assertEquals(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED, budget.check());
    }

    @Test
    void repeatedToolRequestsWarnButDoNotTerminateTheRun() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);
        List<LlmClient.ToolCall> sameCall = List.of(
                new LlmClient.ToolCall("call_1",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"))
        );

        budget.recordToolCalls(sameCall);
        budget.recordToolCalls(sameCall);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.recordToolCalls(sameCall);
        assertTrue(budget.consumeStagnationWarning());
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.recordToolCalls(sameCall);
        assertTrue(!budget.consumeStagnationWarning());
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void requestLoopDetectionDoesNotDependOnToolResults() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);
        List<LlmClient.ToolCall> calls = List.of(toolCall("read_file", "{\"path\":\"a.txt\"}"));
        budget.recordToolCalls(calls);
        budget.recordToolCalls(calls);
        budget.recordToolCalls(calls);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
        assertTrue(budget.consumeStagnationWarning());
    }

    @Test
    void canonicalJsonArgumentsCountAsTheSameRequest() {
        AgentBudget budget = new AgentBudget(1_000_000, 2, 50);
        budget.recordToolCalls(List.of(toolCall("read_file", "{\"path\":\"a.txt\",\"offset\":1}")));
        budget.recordToolCalls(List.of(toolCall("read_file", "{ \"offset\" : 1, \"path\" : \"a.txt\" }")));

        assertTrue(budget.consumeStagnationWarning());
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void alternatingToolRequestCycleWarnsOnceWithoutTerminating() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);
        List<LlmClient.ToolCall> first = List.of(toolCall("grep_code", "{\"query\":\"Target\"}"));
        List<LlmClient.ToolCall> second = List.of(toolCall("read_file", "{\"path\":\"Target.java\"}"));

        for (int i = 0; i < 3; i++) {
            budget.recordToolCalls(first);
            budget.recordToolCalls(second);
        }

        assertTrue(budget.consumeStagnationWarning());
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
        budget.recordToolCalls(first);
        budget.recordToolCalls(second);
        assertTrue(!budget.consumeStagnationWarning());
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void hardIterationLimitTriggersAfterEnoughIterations() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 3);
        budget.beginIteration();
        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, budget.check());
    }

    @Test
    void tokenBudgetStillTerminatesARepeatedToolLoop() {
        AgentBudget budget = new AgentBudget(100, 2, 50);
        budget.recordTokens(200, 0);
        budget.recordToolCalls(List.of(toolCall("x", "{}")));
        budget.recordToolCalls(List.of(toolCall("x", "{}")));
        assertTrue(budget.consumeStagnationWarning());
        assertEquals(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED, budget.check());
    }

    @Test
    void configuredRunTimeoutTerminatesTheRun() throws Exception {
        String key = "mindcli.react.max.duration.seconds";
        String old = System.getProperty(key);
        try {
            System.setProperty(key, "1");
            AgentBudget budget = AgentBudget.fromLlmClient(new GLMClient("test-key"));

            Thread.sleep(1_100);

            assertEquals("RUN_TIMEOUT", budget.check().name());
        } finally {
            if (old == null) System.clearProperty(key);
            else System.setProperty(key, old);
        }
    }

    @Test
    void cumulativeTokenCountersDoNotOverflowIntRange() {
        AgentBudget budget = new AgentBudget(Integer.MAX_VALUE, 3, 50);
        budget.recordTokens(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertEquals(4_294_967_294L, budget.totalInputTokens() + budget.totalOutputTokens());
        assertEquals(2_147_483_647L, budget.totalCachedInputTokens());
    }

    @Test
    void invalidConstructorArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(0, 3, 50));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(100, 1, 50));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(100, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(100, 3, 50, -1));
    }

    @Test
    void describeExitContainsRelevantNumbers() {
        AgentBudget budget = new AgentBudget(100, 3, 50);
        budget.recordTokens(80, 40);
        String message = budget.describeExit(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED);
        assertTrue(message.contains("120"));
        assertTrue(message.contains("100"));
    }

    @Test
    void defaultTokenBudgetIsUnlimited() {
        // 默认不再用 80% × window 当硬限——长上下文 + 套餐用户场景下太容易撞墙。
        // 失控运行由总时限和 hardMaxIterations 兜底；循环检测只提示模型。
        AgentBudget budget = AgentBudget.fromLlmClient(new GLMClient("test-key"));

        assertEquals(Integer.MAX_VALUE, budget.tokenBudget());
    }

    @Test
    void systemPropertyCanStillOverrideDynamicTokenBudget() {
        String old = System.getProperty("mindcli.react.token.budget");
        try {
            System.setProperty("mindcli.react.token.budget", "12345");
            AgentBudget budget = AgentBudget.fromLlmClient(new GLMClient("test-key"));

            assertEquals(12345, budget.tokenBudget());
        } finally {
            if (old == null) {
                System.clearProperty("mindcli.react.token.budget");
            } else {
                System.setProperty("mindcli.react.token.budget", old);
            }
        }
    }

    @Test
    void projectDotEnvCanConfigureDynamicTokenBudget() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(project.resolve(".env"), "MINDCLI_REACT_TOKEN_BUDGET=12345\n");

        AgentBudget budget = AgentBudget.fromLlmClient(
                new GLMClient("test-key"),
                new ConfigValueResolver(project, home));

        assertEquals(12345, budget.tokenBudget());
    }

    private LlmClient.ToolCall toolCall(String name, String args) {
        return new LlmClient.ToolCall("call_" + name + "_" + args.hashCode(),
                new LlmClient.ToolCall.Function(name, args));
    }

}
