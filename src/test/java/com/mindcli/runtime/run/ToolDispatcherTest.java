package com.mindcli.runtime.run;

import com.mindcli.platform.llm.LlmClient;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.platform.hitl.ApprovalPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDispatcherTest {

    @Test
    void dispatchPreservesToolCallOrderAndArguments() {
        Map<String, ToolRegistry.ToolInvocation> seen = new java.util.concurrent.ConcurrentHashMap<>();
        ToolDispatcher dispatcher = new ToolDispatcher(invocations -> {
            invocations.forEach(invocation -> seen.put(invocation.id(), invocation));
            return invocations.stream()
                    .map(invocation -> new ToolRegistry.ToolExecutionResult(
                            invocation.id(), invocation.name(), invocation.argumentsJson(),
                            "ok:" + invocation.name(), 5, false, List.of()))
                    .toList();
        });

        List<ToolOutcome> outcomes = dispatcher.dispatch(List.of(
                toolCall("call_1", "read_file", "{\"path\":\"a.txt\"}"),
                toolCall("call_2", "grep_code", "{\"query\":\"Agent\"}")
        ));

        assertEquals(List.of("call_1", "call_2"), outcomes.stream().map(ToolOutcome::id).toList());
        assertEquals("{\"path\":\"a.txt\"}", seen.get("call_1").argumentsJson());
        assertEquals("{\"query\":\"Agent\"}", seen.get("call_2").argumentsJson());
        assertEquals("ok:grep_code", outcomes.get(1).text());
    }

    @Test
    void dispatchPropagatesApprovalPolicyToToolWorkersAndSerializesApprovalPrompts() {
        List<Boolean> approvalDecisions = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                approvalDecisions.add(ApprovalPolicy.requiresApproval(name));
                int current = active.incrementAndGet();
                peak.updateAndGet(previous -> Math.max(previous, current));
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
                return "ok:" + name;
            }
        };
        ToolDispatcher dispatcher = new ToolDispatcher(registry);
        AgentRunContext context = AgentRunContext.create(
                AgentMode.REACT, "test", "workspace", Map.of("approvalPolicy", "untrusted"));

        List<ToolOutcome> outcomes = dispatcher.dispatch(List.of(
                toolCall("call_1", "read_file", "{\"path\":\"a.txt\"}"),
                toolCall("call_2", "read_file", "{\"path\":\"b.txt\"}")), context);

        assertEquals(List.of(true, true), approvalDecisions);
        assertEquals(1, peak.get(), "calls that require interactive approval must not prompt concurrently");
        assertEquals(List.of("call_1", "call_2"), outcomes.stream().map(ToolOutcome::id).toList());
    }

    @Test
    void timedOutToolKeepsItsResourceLockUntilWorkerActuallyStops() throws Exception {
        CountDownLatch stubbornToolStarted = new CountDownLatch(1);
        CountDownLatch stubbornToolFinished = new CountDownLatch(1);
        CountDownLatch secondWriteEntered = new CountDownLatch(1);
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if (!"write_file".equals(name)) {
                    return "fast";
                }
                stubbornToolStarted.countDown();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_800);
                while (System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        // Reproduce a third-party or external tool that does not cooperate with cancellation.
                    }
                }
                stubbornToolFinished.countDown();
                return "stopped";
            }
        };
        setToolBatchTimeoutSeconds(registry, 1);
        ToolDispatcher first = new ToolDispatcher(registry);
        ToolDispatcher second = new ToolDispatcher(invocations -> {
            secondWriteEntered.countDown();
            return completed(invocations, "second");
        });
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "test", "workspace");
        ExecutorService callers = Executors.newFixedThreadPool(2);

        try {
            Future<List<ToolOutcome>> firstFuture = callers.submit(() -> first.dispatch(List.of(
                    toolCall("call_1", "write_file", "{\"path\":\"shared.txt\",\"content\":\"a\"}"),
                    toolCall("call_2", "read_file", "{\"path\":\"other.txt\"}")), context));
            assertTrue(stubbornToolStarted.await(1, TimeUnit.SECONDS));
            assertEquals(ToolOutcomeStatus.TIMED_OUT,
                    firstFuture.get(2, TimeUnit.SECONDS).get(0).status());
            assertEquals(1, stubbornToolFinished.getCount(), "the timed-out worker should still be running");

            Future<List<ToolOutcome>> secondFuture = callers.submit(() -> second.dispatch(List.of(
                    toolCall("call_3", "write_file", "{\"path\":\"shared.txt\",\"content\":\"b\"}")), context));

            assertFalse(secondWriteEntered.await(250, TimeUnit.MILLISECONDS),
                    "a timed-out worker must retain its lock until its code really exits");
            assertTrue(stubbornToolFinished.await(2, TimeUnit.SECONDS));
            assertEquals(ToolOutcomeStatus.COMPLETED,
                    secondFuture.get(1, TimeUnit.SECONDS).get(0).status());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void dispatchReturnsFailedOutcomeForEachToolCallWhenRegistryThrows() {
        ToolDispatcher dispatcher = new ToolDispatcher(invocations -> {
            throw new IllegalStateException("registry down");
        });

        List<ToolOutcome> outcomes = dispatcher.dispatch(List.of(
                toolCall("call_1", "read_file", "{}"),
                toolCall("call_2", "list_dir", "{}")
        ));

        assertEquals(2, outcomes.size());
        assertEquals(List.of(ToolOutcomeStatus.FAILED, ToolOutcomeStatus.FAILED),
                outcomes.stream().map(ToolOutcome::status).toList());
        assertEquals("registry down", outcomes.get(0).errorMessage());
        assertEquals("call_2", outcomes.get(1).id());
    }

    @Test
    void dispatchEmptyToolCallsReturnsEmptyList() {
        ToolDispatcher dispatcher = new ToolDispatcher(invocations -> {
            throw new AssertionError("should not call registry");
        });

        assertEquals(List.of(), dispatcher.dispatch(List.of()));
    }

    @Test
    void contextDispatchReturnsDeniedOutcomeWhenPreHookRejects() {
        AtomicBoolean executorCalled = new AtomicBoolean(false);
        ToolDispatcher dispatcher = dispatcher(invocations -> {
            executorCalled.set(true);
            return List.of();
        }, new HookManager(List.of(event -> HookDecision.denyByPolicy("blocked by policy"))));

        List<ToolOutcome> outcomes = dispatcher.dispatch(List.of(
                toolCall("call_1", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}")
        ), AgentRunContext.create(AgentMode.REACT, "test", "workspace"));

        assertEquals(1, outcomes.size());
        assertEquals(ToolOutcomeStatus.DENIED_BY_POLICY, outcomes.get(0).status());
        assertEquals("blocked by policy", outcomes.get(0).errorMessage());
        assertEquals("DENY_BY_POLICY", outcomes.get(0).metadata().get("hookDecision"));
        assertFalse(executorCalled.get());
    }

    @Test
    void contextDispatchReturnsDeniedOutcomeWhenProfilePolicyRejects() {
        AtomicBoolean executorCalled = new AtomicBoolean(false);
        ToolDispatcher dispatcher = dispatcher(invocations -> {
            executorCalled.set(true);
            return List.of();
        }, new HookManager(List.of(event -> HookDecision.allow())));
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "test", "workspace", java.util.Map.of(
                "profileName", "code-reader",
                "profileRole", "WORKER",
                "permissionMode", "READ_ONLY",
                "allowedTools", "read_file"));

        List<ToolOutcome> outcomes = dispatcher.dispatch(List.of(
                toolCall("call_1", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}")
        ), context);

        assertEquals(1, outcomes.size());
        assertEquals(ToolOutcomeStatus.DENIED_BY_POLICY, outcomes.get(0).status());
        assertEquals("code-reader", outcomes.get(0).metadata().get("profileName"));
        assertEquals("DENY", outcomes.get(0).metadata().get("policyDecision"));
        assertTrue(outcomes.get(0).metadata().get("policyReason").contains("read-only"));
        assertFalse(executorCalled.get());
    }

    @Test
    void preHookDenialsFireTerminalErrorHook() {
        List<HookType> seenTypes = new ArrayList<>();
        HookManager hookManager = new HookManager(List.of(event -> {
            seenTypes.add(event.type());
            if (event.type() == HookType.PRE_TOOL_USE) {
                return HookDecision.denyByPolicy("blocked by policy");
            }
            return HookDecision.allow();
        }));
        ToolDispatcher dispatcher = dispatcher(invocations -> {
            throw new AssertionError("executor should not run");
        }, hookManager);

        dispatcher.dispatch(List.of(
                toolCall("call_1", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}")
        ), AgentRunContext.create(AgentMode.REACT, "test", "workspace"));

        assertEquals(List.of(HookType.PRE_TOOL_USE, HookType.TOOL_ERROR), seenTypes);
    }

    @Test
    void contextDispatchUsesModifiedArgumentsFromPreHook() {
        List<ToolRegistry.ToolInvocation> seen = new ArrayList<>();
        ToolDispatcher dispatcher = dispatcher(invocations -> {
            seen.addAll(invocations);
            return invocations.stream()
                    .map(invocation -> new ToolRegistry.ToolExecutionResult(
                            invocation.id(), invocation.name(), invocation.argumentsJson(),
                            "ok", 1, false, List.of()))
                    .toList();
        }, new HookManager(List.of(event -> HookDecision.modifyArguments("{\"path\":\"safe.txt\"}"))));

        List<ToolOutcome> outcomes = dispatcher.dispatch(List.of(
                toolCall("call_1", "read_file", "{\"path\":\"unsafe.txt\"}")
        ), AgentRunContext.create(AgentMode.REACT, "test", "workspace"));

        assertEquals("{\"path\":\"safe.txt\"}", seen.get(0).argumentsJson());
        assertEquals("{\"path\":\"safe.txt\"}", outcomes.get(0).argumentsJson());
        assertEquals("MODIFY_ARGUMENTS", outcomes.get(0).metadata().get("hookDecision"));
    }

    @Test
    void contextDispatchKeepsOriginalOrderWhenSomeToolsAreDenied() {
        ToolDispatcher dispatcher = dispatcher(invocations -> invocations.stream()
                .map(invocation -> new ToolRegistry.ToolExecutionResult(
                        invocation.id(), invocation.name(), invocation.argumentsJson(),
                        "ok:" + invocation.name(), 1, false, List.of()))
                .toList(), new HookManager(List.of(event -> {
            if ("write_file".equals(event.invocation().name())) {
                return HookDecision.denyByUser("not now");
            }
            return HookDecision.allow();
        })));

        List<ToolOutcome> outcomes = dispatcher.dispatch(List.of(
                toolCall("call_1", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}"),
                toolCall("call_2", "read_file", "{\"path\":\"a.txt\"}")
        ), AgentRunContext.create(AgentMode.REACT, "test", "workspace"));

        assertEquals(List.of("call_1", "call_2"), outcomes.stream().map(ToolOutcome::id).toList());
        assertEquals(ToolOutcomeStatus.DENIED_BY_USER, outcomes.get(0).status());
        assertEquals(ToolOutcomeStatus.COMPLETED, outcomes.get(1).status());
        assertEquals("ok:read_file", outcomes.get(1).text());
    }

    @Test
    void defaultDispatchersShareResourceLocksAcrossInstances() throws Exception {
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "test", "workspace");
        CountDownLatch firstToolEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstTool = new CountDownLatch(1);
        CountDownLatch secondToolEntered = new CountDownLatch(1);
        ToolDispatcher first = new ToolDispatcher(invocations -> {
            firstToolEntered.countDown();
            await(releaseFirstTool);
            return completed(invocations, "first");
        });
        ToolDispatcher second = new ToolDispatcher(invocations -> {
            secondToolEntered.countDown();
            return completed(invocations, "second");
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<List<ToolOutcome>> firstFuture = executor.submit(() ->
                    first.dispatch(List.of(toolCall("call_1", "write_file",
                            "{\"path\":\"shared.txt\",\"content\":\"a\"}")), context));
            assertTrue(firstToolEntered.await(1, TimeUnit.SECONDS));

            Future<List<ToolOutcome>> secondFuture = executor.submit(() ->
                    second.dispatch(List.of(toolCall("call_2", "write_file",
                            "{\"path\":\"shared.txt\",\"content\":\"b\"}")), context));

            assertFalse(secondToolEntered.await(250, TimeUnit.MILLISECONDS),
                    "a second dispatcher must not enter the same exclusive resource while the first holds it");
            releaseFirstTool.countDown();

            assertEquals(ToolOutcomeStatus.COMPLETED, firstFuture.get(1, TimeUnit.SECONDS).get(0).status());
            assertEquals(ToolOutcomeStatus.COMPLETED, secondFuture.get(1, TimeUnit.SECONDS).get(0).status());
            assertTrue(secondToolEntered.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirstTool.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void defaultDispatchersLockFilesUnderExclusiveDirectoryLocks() throws Exception {
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "test", "workspace");
        CountDownLatch directoryToolEntered = new CountDownLatch(1);
        CountDownLatch releaseDirectoryTool = new CountDownLatch(1);
        CountDownLatch fileToolEntered = new CountDownLatch(1);
        ToolDispatcher directoryDispatcher = new ToolDispatcher(invocations -> {
            directoryToolEntered.countDown();
            await(releaseDirectoryTool);
            return completed(invocations, "directory");
        });
        ToolDispatcher fileDispatcher = new ToolDispatcher(invocations -> {
            fileToolEntered.countDown();
            return completed(invocations, "file");
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<List<ToolOutcome>> directoryFuture = executor.submit(() ->
                    directoryDispatcher.dispatch(List.of(toolCall("call_1", "create_project",
                            "{\"name\":\"app\"}")), context));
            assertTrue(directoryToolEntered.await(1, TimeUnit.SECONDS));

            Future<List<ToolOutcome>> fileFuture = executor.submit(() ->
                    fileDispatcher.dispatch(List.of(toolCall("call_2", "write_file",
                            "{\"path\":\"app/README.md\",\"content\":\"hello\"}")), context));

            assertFalse(fileToolEntered.await(250, TimeUnit.MILLISECONDS),
                    "file writes below an exclusive directory lock must wait");
            releaseDirectoryTool.countDown();

            assertEquals(ToolOutcomeStatus.COMPLETED, directoryFuture.get(1, TimeUnit.SECONDS).get(0).status());
            assertEquals(ToolOutcomeStatus.COMPLETED, fileFuture.get(1, TimeUnit.SECONDS).get(0).status());
            assertTrue(fileToolEntered.await(1, TimeUnit.SECONDS));
        } finally {
            releaseDirectoryTool.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void listDirSerializesWithWritesBelowThatDirectory() throws Exception {
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, "test", "workspace");
        CountDownLatch listEntered = new CountDownLatch(1);
        CountDownLatch releaseList = new CountDownLatch(1);
        CountDownLatch writeEntered = new CountDownLatch(1);
        ToolDispatcher listDispatcher = new ToolDispatcher(invocations -> {
            listEntered.countDown();
            await(releaseList);
            return completed(invocations, "list");
        });
        ToolDispatcher writeDispatcher = new ToolDispatcher(invocations -> {
            writeEntered.countDown();
            return completed(invocations, "write");
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<List<ToolOutcome>> listFuture = executor.submit(() ->
                    listDispatcher.dispatch(List.of(toolCall("call_1", "list_dir",
                            "{\"path\":\"src\"}")), context));
            assertTrue(listEntered.await(1, TimeUnit.SECONDS));

            Future<List<ToolOutcome>> writeFuture = executor.submit(() ->
                    writeDispatcher.dispatch(List.of(toolCall("call_2", "write_file",
                            "{\"path\":\"src/A.java\",\"content\":\"class A {}\"}")), context));

            assertFalse(writeEntered.await(250, TimeUnit.MILLISECONDS));
            releaseList.countDown();

            assertEquals(ToolOutcomeStatus.COMPLETED, listFuture.get(1, TimeUnit.SECONDS).get(0).status());
            assertEquals(ToolOutcomeStatus.COMPLETED, writeFuture.get(1, TimeUnit.SECONDS).get(0).status());
        } finally {
            releaseList.countDown();
            executor.shutdownNow();
        }
    }

    private static ToolDispatcher dispatcher(ToolBatchExecutor executor, HookManager hookManager) {
        return new ToolDispatcher(
                executor,
                new ToolResourceClassifier(),
                new ResourceLockManager(),
                hookManager);
    }

    private static LlmClient.ToolCall toolCall(String id, String name, String args) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, args));
    }

    private static List<ToolRegistry.ToolExecutionResult> completed(List<ToolRegistry.ToolInvocation> invocations,
                                                                    String prefix) {
        return invocations.stream()
                .map(invocation -> new ToolRegistry.ToolExecutionResult(
                        invocation.id(), invocation.name(), invocation.argumentsJson(),
                        prefix + ":" + invocation.name(), 1, false, List.of()))
                .toList();
    }

    private static void setToolBatchTimeoutSeconds(ToolRegistry registry, long seconds) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField("toolBatchTimeoutSeconds");
        field.setAccessible(true);
        field.setLong(registry, seconds);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
