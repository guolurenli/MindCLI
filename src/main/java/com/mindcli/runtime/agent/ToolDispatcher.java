package com.mindcli.runtime.agent;

import com.mindcli.llm.LlmClient;
import com.mindcli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ToolDispatcher {
    private static final ResourceLockManager SHARED_LOCK_MANAGER = new ResourceLockManager();

    private final ToolBatchExecutor executor;
    private final ToolResourceClassifier resourceClassifier;
    private final ResourceLockManager lockManager;
    private final HookManager hookManager;

    public ToolDispatcher(ToolRegistry toolRegistry) {
        this(Objects.requireNonNull(toolRegistry, "toolRegistry")::executeTools);
    }

    ToolDispatcher(ToolBatchExecutor executor) {
        this(executor, new ToolResourceClassifier(), SHARED_LOCK_MANAGER, HookManager.noop());
    }

    ToolDispatcher(ToolBatchExecutor executor,
                   ToolResourceClassifier resourceClassifier,
                   ResourceLockManager lockManager,
                   HookManager hookManager) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.resourceClassifier = Objects.requireNonNull(resourceClassifier, "resourceClassifier");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.hookManager = hookManager == null ? HookManager.noop() : hookManager;
    }

    public List<ToolOutcome> dispatch(List<LlmClient.ToolCall> toolCalls) {
        return dispatch(toolCalls, AgentRunContext.create(
                AgentMode.REACT,
                "",
                System.getProperty("user.dir", "")));
    }

    public List<ToolOutcome> dispatch(List<LlmClient.ToolCall> toolCalls, AgentRunContext context) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<ToolRegistry.ToolInvocation> invocations = toolCalls.stream()
                .map(ToolDispatcher::toInvocation)
                .toList();
        return dispatchInvocations(invocations, context);
    }

    public List<ToolOutcome> dispatchInvocations(List<ToolRegistry.ToolInvocation> invocations,
                                                 AgentRunContext context) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        AgentRunContext effectiveContext = context == null
                ? AgentRunContext.create(AgentMode.REACT, "", System.getProperty("user.dir", ""))
                : context;
        List<ToolOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < invocations.size(); i++) {
            outcomes.add(null);
        }

        List<PreparedInvocation> prepared = new ArrayList<>();
        for (int i = 0; i < invocations.size(); i++) {
            ToolRegistry.ToolInvocation invocation = invocations.get(i);
            HookDecision decision = hookManager.fire(HookEvent.of(HookType.PRE_TOOL_USE, invocation, effectiveContext));
            Map<String, String> metadata = baseMetadata(effectiveContext, i, decision);
            if (decision.type() == HookDecisionType.DENY_BY_POLICY) {
                ToolOutcome outcome = ToolOutcome.denied(invocation, ToolOutcomeStatus.DENIED_BY_POLICY,
                        decision.reason(), metadata);
                fireTerminalHook(effectiveContext, invocation, outcome);
                outcomes.set(i, outcome);
                continue;
            }
            if (decision.type() == HookDecisionType.DENY_BY_USER) {
                ToolOutcome outcome = ToolOutcome.denied(invocation, ToolOutcomeStatus.DENIED_BY_USER,
                        decision.reason(), metadata);
                fireTerminalHook(effectiveContext, invocation, outcome);
                outcomes.set(i, outcome);
                continue;
            }
            ToolRegistry.ToolInvocation effectiveInvocation = invocation;
            if (decision.type() == HookDecisionType.MODIFY_ARGUMENTS) {
                effectiveInvocation = new ToolRegistry.ToolInvocation(
                        invocation.id(), invocation.name(), decision.effectiveArgumentsJson());
            }
            List<ResourceKey> resourceKeys = resourceClassifier.classify(effectiveInvocation, effectiveContext);
            metadata.put("lockKeys", formatResourceKeys(resourceKeys));
            prepared.add(new PreparedInvocation(i, effectiveInvocation, resourceKeys, metadata));
        }

        for (List<PreparedInvocation> batch : batches(prepared)) {
            executeBatch(batch, effectiveContext, outcomes);
        }

        return outcomes.stream()
                .map(outcome -> outcome == null
                        ? new ToolOutcome("", "", "", ToolOutcomeStatus.FAILED,
                        "", 0, "Tool dispatcher did not produce an outcome", List.of())
                        : outcome)
                .toList();
    }

    private void executeBatch(List<PreparedInvocation> batch, AgentRunContext context,
                              List<ToolOutcome> outcomes) {
        if (batch.isEmpty()) {
            return;
        }
        List<ResourceKey> batchKeys = batch.stream()
                .flatMap(prepared -> prepared.resourceKeys().stream())
                .toList();
        try {
            List<ToolRegistry.ToolExecutionResult> results;
            try (ResourceLockManager.LockLease ignored = lockManager.acquireAll(batchKeys)) {
                results = executor.execute(batch.stream()
                        .map(PreparedInvocation::invocation)
                        .toList());
            }
            if (results == null) {
                for (PreparedInvocation prepared : batch) {
                    ToolOutcome outcome = ToolOutcome.failed(
                            prepared.invocation(),
                            "Tool registry returned null result list",
                            prepared.metadata());
                    fireTerminalHook(context, prepared.invocation(), outcome);
                    outcomes.set(prepared.index(), outcome);
                }
                return;
            }
            for (int i = 0; i < batch.size(); i++) {
                PreparedInvocation prepared = batch.get(i);
                ToolOutcome outcome;
                if (i >= results.size()) {
                    outcome = ToolOutcome.failed(prepared.invocation(),
                            "Tool registry returned too few results",
                            prepared.metadata());
                } else {
                    outcome = ToolOutcome.fromLegacy(results.get(i))
                            .withArgumentsJson(prepared.invocation().argumentsJson())
                            .withMetadata(prepared.metadata());
                }
                fireTerminalHook(context, prepared.invocation(), outcome);
                outcomes.set(prepared.index(), outcome);
            }
        } catch (Exception e) {
            for (PreparedInvocation prepared : batch) {
                ToolOutcome outcome = ToolOutcome.failed(prepared.invocation(), errorMessage(e), prepared.metadata());
                hookManager.fire(HookEvent.withError(prepared.invocation(), context, e));
                outcomes.set(prepared.index(), outcome);
            }
        }
    }

    private void fireTerminalHook(AgentRunContext context, ToolRegistry.ToolInvocation invocation,
                                  ToolOutcome outcome) {
        HookType type = switch (outcome.status()) {
            case COMPLETED, PARTIAL -> HookType.POST_TOOL_USE;
            case DENIED_BY_POLICY, DENIED_BY_USER, TIMED_OUT, CANCELLED, FAILED -> HookType.TOOL_ERROR;
        };
        hookManager.fire(HookEvent.withOutcome(type, invocation, context, outcome));
    }

    private static Map<String, String> baseMetadata(AgentRunContext context, int index, HookDecision decision) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("runId", context.runId());
        metadata.put("mode", context.mode().name());
        metadata.put("batchIndex", String.valueOf(index));
        metadata.put("hookDecision", decision.type().name());
        metadata.putAll(decision.metadata());
        if (!decision.reason().isBlank()) {
            metadata.put("deniedReason", decision.reason());
        }
        return metadata;
    }

    private static List<List<PreparedInvocation>> batches(List<PreparedInvocation> prepared) {
        List<List<PreparedInvocation>> batches = new ArrayList<>();
        List<PreparedInvocation> current = new ArrayList<>();
        for (PreparedInvocation invocation : prepared) {
            if (!current.isEmpty() && conflicts(current, invocation)) {
                batches.add(List.copyOf(current));
                current.clear();
            }
            current.add(invocation);
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }

    private static boolean conflicts(List<PreparedInvocation> batch, PreparedInvocation candidate) {
        for (PreparedInvocation existing : batch) {
            for (ResourceKey left : existing.resourceKeys()) {
                for (ResourceKey right : candidate.resourceKeys()) {
                    if (left.conflictsWith(right)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ToolRegistry.ToolInvocation toInvocation(LlmClient.ToolCall toolCall) {
        LlmClient.ToolCall.Function function = toolCall.function();
        return new ToolRegistry.ToolInvocation(
                toolCall.id(),
                function == null ? "" : function.name(),
                function == null ? "" : function.arguments());
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String formatResourceKeys(List<ResourceKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return "";
        }
        return String.join(",", keys.stream()
                .sorted()
                .map(ResourceKey::toString)
                .toList());
    }

    private record PreparedInvocation(
            int index,
            ToolRegistry.ToolInvocation invocation,
            List<ResourceKey> resourceKeys,
            Map<String, String> metadata
    ) {
        private PreparedInvocation {
            resourceKeys = resourceKeys == null ? List.of() : List.copyOf(resourceKeys);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
