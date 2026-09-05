package com.mindcli.runtime.run.loop;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import com.mindcli.agent.AgentBudget;

import java.util.List;

public record AgentLoopResult(
        AgentLoopStatus status,
        String content,
        String reasoningContent,
        String errorMessage,
        String exitDescription,
        int inputTokens,
        int outputTokens,
        int cachedInputTokens,
        List<ToolOutcome> toolOutcomes
) {
    public AgentLoopResult {
        status = status == null ? AgentLoopStatus.FAILED : status;
        content = content == null ? "" : content;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        errorMessage = errorMessage == null ? "" : errorMessage;
        exitDescription = exitDescription == null ? "" : exitDescription;
        toolOutcomes = toolOutcomes == null ? List.of() : List.copyOf(toolOutcomes);
    }

    public static AgentLoopResult completed(String content, String reasoningContent,
                                            AgentBudget budget, List<ToolOutcome> toolOutcomes) {
        return new AgentLoopResult(
                AgentLoopStatus.COMPLETED,
                content,
                reasoningContent,
                "",
                "",
                budget.totalInputTokens(),
                budget.totalOutputTokens(),
                budget.totalCachedInputTokens(),
                toolOutcomes);
    }

    public static AgentLoopResult failed(String errorMessage, AgentBudget budget, List<ToolOutcome> toolOutcomes) {
        return new AgentLoopResult(
                AgentLoopStatus.FAILED,
                "",
                "",
                errorMessage,
                "",
                budget.totalInputTokens(),
                budget.totalOutputTokens(),
                budget.totalCachedInputTokens(),
                toolOutcomes);
    }

    public static AgentLoopResult cancelled(AgentBudget budget, List<ToolOutcome> toolOutcomes) {
        return new AgentLoopResult(
                AgentLoopStatus.CANCELLED,
                "⏹️ 已取消当前任务。",
                "",
                "",
                "",
                budget.totalInputTokens(),
                budget.totalOutputTokens(),
                budget.totalCachedInputTokens(),
                toolOutcomes);
    }

    public static AgentLoopResult budgetExhausted(String description, AgentBudget budget,
                                                  List<ToolOutcome> toolOutcomes) {
        return new AgentLoopResult(
                AgentLoopStatus.BUDGET_EXHAUSTED,
                "",
                "",
                "",
                description,
                budget.totalInputTokens(),
                budget.totalOutputTokens(),
                budget.totalCachedInputTokens(),
                toolOutcomes);
    }
}
