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
        AgentBudget.ExitReason exitReason = RunDeadline.isExpired(context.runContext())
                ? AgentBudget.ExitReason.RUN_TIMEOUT : budget.check();
        if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
            return new AgentTurnResult(AgentTurnStatus.BUDGET_EXHAUSTED, budget.iteration(), null, List.of(), "",
                    exitReason == AgentBudget.ExitReason.RUN_TIMEOUT ? RunDeadline.DESCRIPTION
                            : budget.describeExit(exitReason), exitReason);
        }

        int iteration = budget.beginIteration();
        context.observer().beforeIteration(iteration, context.messages(), context.effectiveTools());
        LlmClient.ChatResponse response;
        try {
            response = LlmRetryPolicy.withRetry(() -> {
                RunDeadline.requireActive(context.runContext());
                return llmClient.chat(context.messages(), context.effectiveTools(), context.streamListener());
            },
                    context.policy().traceName());
        } catch (Exception e) {
            if (RunDeadline.isExpired(context.runContext())) {
                return deadlineExhausted(budget, null);
            }
            return new AgentTurnResult(AgentTurnStatus.FAILED, iteration, null, List.of(), errorMessage(e), "",
                    AgentBudget.ExitReason.WITHIN_BUDGET);
        }
        context.observer().afterLlmResponse(iteration, response);
        if (CancellationContext.isCancelled()) {
            return new AgentTurnResult(AgentTurnStatus.CANCELLED, iteration, response, List.of(), "", "",
                    AgentBudget.ExitReason.WITHIN_BUDGET);
        }
        budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
        if (RunDeadline.isExpired(context.runContext())) {
            return deadlineExhausted(budget, response);
        }
        if (!response.hasToolCalls()) {
            context.messages().add(LlmClient.Message.assistant(response.content()));
            return new AgentTurnResult(AgentTurnStatus.COMPLETED, iteration, response, List.of(), "", "",
                    AgentBudget.ExitReason.WITHIN_BUDGET);
        }

        context.messages().add(LlmClient.Message.assistant(
                response.reasoningContent(), response.content(), response.toolCalls()));
        context.observer().beforeToolDispatch(iteration, response.toolCalls());
        if (RunDeadline.isExpired(context.runContext())) {
            return deadlineExhausted(budget, response);
        }
        List<ToolOutcome> outcomes = toolBatchExecutor.dispatch(response.toolCalls(), context.runContext());
        for (ToolOutcome outcome : outcomes) {
            context.messages().add(outcome.toToolMessage());
        }
        appendImageToolMessages(context.messages(), outcomes);
        budget.recordToolCalls(response.toolCalls());
        if (budget.consumeStagnationWarning()) {
            context.messages().add(LlmClient.Message.user(
                    "[运行时提示] 检测到工具调用模式正在重复。请检查是否取得实际进展；必要时调整参数、采用其他方法，或明确说明受阻原因。"));
        }
        context.observer().afterToolDispatch(iteration, outcomes);
        return new AgentTurnResult(AgentTurnStatus.TOOL_CALLS, iteration, response, outcomes, "", "",
                AgentBudget.ExitReason.WITHIN_BUDGET);
    }

    private static AgentTurnResult deadlineExhausted(AgentBudget budget, LlmClient.ChatResponse response) {
        return new AgentTurnResult(AgentTurnStatus.BUDGET_EXHAUSTED, budget.iteration(), response, List.of(), "",
                RunDeadline.DESCRIPTION, AgentBudget.ExitReason.RUN_TIMEOUT);
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
