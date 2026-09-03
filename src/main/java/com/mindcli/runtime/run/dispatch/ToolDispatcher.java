package com.mindcli.runtime.run.dispatch;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import com.mindcli.platform.llm.LlmClient;
import com.mindcli.agent.profile.AgentToolPolicy;
import com.mindcli.capability.tool.ToolExecution;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.platform.hitl.ApprovalPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ToolDispatcher {
    private static final ResourceLockManager SHARED_LOCK_MANAGER = new ResourceLockManager();
    private static final int MAX_PARALLEL_TOOLS = 4;
    private static final long DEFAULT_BATCH_TIMEOUT_SECONDS = 90;

    private final ToolInvocationExecutor executor;
    private final ToolResourceClassifier resourceClassifier;
    private final ResourceLockManager lockManager;
    private final HookManager hookManager;
    private final long batchTimeoutSeconds;
    private final RunStore runStore;

    public ToolDispatcher(ToolRegistry toolRegistry) {
        this((ToolInvocationExecutor) invocation -> Objects.requireNonNull(toolRegistry, "toolRegistry")
                        .executeToolExecution(invocation.name(), invocation.argumentsJson()),
                new ToolResourceClassifier(),
                SHARED_LOCK_MANAGER,
                HookManager.noop(),
                toolRegistry.getToolBatchTimeoutSeconds(),
                null);
    }

    public ToolDispatcher(ToolRegistry toolRegistry, RunStore runStore) {
        this((ToolInvocationExecutor) invocation -> Objects.requireNonNull(toolRegistry, "toolRegistry")
                        .executeToolExecution(invocation.name(), invocation.argumentsJson()),
                new ToolResourceClassifier(),
                SHARED_LOCK_MANAGER,
                HookManager.noop(),
                toolRegistry.getToolBatchTimeoutSeconds(),
                runStore);
    }

    public ToolDispatcher(ToolInvocationExecutor executor) {
        this(executor, new ToolResourceClassifier(), SHARED_LOCK_MANAGER, HookManager.noop(),
                DEFAULT_BATCH_TIMEOUT_SECONDS, null);
    }

    public ToolDispatcher(ToolInvocationExecutor executor, RunStore runStore) {
        this(executor, new ToolResourceClassifier(), SHARED_LOCK_MANAGER, HookManager.noop(),
                DEFAULT_BATCH_TIMEOUT_SECONDS, runStore);
    }

    public ToolDispatcher(ToolInvocationExecutor executor,
                   ToolResourceClassifier resourceClassifier,
                   ResourceLockManager lockManager,
                   HookManager hookManager) {
        this(executor, resourceClassifier, lockManager, hookManager, DEFAULT_BATCH_TIMEOUT_SECONDS, null);
    }

    public ToolDispatcher(ToolInvocationExecutor executor,
                   ToolResourceClassifier resourceClassifier,
                   ResourceLockManager lockManager,
                   HookManager hookManager,
                   long batchTimeoutSeconds) {
        this(executor, resourceClassifier, lockManager, hookManager, batchTimeoutSeconds, null);
    }

    private ToolDispatcher(ToolInvocationExecutor executor,
                   ToolResourceClassifier resourceClassifier,
                   ResourceLockManager lockManager,
                   HookManager hookManager,
                   long batchTimeoutSeconds,
                   RunStore runStore) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.resourceClassifier = Objects.requireNonNull(resourceClassifier, "resourceClassifier");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.hookManager = hookManager == null ? HookManager.noop() : hookManager;
        this.batchTimeoutSeconds = Math.max(1, batchTimeoutSeconds);
        this.runStore = runStore;
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
            AgentToolPolicy.Decision policyDecision = AgentToolPolicy.evaluate(effectiveContext, effectiveInvocation);
            metadata.putAll(policyDecision.metadata());
            if (!policyDecision.allowed()) {
                ToolOutcome outcome = ToolOutcome.denied(effectiveInvocation, ToolOutcomeStatus.DENIED_BY_POLICY,
                        policyDecision.reason(), metadata);
                fireTerminalHook(effectiveContext, effectiveInvocation, outcome);
                outcomes.set(i, outcome);
                continue;
            }
            ToolOutcome replayed = replayedOutcome(effectiveContext, effectiveInvocation, metadata);
            if (replayed != null) {
                fireTerminalHook(effectiveContext, effectiveInvocation, replayed);
                outcomes.set(i, replayed);
                continue;
            }
            List<ResourceKey> resourceKeys = resourceClassifier.classify(effectiveInvocation, effectiveContext);
            metadata.put("lockKeys", formatResourceKeys(resourceKeys));
            prepared.add(new PreparedInvocation(i, effectiveInvocation, resourceKeys, metadata));
        }

        String approvalPolicy = effectiveContext.metadata().getOrDefault("approvalPolicy", "on-request");
        for (List<PreparedInvocation> batch : batches(prepared, approvalPolicy)) {
            executeBatch(batch, effectiveContext, approvalPolicy, outcomes);
        }

        return outcomes.stream()
                .map(outcome -> outcome == null
                        ? new ToolOutcome("", "", "", ToolOutcomeStatus.FAILED,
                        "", 0, "Tool dispatcher did not produce an outcome", List.of())
                        : outcome)
                .toList();
    }

    private void executeBatch(List<PreparedInvocation> batch, AgentRunContext context,
                              String approvalPolicy,
                              List<ToolOutcome> outcomes) {
        if (batch.isEmpty()) {
            return;
        }
        int parallelism = Math.min(batch.size(), MAX_PARALLEL_TOOLS);
        ExecutorService workers = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "mindcli-dispatch-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Callable<ToolExecution>> tasks = batch.stream()
                    .<Callable<ToolExecution>>map(prepared ->
                            () -> executeOne(prepared, approvalPolicy))
                    .toList();
            List<Future<ToolExecution>> futures =
                    workers.invokeAll(tasks, batchTimeoutSeconds, TimeUnit.SECONDS);

            for (int i = 0; i < batch.size(); i++) {
                PreparedInvocation prepared = batch.get(i);
                ToolOutcome outcome = outcomeFromFuture(prepared, futures.get(i));
                fireTerminalHook(context, prepared.invocation(), outcome);
                outcomes.set(prepared.index(), outcome);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (PreparedInvocation prepared : batch) {
                ToolOutcome outcome = new ToolOutcome(
                        prepared.invocation().id(), prepared.invocation().name(),
                        prepared.invocation().argumentsJson(), ToolOutcomeStatus.CANCELLED,
                        "工具执行已取消: 批次执行被中断", 0,
                        "批次执行被中断", "CANCELLED", List.of(), prepared.metadata());
                fireTerminalHook(context, prepared.invocation(), outcome);
                outcomes.set(prepared.index(), outcome);
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private ToolExecution executeOne(PreparedInvocation prepared,
                                     String approvalPolicy) throws Exception {
        ApprovalPolicy.applyApprovalPolicy(approvalPolicy);
        try (ResourceLockManager.LockLease ignored =
                     lockManager.acquireAllInterruptibly(prepared.resourceKeys())) {
            ToolExecution execution = executor.execute(prepared.invocation());
            if (execution == null) {
                throw new IllegalStateException("Tool executor returned null result");
            }
            return execution;
        } finally {
            ApprovalPolicy.clearApprovalPolicy();
        }
    }

    private ToolOutcome outcomeFromFuture(PreparedInvocation prepared,
                                          Future<ToolExecution> future) {
        if (future.isCancelled()) {
            return timedOut(prepared);
        }
        try {
            ToolExecution execution = future.get();
            ToolOutcomeStatus status = switch (execution.status()) {
                case COMPLETED -> ToolOutcomeStatus.COMPLETED;
                case PARTIAL -> ToolOutcomeStatus.PARTIAL;
                case DENIED_BY_POLICY -> ToolOutcomeStatus.DENIED_BY_POLICY;
                case DENIED_BY_USER -> ToolOutcomeStatus.DENIED_BY_USER;
                case TIMED_OUT -> ToolOutcomeStatus.TIMED_OUT;
                case CANCELLED -> ToolOutcomeStatus.CANCELLED;
                case FAILED -> ToolOutcomeStatus.FAILED;
            };
            String text = execution.output().text();
            String errorMessage = execution.errorMessage();
            if (errorMessage.isBlank() && status != ToolOutcomeStatus.COMPLETED
                    && status != ToolOutcomeStatus.PARTIAL) {
                errorMessage = text;
            }
            String category = execution.errorCategory();
            if (category.isBlank()) {
                category = switch (status) {
                    case DENIED_BY_POLICY -> "POLICY_DENIED";
                    case DENIED_BY_USER -> "USER_DENIED";
                    case TIMED_OUT -> "TIMEOUT";
                    case CANCELLED -> "CANCELLED";
                    case FAILED -> "TOOL_FAILED";
                    case PARTIAL -> "PARTIAL";
                    case COMPLETED -> "";
                };
            }
            return new ToolOutcome(prepared.invocation().id(), prepared.invocation().name(),
                    execution.effectiveArgumentsJson().isBlank()
                            ? prepared.invocation().argumentsJson() : execution.effectiveArgumentsJson(),
                    status, text, 0, errorMessage, category, execution.output().imageParts(),
                    prepared.metadata());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelled(prepared, "收集工具结果时被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause == null ? errorMessage(e) : errorMessage(cause);
            return ToolOutcome.failed(prepared.invocation(), message, prepared.metadata());
        }
    }

    private ToolOutcome timedOut(PreparedInvocation prepared) {
        String text = "工具执行超时（" + batchTimeoutSeconds + "秒），已取消";
        return new ToolOutcome(
                prepared.invocation().id(), prepared.invocation().name(),
                prepared.invocation().argumentsJson(), ToolOutcomeStatus.TIMED_OUT,
                text, TimeUnit.SECONDS.toMillis(batchTimeoutSeconds), text,
                "TIMEOUT", List.of(), prepared.metadata());
    }

    private static ToolOutcome cancelled(PreparedInvocation prepared, String reason) {
        return new ToolOutcome(
                prepared.invocation().id(), prepared.invocation().name(),
                prepared.invocation().argumentsJson(), ToolOutcomeStatus.CANCELLED,
                "工具执行已取消: " + reason, 0, reason,
                "CANCELLED", List.of(), prepared.metadata());
    }

    private void fireTerminalHook(AgentRunContext context, ToolRegistry.ToolInvocation invocation,
                                  ToolOutcome outcome) {
        HookType type = switch (outcome.status()) {
            case COMPLETED, PARTIAL -> HookType.POST_TOOL_USE;
            case DENIED_BY_POLICY, DENIED_BY_USER, TIMED_OUT, CANCELLED, FAILED -> HookType.TOOL_ERROR;
        };
        hookManager.fire(HookEvent.withOutcome(type, invocation, context, outcome));
    }

    private ToolOutcome replayedOutcome(AgentRunContext context,
                                         ToolRegistry.ToolInvocation invocation,
                                         Map<String, String> metadata) {
        if (runStore == null || context == null
                || !"true".equalsIgnoreCase(context.metadata().getOrDefault("resumed", "false"))
                || invocation == null || invocation.id() == null
                || invocation.id().isBlank()) {
            return null;
        }
        List<AgentRunEvent> events = runStore.events(context.runId());
        if (events == null) {
            return null;
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentRunEvent event = events.get(i);
            if (event == null || event.type() != AgentRunEventType.TOOL_OUTCOME) {
                continue;
            }
            Map<String, String> attributes = event.attributes();
            if (!invocation.id().equals(attributes.get("toolId"))) {
                continue;
            }
            boolean completed = ToolOutcomeStatus.COMPLETED.name().equalsIgnoreCase(attributes.get("status"));
            boolean exactMatch = completed
                    && invocation.name().equals(attributes.getOrDefault("toolName", ""))
                    && invocation.argumentsJson().equals(attributes.getOrDefault("argumentsJson", ""));
            if (!exactMatch) {
                Map<String, String> collisionMetadata = new LinkedHashMap<>();
                if (metadata != null) {
                    collisionMetadata.putAll(metadata);
                }
                collisionMetadata.put("idempotency", "collision");
                return new ToolOutcome(
                        invocation.id(), invocation.name(), invocation.argumentsJson(),
                        ToolOutcomeStatus.FAILED,
                        "恢复时发现 toolCallId 已对应不同的工具调用，已阻止重复执行",
                        0,
                        "toolCallId 与历史调用不匹配",
                        "IDEMPOTENCY_KEY_COLLISION",
                        List.of(), collisionMetadata);
            }
            Map<String, String> replayMetadata = new LinkedHashMap<>();
            if (metadata != null) {
                replayMetadata.putAll(metadata);
            }
            replayMetadata.put("idempotency", "replayed");
            return new ToolOutcome(
                    invocation.id(), invocation.name(), invocation.argumentsJson(),
                    ToolOutcomeStatus.COMPLETED,
                    attributes.getOrDefault("text", ""),
                    parseLong(attributes.get("elapsedMillis")),
                    attributes.getOrDefault("errorMessage", ""),
                    attributes.getOrDefault("errorCategory", ""),
                    List.of(), replayMetadata);
        }
        return null;
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
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

    private static List<List<PreparedInvocation>> batches(List<PreparedInvocation> prepared,
                                                          String approvalPolicy) {
        List<List<PreparedInvocation>> batches = new ArrayList<>();
        List<PreparedInvocation> current = new ArrayList<>();
        for (PreparedInvocation invocation : prepared) {
            if (ApprovalPolicy.requiresApproval(invocation.invocation().name(), approvalPolicy)) {
                if (!current.isEmpty()) {
                    batches.add(List.copyOf(current));
                    current.clear();
                }
                batches.add(List.of(invocation));
                continue;
            }
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

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
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
