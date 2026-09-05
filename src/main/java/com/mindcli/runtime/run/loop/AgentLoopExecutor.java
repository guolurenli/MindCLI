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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.mindcli.platform.serialization.JsonSupport;

public final class AgentLoopExecutor {
    private final AgentTurnKernel turnKernel;
    private final RunStore runStore;

    public AgentLoopExecutor(LlmClient llmClient, ToolDispatcher toolDispatcher, RunStore runStore) {
        this.turnKernel = new AgentTurnKernel(
                Objects.requireNonNull(llmClient, "llmClient"),
                Objects.requireNonNull(toolDispatcher, "toolDispatcher"));
        this.runStore = Objects.requireNonNull(runStore, "runStore");
    }

    public AgentLoopResult execute(AgentLoopContext context) {
        Objects.requireNonNull(context, "context");
        AgentBudget budget = context.budget();
        List<ToolOutcome> allToolOutcomes = new ArrayList<>();
        StringBuilder reasoningTranscript = new StringBuilder();

        while (true) {
            AgentTurnResult turn = turnKernel.run(new AgentTurnContext(
                    context.runContext(), context.messages(), context.tools(), context.policy(),
                    context.budget(), context.streamListener(), context.observer()));
            if (turn.response() != null) {
                appendLlmResponseEvent(context, turn.iteration(), turn.response());
            }
            if (turn.status() == AgentTurnStatus.CANCELLED) {
                append(context, AgentRunEventType.RUN_CANCELLED);
                return AgentLoopResult.cancelled(budget, allToolOutcomes);
            }
            if (turn.status() == AgentTurnStatus.BUDGET_EXHAUSTED) {
                append(context, AgentRunEventType.BUDGET_EXHAUSTED, Map.of(
                        "reason", turn.exitReason().name(),
                        "description", turn.exitDescription()));
                return AgentLoopResult.budgetExhausted(turn.exitDescription(), budget, allToolOutcomes);
            }
            if (turn.status() == AgentTurnStatus.FAILED) {
                return AgentLoopResult.failed(turn.errorMessage(), budget, allToolOutcomes);
            }
            appendReasoning(reasoningTranscript, turn.response() == null ? "" : turn.response().reasoningContent());
            if (turn.completed()) {
                return AgentLoopResult.completed(
                        turn.response() == null ? "" : turn.response().content(),
                        reasoningTranscript.toString(),
                        budget,
                        allToolOutcomes);
            }
            append(context, AgentRunEventType.TOOL_CALL_REQUESTED, Map.of(
                    "iteration", String.valueOf(turn.iteration()),
                    "toolCallCount", String.valueOf(turn.response().toolCalls().size()),
                    "toolNames", toolNames(turn.response().toolCalls())));
            allToolOutcomes.addAll(turn.toolOutcomes());
            for (ToolOutcome outcome : turn.toolOutcomes()) {
                appendToolOutcomeEvent(context, turn.iteration(), outcome);
            }
        }
    }

    private void appendLlmResponseEvent(AgentLoopContext context, int iteration, LlmClient.ChatResponse response) {
        Map<String, String> attributes = new java.util.LinkedHashMap<>(Map.of(
                "iteration", String.valueOf(iteration),
                "inputTokens", String.valueOf(response.inputTokens()),
                "outputTokens", String.valueOf(response.outputTokens()),
                "cachedInputTokens", String.valueOf(response.cachedInputTokens()),
                "toolCallCount", String.valueOf(response.toolCalls() == null ? 0 : response.toolCalls().size()),
                "contentChars", String.valueOf(response.content() == null ? 0 : response.content().length()),
                "reasoningChars", String.valueOf(response.reasoningContent() == null ? 0 : response.reasoningContent().length()),
                "content", response.content() == null ? "" : response.content(),
                "reasoningContent", response.reasoningContent() == null ? "" : response.reasoningContent(),
                "toolCallsJson", serializeToolCalls(response.toolCalls())));
        append(context, AgentRunEventType.LLM_RESPONSE, attributes);
    }

    private void appendToolOutcomeEvent(AgentLoopContext context, int iteration, ToolOutcome outcome) {
        Map<String, String> attributes = new java.util.LinkedHashMap<>(
                ToolOutcomeEventFactory.attributes(outcome, Map.of("iteration", String.valueOf(iteration))));
        if (outcome != null) {
            attributes.put("argumentsJson", outcome.argumentsJson());
            attributes.put("text", outcome.text());
        }
        append(context, AgentRunEventType.TOOL_OUTCOME, attributes);
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

    private static String toolNames(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "";
        }
        return String.join(",", toolCalls.stream()
                .map(toolCall -> toolCall.function() == null ? "" : toolCall.function().name())
                .toList());
    }

    private static String serializeToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "[]";
        }
        try {
            return JsonSupport.mapper().writeValueAsString(toolCalls);
        } catch (Exception ignored) {
            return "[]";
        }
    }

}
