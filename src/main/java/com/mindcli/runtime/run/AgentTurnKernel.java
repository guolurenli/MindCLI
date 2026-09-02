package com.mindcli.runtime.run;

import com.mindcli.agent.AgentBudget;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.LlmRetryPolicy;
import com.mindcli.runtime.CancellationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Executes one LLM turn and, when requested, its complete tool batch. */
public final class AgentTurnKernel {
    private final LlmClient llmClient;
    private final ToolBatchExecutor toolBatchExecutor;

    public AgentTurnKernel(LlmClient llmClient, ToolDispatcher toolDispatcher) {
        this(llmClient, dispatcherOf(toolDispatcher));
    }

    public AgentTurnKernel(LlmClient llmClient, ToolBatchExecutor toolBatchExecutor) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.toolBatchExecutor = Objects.requireNonNull(toolBatchExecutor, "toolBatchExecutor");
    }

    private static ToolBatchExecutor dispatcherOf(ToolDispatcher dispatcher) {
        ToolDispatcher checked = Objects.requireNonNull(dispatcher, "toolDispatcher");
        return checked::dispatch;
    }

    public AgentTurnResult run(AgentTurnContext context) {
        Objects.requireNonNull(context, "context");
        AgentBudget budget = context.budget();
        if (CancellationContext.isCancelled()) {
            return new AgentTurnResult(AgentTurnStatus.CANCELLED, budget.iteration(), null, List.of(), "", "",
                    AgentBudget.ExitReason.WITHIN_BUDGET);
        }
        AgentBudget.ExitReason exitReason = budget.check();
        if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
            return new AgentTurnResult(AgentTurnStatus.BUDGET_EXHAUSTED, budget.iteration(), null, List.of(), "",
                    budget.describeExit(exitReason), exitReason);
        }

        int iteration = budget.beginIteration();
        context.observer().beforeIteration(iteration, context.messages(), context.effectiveTools());
        LlmClient.ChatResponse response;
        try {
            response = LlmRetryPolicy.withRetry(() -> llmClient.chat(
                    context.messages(), context.effectiveTools(), context.streamListener()),
                    context.policy().traceName());
        } catch (Exception e) {
            return new AgentTurnResult(AgentTurnStatus.FAILED, iteration, null, List.of(), errorMessage(e), "",
                    AgentBudget.ExitReason.WITHIN_BUDGET);
        }
        context.observer().afterLlmResponse(iteration, response);
        if (CancellationContext.isCancelled()) {
            return new AgentTurnResult(AgentTurnStatus.CANCELLED, iteration, response, List.of(), "", "",
                    AgentBudget.ExitReason.WITHIN_BUDGET);
        }
        budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
        if (!response.hasToolCalls()) {
            context.messages().add(LlmClient.Message.assistant(response.content()));
            return new AgentTurnResult(AgentTurnStatus.COMPLETED, iteration, response, List.of(), "", "",
                    AgentBudget.ExitReason.WITHIN_BUDGET);
        }

        budget.recordToolCalls(response.toolCalls());
        context.messages().add(LlmClient.Message.assistant(
                response.reasoningContent(), response.content(), response.toolCalls()));
        context.observer().beforeToolDispatch(iteration, response.toolCalls());
        List<ToolOutcome> outcomes = toolBatchExecutor.dispatch(response.toolCalls(), context.runContext());
        for (ToolOutcome outcome : outcomes) {
            context.messages().add(outcome.toToolMessage());
        }
        appendImageToolMessages(context.messages(), outcomes);
        context.observer().afterToolDispatch(iteration, outcomes);
        return new AgentTurnResult(AgentTurnStatus.TOOL_CALLS, iteration, response, outcomes, "", "",
                AgentBudget.ExitReason.WITHIN_BUDGET);
    }

    private static void appendImageToolMessages(List<LlmClient.Message> messages, List<ToolOutcome> outcomes) {
        if (outcomes == null) return;
        for (ToolOutcome outcome : outcomes) {
            if (!outcome.hasImageParts()) continue;
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + outcome.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(outcome.imageParts());
            messages.add(LlmClient.Message.user(parts));
        }
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
