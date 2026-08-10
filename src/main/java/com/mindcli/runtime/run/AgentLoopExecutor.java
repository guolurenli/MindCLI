package com.mindcli.runtime.run;

import com.mindcli.agent.AgentBudget;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.LlmRetryPolicy;
import com.mindcli.runtime.CancellationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentLoopExecutor {
    private final LlmClient llmClient;
    private final ToolDispatcher toolDispatcher;
    private final RunStore runStore;

    public AgentLoopExecutor(LlmClient llmClient, ToolDispatcher toolDispatcher, RunStore runStore) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.toolDispatcher = Objects.requireNonNull(toolDispatcher, "toolDispatcher");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
    }

    public AgentLoopResult execute(AgentLoopContext context) {
        Objects.requireNonNull(context, "context");
        AgentBudget budget = context.budget();
        List<ToolOutcome> allToolOutcomes = new ArrayList<>();
        StringBuilder reasoningTranscript = new StringBuilder();

        while (true) {
            if (CancellationContext.isCancelled()) {
                append(context, AgentRunEventType.RUN_CANCELLED);
                return AgentLoopResult.cancelled(budget, allToolOutcomes);
            }

            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String description = budget.describeExit(exitReason);
                append(context, AgentRunEventType.BUDGET_EXHAUSTED, Map.of(
                        "reason", exitReason.name(),
                        "description", description));
                return AgentLoopResult.budgetExhausted(description, budget, allToolOutcomes);
            }

            int iteration = budget.beginIteration();
            context.observer().beforeIteration(iteration, context.messages(), context.effectiveTools());

            LlmClient.ChatResponse response;
            try {
                response = LlmRetryPolicy.withRetry(() ->
                        llmClient.chat(
                                context.messages(),
                                context.effectiveTools(),
                                context.streamListener()),
                        context.policy().traceName());
            } catch (Exception e) {
                return AgentLoopResult.failed(errorMessage(e), budget, allToolOutcomes);
            }

            context.observer().afterLlmResponse(iteration, response);
            appendLlmResponseEvent(context, iteration, response);
            if (CancellationContext.isCancelled()) {
                append(context, AgentRunEventType.RUN_CANCELLED);
                return AgentLoopResult.cancelled(budget, allToolOutcomes);
            }

            budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

            if (!response.hasToolCalls()) {
                appendReasoning(reasoningTranscript, response.reasoningContent());
                context.messages().add(LlmClient.Message.assistant(response.content()));
                return AgentLoopResult.completed(
                        response.content(),
                        reasoningTranscript.toString(),
                        budget,
                        allToolOutcomes);
            }

            appendReasoning(reasoningTranscript, response.reasoningContent());
            budget.recordToolCalls(response.toolCalls());
            context.messages().add(LlmClient.Message.assistant(
                    response.reasoningContent(),
                    response.content(),
                    response.toolCalls()));

            append(context, AgentRunEventType.TOOL_CALL_REQUESTED, Map.of(
                    "iteration", String.valueOf(iteration),
                    "toolCallCount", String.valueOf(response.toolCalls().size()),
                    "toolNames", toolNames(response.toolCalls())));
            context.observer().beforeToolDispatch(iteration, response.toolCalls());

            List<ToolOutcome> outcomes = toolDispatcher.dispatch(response.toolCalls(), context.runContext());
            allToolOutcomes.addAll(outcomes);
            for (ToolOutcome outcome : outcomes) {
                appendToolOutcomeEvent(context, iteration, outcome);
                context.messages().add(outcome.toToolMessage());
            }
            appendImageToolMessages(context.messages(), outcomes);
            context.observer().afterToolDispatch(iteration, outcomes);
        }
    }

    private void appendLlmResponseEvent(AgentLoopContext context, int iteration, LlmClient.ChatResponse response) {
        append(context, AgentRunEventType.LLM_RESPONSE, Map.of(
                "iteration", String.valueOf(iteration),
                "inputTokens", String.valueOf(response.inputTokens()),
                "outputTokens", String.valueOf(response.outputTokens()),
                "cachedInputTokens", String.valueOf(response.cachedInputTokens()),
                "toolCallCount", String.valueOf(response.toolCalls() == null ? 0 : response.toolCalls().size()),
                "contentChars", String.valueOf(response.content() == null ? 0 : response.content().length()),
                "reasoningChars", String.valueOf(response.reasoningContent() == null ? 0 : response.reasoningContent().length())));
    }

    private void appendToolOutcomeEvent(AgentLoopContext context, int iteration, ToolOutcome outcome) {
        append(context, AgentRunEventType.TOOL_OUTCOME,
                ToolOutcomeEventFactory.attributes(outcome, Map.of("iteration", String.valueOf(iteration))));
    }

    private void append(AgentLoopContext context, AgentRunEventType type) {
        append(context, type, Map.of());
    }

    private void append(AgentLoopContext context, AgentRunEventType type, Map<String, String> attributes) {
        runStore.append(AgentRunEvent.of(context.runContext(), type, attributes));
    }

    private static void appendReasoning(StringBuilder transcript, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        if (!transcript.isEmpty()) {
            transcript.append("\n\n");
        }
        transcript.append(reasoningContent.trim());
    }

    private static void appendImageToolMessages(List<LlmClient.Message> messages, List<ToolOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return;
        }
        for (ToolOutcome outcome : outcomes) {
            if (!outcome.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + outcome.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(outcome.imageParts());
            messages.add(LlmClient.Message.user(parts));
        }
    }

    private static String toolNames(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "";
        }
        return String.join(",", toolCalls.stream()
                .map(toolCall -> toolCall.function() == null ? "" : toolCall.function().name())
                .toList());
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
