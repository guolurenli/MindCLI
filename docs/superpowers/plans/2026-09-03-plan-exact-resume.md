# Plan Exact Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/run resume <runId>` restore Plan mode at a safe task boundary from the existing JSONL ledger, without replanning or rerunning completed tasks.

**Architecture:** Add immutable recovery-side Plan records and one JSON codec, then project the latest `PLAN_DEFINED` plus later `PLAN_TASK_CHECKPOINT` events into a `PlanResumeState`. `PlanExecuteAgent` maps that state back to a fresh `ExecutionPlan` and reuses the existing DAG executor; `AgentRuntime` selects this path before writing `RUN_RESUMED`. The JSONL ledger remains the only persisted source of truth.

**Tech Stack:** Java 17 records, Jackson through `JsonSupport`, Maven, JUnit 5, existing `RunStore` / `JsonlRunStore`, existing Plan DAG executor.

## Global Constraints

- Implement Plan task-boundary recovery only; Team exact recovery remains out of scope.
- Reuse the recorded DAG and skip `Planner.createPlan` plus Plan review during recovery.
- Preserve `COMPLETED` and `SKIPPED`; retry safe historical `RUNNING` tasks as `PENDING`.
- Refuse recovery when a nonterminal task has a successful write, command, MCP, or unknown-side-effect outcome.
- Do not add a database, workflow engine, second state file, old-ledger inference, network test, or test-only production API.
- Keep ReAct recovery behavior unchanged.
- Update `README.md`, `AGENTS.md`, and `ROADMAP.md` for the delivered behavior.
- Preserve unrelated untracked files, `.env`, credentials, and `target/` artifacts.

---

### Task 1: Immutable Plan Checkpoint Model and Codec

**Files:**
- Create: `src/main/java/com/mindcli/runtime/run/recovery/PlanTaskResumeState.java`
- Create: `src/main/java/com/mindcli/runtime/run/recovery/PlanResumeState.java`
- Create: `src/main/java/com/mindcli/runtime/run/recovery/PlanCheckpointCodec.java`
- Create: `src/test/java/com/mindcli/runtime/run/recovery/PlanCheckpointCodecTest.java`

**Interfaces:**
- Consumes: `com.mindcli.platform.serialization.JsonSupport.mapper()`.
- Produces: `String PlanCheckpointCodec.encode(PlanResumeState state)` and `PlanResumeState PlanCheckpointCodec.decode(String planJson)`.
- Produces: immutable task fields `id`, `description`, `type`, `dependencies`, `critical`, `maxRetries`, `degradation`, `expectedEvidence`, `requiredTools`, `preferredAgent`, `riskLevel`, `status`, `result`, `error`, and `retryCount`.

- [ ] **Step 1: Write the failing codec tests**

```java
package com.mindcli.runtime.run.recovery;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanCheckpointCodecTest {
    private final PlanCheckpointCodec codec = new PlanCheckpointCodec();

    @Test
    void roundTripsEveryPlanAndTaskField() {
        PlanResumeState state = new PlanResumeState(true, 2, "plan-1", "goal", "summary", List.of(
                new PlanTaskResumeState("task_1", "write file", "FILE_WRITE", List.of(), false, 1,
                        "SKIP", List.of("file exists"), List.of("write_file"), "worker", "high",
                        "COMPLETED", "done", "", 1)), "");

        PlanResumeState decoded = codec.decode(codec.encode(state));

        assertTrue(decoded.available());
        assertEquals(state, decoded);
    }

    @Test
    void rejectsDuplicateTaskIdsAndMissingDependencies() {
        assertFalse(codec.decode("""
                {"planVersion":1,"planId":"p","goal":"g","summary":"","tasks":[
                  {"id":"a","description":"a","type":"ANALYSIS","dependencies":[],"critical":true,
                   "maxRetries":0,"degradation":"REPLAN","expectedEvidence":[],"requiredTools":[],
                   "preferredAgent":"","riskLevel":"low","status":"PENDING","result":"","error":"","retryCount":0},
                  {"id":"a","description":"b","type":"ANALYSIS","dependencies":["missing"],"critical":true,
                   "maxRetries":0,"degradation":"REPLAN","expectedEvidence":[],"requiredTools":[],
                   "preferredAgent":"","riskLevel":"low","status":"PENDING","result":"","error":"","retryCount":0}
                ]}
                """).available());
    }

    @Test
    void rejectsUnknownStatusAndCyclicDag() {
        String invalid = """
                {"planVersion":1,"planId":"p","goal":"g","summary":"","tasks":[
                  {"id":"a","description":"a","type":"ANALYSIS","dependencies":["b"],"critical":true,
                   "maxRetries":0,"degradation":"REPLAN","expectedEvidence":[],"requiredTools":[],
                   "preferredAgent":"","riskLevel":"low","status":"UNKNOWN","result":"","error":"","retryCount":0},
                  {"id":"b","description":"b","type":"ANALYSIS","dependencies":["a"],"critical":true,
                   "maxRetries":0,"degradation":"REPLAN","expectedEvidence":[],"requiredTools":[],
                   "preferredAgent":"","riskLevel":"low","status":"PENDING","result":"","error":"","retryCount":0}
                ]}
                """;

        PlanResumeState decoded = codec.decode(invalid);

        assertFalse(decoded.available());
        assertFalse(decoded.reason().isBlank());
    }
}
```

- [ ] **Step 2: Run the codec test and verify RED**

Run: `mvn test -Dtest=PlanCheckpointCodecTest -DskipTests=false`

Expected: compilation fails because `PlanCheckpointCodec`, `PlanResumeState`, and `PlanTaskResumeState` do not exist.

- [ ] **Step 3: Add the immutable records and validating codec**

```java
public record PlanTaskResumeState(
        String id, String description, String type, List<String> dependencies,
        boolean critical, int maxRetries, String degradation,
        List<String> expectedEvidence, List<String> requiredTools,
        String preferredAgent, String riskLevel, String status,
        String result, String error, int retryCount) {
    public PlanTaskResumeState {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        result = result == null ? "" : result;
        error = error == null ? "" : error;
    }
}
```

```java
public record PlanResumeState(boolean available, int planVersion, String planId, String goal,
                              String summary, List<PlanTaskResumeState> tasks, String reason) {
    public PlanResumeState {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        summary = summary == null ? "" : summary;
        reason = reason == null ? "" : reason;
    }

    public static PlanResumeState unavailable(String reason) {
        return new PlanResumeState(false, 0, "", "", "", List.of(), reason);
    }
}
```

Implement `PlanCheckpointCodec` with Jackson object/array nodes, explicit required-field reads, accepted task types `PLANNING`, `FILE_READ`, `FILE_WRITE`, `COMMAND`, `ANALYSIS`, `VERIFICATION`, accepted statuses `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `SKIPPED`, nonnegative retries, unique IDs, dependency existence, and a Kahn topological validation. `decode` catches malformed JSON and returns `PlanResumeState.unavailable("Plan checkpoint 损坏: " + message)`; `encode` rejects unavailable or invalid states with `IllegalArgumentException` and emits fields in the record order.

- [ ] **Step 4: Run the codec test and verify GREEN**

Run: `mvn test -Dtest=PlanCheckpointCodecTest -DskipTests=false`

Expected: 3 tests pass, 0 failures, 0 errors.

- [ ] **Step 5: Commit the checkpoint model**

```bash
git add src/main/java/com/mindcli/runtime/run/recovery/PlanTaskResumeState.java src/main/java/com/mindcli/runtime/run/recovery/PlanResumeState.java src/main/java/com/mindcli/runtime/run/recovery/PlanCheckpointCodec.java src/test/java/com/mindcli/runtime/run/recovery/PlanCheckpointCodecTest.java
git commit -m "feat: add plan checkpoint codec"
```

### Task 2: Ledger Projection and Safe Resume Classification

**Files:**
- Modify: `src/main/java/com/mindcli/runtime/run/AgentRunEventType.java`
- Modify: `src/main/java/com/mindcli/runtime/run/recovery/RunRecoveryService.java`
- Modify: `src/test/java/com/mindcli/runtime/run/recovery/RunRecoveryServiceTest.java`

**Interfaces:**
- Consumes: `PlanCheckpointCodec.decode(String)` and `AgentRunEvent.seq()` order.
- Produces: `PlanResumeState RunRecoveryService.reconstructPlanState(String runId)`.
- Produces events `PLAN_DEFINED` and `PLAN_TASK_CHECKPOINT`.

- [ ] **Step 1: Write failing projection tests**

```java
@Test
void reconstructsLatestPlanVersionAndAppliesItsLastTaskCheckpoint() {
    InMemoryRunStore store = new InMemoryRunStore();
    AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", "workspace");
    store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED, Map.of("input", "goal")));
    appendPlanDefinition(store, context, 1, state(1, "old", "PENDING"));
    appendTaskCheckpoint(store, context, 1, "task_1", "COMPLETED", "old result");
    appendPlanDefinition(store, context, 2, state(2, "new", "PENDING"));
    appendTaskCheckpoint(store, context, 2, "task_1", "RUNNING", "");
    store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

    PlanResumeState restored = new RunRecoveryService(store).reconstructPlanState(context.runId());

    assertTrue(restored.available());
    assertEquals(2, restored.planVersion());
    assertEquals("new", restored.summary());
    assertEquals("PENDING", restored.tasks().get(0).status());
}

@Test
void rejectsSuccessfulSideEffectWithoutTerminalTaskCheckpoint() {
    InMemoryRunStore store = new InMemoryRunStore();
    AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", "workspace");
    store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED, Map.of("input", "goal")));
    appendPlanDefinition(store, context, 1, state(1, "plan", "RUNNING"));
    store.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, Map.of(
            "taskId", "task_1", "toolId", "write_1", "toolName", "write_file",
            "status", "COMPLETED", "text", "written")));
    store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

    RunRecoveryPlan inspected = new RunRecoveryService(store).inspect(context.runId());

    assertFalse(inspected.resumeAvailable());
    assertTrue(inspected.resumePlan().reason().contains("副作用"));
}

@Test
void rejectsPlanCheckpointThatReferencesUnknownTaskOrWrongVersion() {
    InMemoryRunStore store = new InMemoryRunStore();
    AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", "workspace");
    store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED, Map.of("input", "goal")));
    appendPlanDefinition(store, context, 1, state(1, "plan", "PENDING"));
    appendTaskCheckpoint(store, context, 2, "missing", "COMPLETED", "done");
    store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));

    assertFalse(new RunRecoveryService(store).reconstructPlanState(context.runId()).available());
}
```

- [ ] **Step 2: Run the projection tests and verify RED**

Run: `mvn test -Dtest=RunRecoveryServiceTest -DskipTests=false`

Expected: compilation fails because the event constants and `reconstructPlanState` are missing.

- [ ] **Step 3: Implement event projection and Plan-specific classification**

Add to `AgentRunEventType`:

```java
PLAN_DEFINED,
PLAN_TASK_CHECKPOINT,
```

Add to `RunRecoveryService`:

```java
public PlanResumeState reconstructPlanState(String runId) {
    List<AgentRunEvent> events = runStore.events(runId);
    int definitionIndex = lastIndexOf(events, AgentRunEventType.PLAN_DEFINED);
    if (definitionIndex < 0) return PlanResumeState.unavailable("旧 Plan run 缺少精确恢复 checkpoint");
    AgentRunEvent definition = events.get(definitionIndex);
    Integer version = parsePositiveInt(definition.attributes().get("planVersion"));
    PlanResumeState base = new PlanCheckpointCodec().decode(definition.attributes().get("planJson"));
    if (version == null || !base.available() || base.planVersion() != version) {
        return PlanResumeState.unavailable(base.available() ? "Plan checkpoint 版本不一致" : base.reason());
    }
    // Copy tasks by ID, apply only later checkpoints of the same version, reject an unknown task,
    // illegal status, malformed retry count, or any different-version checkpoint.
    // After all events are applied, reject successful side-effect outcomes attached to a task
    // whose final status is not COMPLETED or SKIPPED; then map safe RUNNING tasks to PENDING.
}
```

Use a read-only allowlist for ambiguity checks: `read_file`, `list_dir`, `glob_files`, `grep_code`, `web_search`, `web_fetch`, `search_memory`, and `read_memory`. Treat `write_file`, `create_project`, `save_memory`, `revert_turn`, `execute_command`, all `mcp__*`, blank tool names, and every unknown tool as side-effecting. Only `TOOL_OUTCOME` with status `COMPLETED` creates the ambiguous-success window.

In `inspect`, when mode is `PLAN`, call `reconstructPlanState`. Set `resumeAvailable=false` and return `risk=UNKNOWN` with its exact reason when unavailable. When available, ignore generic incomplete tool-call counts (a task-boundary retry can safely replace incomplete read-only work), but retain `risk=HIGH` plus confirmation for any completed side-effect outcome belonging to terminal tasks.

- [ ] **Step 4: Run recovery tests and verify GREEN**

Run: `mvn test -Dtest=PlanCheckpointCodecTest,RunRecoveryServiceTest -DskipTests=false`

Expected: all selected tests pass, including existing ReAct reconstruction tests.

- [ ] **Step 5: Commit ledger projection**

```bash
git add src/main/java/com/mindcli/runtime/run/AgentRunEventType.java src/main/java/com/mindcli/runtime/run/recovery/RunRecoveryService.java src/test/java/com/mindcli/runtime/run/recovery/RunRecoveryServiceTest.java
git commit -m "feat: reconstruct plan state from run ledger"
```

### Task 3: Emit Plan Checkpoints and Resume the Existing DAG

**Files:**
- Modify: `src/main/java/com/mindcli/agent/PlanExecuteAgent.java`
- Modify: `src/test/java/com/mindcli/agent/PlanExecuteAgentTest.java`

**Interfaces:**
- Consumes: `PlanResumeState` and `PlanTaskResumeState`.
- Produces: `String PlanExecuteAgent.runRecovered(AgentRunContext context, RunStore runStore, PlanResumeState state)`.
- Emits: one `PLAN_DEFINED` after approval and after each successful replan merge; one `PLAN_TASK_CHECKPOINT` immediately after every task state transition.

- [ ] **Step 1: Write failing Plan agent tests**

```java
@Test
void recordsDefinitionAndTerminalCheckpointAroundTaskExecution() {
    InMemoryRunStore store = new InMemoryRunStore();
    StubGLMClient llm = StubGLMClient.streaming(List.of(StubResponse.streamed(
            new LlmClient.ChatResponse("assistant", "done", null, 10, 5))));
    PlanExecuteAgent agent = planAgent(llm, store);
    AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", tempDir.toString());

    agent.run(context, store);

    assertEquals(List.of(AgentRunEventType.PLAN_DEFINED,
                         AgentRunEventType.PLAN_TASK_CHECKPOINT,
                         AgentRunEventType.PLAN_TASK_CHECKPOINT),
            store.events(context.runId()).stream()
                    .map(AgentRunEvent::type)
                    .filter(type -> type == AgentRunEventType.PLAN_DEFINED
                            || type == AgentRunEventType.PLAN_TASK_CHECKPOINT)
                    .toList());
    assertEquals(List.of("RUNNING", "COMPLETED"), store.events(context.runId()).stream()
            .filter(event -> event.type() == AgentRunEventType.PLAN_TASK_CHECKPOINT)
            .map(event -> event.attributes().get("taskStatus")).toList());
}

@Test
void recoveredPlanSkipsCompletedTaskAndDoesNotInvokePlannerOrReview() {
    AtomicInteger plannerCalls = new AtomicInteger();
    AtomicInteger reviewCalls = new AtomicInteger();
    InMemoryRunStore store = new InMemoryRunStore();
    PlanExecuteAgent agent = recoveredAgent(plannerCalls, reviewCalls, store);
    PlanResumeState state = twoTaskState("COMPLETED", "PENDING");
    AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, "goal", tempDir.toString());

    String result = agent.runRecovered(context, store, state);

    assertTrue(result.startsWith("✅"));
    assertEquals(0, plannerCalls.get());
    assertEquals(0, reviewCalls.get());
    assertEquals(List.of("task_2"), store.events(context.runId()).stream()
            .filter(event -> event.type() == AgentRunEventType.PLAN_TASK_CHECKPOINT)
            .filter(event -> "RUNNING".equals(event.attributes().get("taskStatus")))
            .map(event -> event.attributes().get("taskId")).toList());
}
```

- [ ] **Step 2: Run Plan agent tests and verify RED**

Run: `mvn test -Dtest=PlanExecuteAgentTest -DskipTests=false`

Expected: compilation fails because `runRecovered` does not exist, then behavior assertions fail until checkpoints are emitted.

- [ ] **Step 3: Add mapping, checkpoint emission, and recovered execution**

Add the public entry point:

```java
public String runRecovered(AgentRunContext context, RunStore runStore, PlanResumeState state) {
    Objects.requireNonNull(state, "state");
    if (!state.available()) throw new IllegalArgumentException(state.reason());
    return runInternal(context, runStore == null ? this.runStore : runStore, false, state);
}
```

Refactor the existing private `runInternal` into an overload accepting a nullable recovered state. For a fresh run, retain planner and review behavior; after review approval append `PLAN_DEFINED` version 1 before calling `executePlan`. For a recovered run, map every recovery task into a new `Task`, restore all governance and result fields, compute the DAG order, reject invalid dependencies/cycles, and call `executePlan` directly with the recorded version.

Use these event helpers:

```java
private void appendPlanDefinition(ExecutionPlan plan, int version, String reason) {
    PlanResumeState state = toResumeState(plan, version);
    appendRunEvent(activeRunContext, AgentRunEventType.PLAN_DEFINED, Map.of(
            "planVersion", Integer.toString(version),
            "reason", reason,
            "planJson", planCheckpointCodec.encode(state)));
}

private void appendTaskCheckpoint(Task task, int version) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("planVersion", Integer.toString(version));
    attributes.put("taskId", task.getId());
    attributes.put("taskStatus", task.getStatus().name());
    attributes.put("result", Objects.toString(task.getResult(), ""));
    attributes.put("error", Objects.toString(task.getError(), ""));
    attributes.put("retryCount", Integer.toString(task.getRetryCount()));
    appendRunEvent(activeRunContext, AgentRunEventType.PLAN_TASK_CHECKPOINT, attributes);
}
```

Call `appendTaskCheckpoint` immediately after `markStarted`, `markCompleted`, `incrementRetry` plus `resetToPending`, `markSkipped`, and every `markFailed`. After `mergeSubtree`, increment the local plan version and append a full `PLAN_DEFINED` with reason `REPLAN`. Pass the plan version into `executeTaskBatch` so both sequential and parallel starts are checkpointed by the coordinator thread before LLM work begins.

- [ ] **Step 4: Run Plan tests and verify GREEN**

Run: `mvn test -Dtest=PlanExecuteAgentTest,ExecutionPlanTest -DskipTests=false`

Expected: all selected tests pass; existing Plan output, retry, skip, block, replan, dispatcher, and DAG behavior remains green.

- [ ] **Step 5: Commit Plan execution changes**

```bash
git add src/main/java/com/mindcli/agent/PlanExecuteAgent.java src/test/java/com/mindcli/agent/PlanExecuteAgentTest.java
git commit -m "feat: checkpoint and resume plan tasks"
```

### Task 4: Runtime, Adapter, and CLI Integration

**Files:**
- Modify: `src/main/java/com/mindcli/runtime/run/mode/PlanModeAdapter.java`
- Modify: `src/main/java/com/mindcli/runtime/run/AgentRuntime.java`
- Modify: `src/main/java/com/mindcli/app/cli/runtime/CliRunResumer.java`
- Modify: `src/main/java/com/mindcli/app/cli/command/RunCommandHandler.java`
- Modify: `src/test/java/com/mindcli/runtime/run/mode/ModeAdapterTest.java`
- Modify: `src/test/java/com/mindcli/runtime/run/mode/AgentRuntimeTest.java`
- Modify: `src/test/java/com/mindcli/app/cli/runtime/CliRunResumerTest.java`
- Modify: `src/test/java/com/mindcli/app/cli/MainCommandHandlerRefactorTest.java`

**Interfaces:**
- Consumes: `RunRecoveryService.reconstructPlanState(String)`.
- Produces: `AgentRunResult PlanModeAdapter.executeRecovered(AgentRunContext, RunStore, PlanResumeState)`.
- Guarantees: no `RUN_RESUMED` event is appended before Plan reconstruction and safety validation succeed.

- [ ] **Step 1: Write failing integration tests**

```java
@Test
void runtimeUsesRecoveredPlanPathBeforeAppendingResumeMarker() {
    InMemoryRunStore store = resumablePlanStore();
    AgentRunContext context = recordedPlanContext(store);
    RecordingPlanExecuteAgent agent = new RecordingPlanExecuteAgent();

    AgentRunResult result = new AgentRuntime(store).resume(context.runId(), new PlanModeAdapter(agent));

    assertEquals(AgentRunStatus.SUCCESS, result.status());
    assertTrue(agent.recoveredCalled);
    assertFalse(agent.normalCalled);
    assertEquals(1, store.events(context.runId()).stream()
            .filter(event -> event.type() == AgentRunEventType.RUN_RESUMED).count());
}

@Test
void runtimeDoesNotAppendResumeMarkerForLegacyPlanLedger() {
    InMemoryRunStore store = legacyResumablePlanStoreWithoutDefinition();
    AgentRunContext context = recordedPlanContext(store);

    AgentRunResult result = new AgentRuntime(store).resume(context.runId(), planAdapter());

    assertEquals(AgentRunStatus.FAILED, result.status());
    assertTrue(store.events(context.runId()).stream()
            .noneMatch(event -> event.type() == AgentRunEventType.RUN_RESUMED));
}
```

Add CLI assertions that both command-level refusal and `CliRunResumer` return `resumePlan.reason()` containing `旧 Plan run 缺少精确恢复 checkpoint` instead of saying input/workspace is missing.

- [ ] **Step 2: Run integration tests and verify RED**

Run: `mvn test -Dtest=ModeAdapterTest,AgentRuntimeTest,CliRunResumerTest,MainCommandHandlerRefactorTest -DskipTests=false`

Expected: tests fail because Plan uses normal execution and CLI emits the old generic message.

- [ ] **Step 3: Route Plan resume and expose precise errors**

Store the concrete agent in the public `PlanModeAdapter(PlanExecuteAgent)` constructor while retaining package-private legacy runner constructors for existing tests. Add:

```java
public AgentRunResult executeRecovered(AgentRunContext context, RunStore runStore, PlanResumeState state) {
    if (agent == null) return AgentRunResult.failed(context, "Plan adapter 不支持 checkpoint 恢复");
    try {
        return resultFromContent(context, agent.runRecovered(context, runStore, state));
    } catch (Exception e) {
        return AgentRunResult.failed(context, errorMessage(e));
    }
}
```

In `AgentRuntime.resumeLocked`, reconstruct `PlanResumeState` for `PlanModeAdapter` before appending `RUN_RESUMED`; return its exact reason on failure. After validation, dispatch ReAct through `executeRecovered(...messages())`, Plan through `executeRecovered(...state)`, and Team through the existing `execute` path.

In both CLI classes, use:

```java
String reason = plan.resumePlan() == null ? "" : plan.resumePlan().reason();
return "❌ 无法恢复: " + (reason == null || reason.isBlank()
        ? (plan.resumable() ? "历史 run 缺少原始输入或工作区信息" : plan.stateStatus())
        : reason);
```

- [ ] **Step 4: Run integration tests and verify GREEN**

Run: `mvn test -Dtest=ModeAdapterTest,AgentRuntimeTest,CliRunResumerTest,MainCommandHandlerRefactorTest -DskipTests=false`

Expected: all selected tests pass, including all existing ReAct resume cases.

- [ ] **Step 5: Commit runtime integration**

```bash
git add src/main/java/com/mindcli/runtime/run/mode/PlanModeAdapter.java src/main/java/com/mindcli/runtime/run/AgentRuntime.java src/main/java/com/mindcli/app/cli/runtime/CliRunResumer.java src/main/java/com/mindcli/app/cli/command/RunCommandHandler.java src/test/java/com/mindcli/runtime/run/mode/ModeAdapterTest.java src/test/java/com/mindcli/runtime/run/mode/AgentRuntimeTest.java src/test/java/com/mindcli/app/cli/runtime/CliRunResumerTest.java src/test/java/com/mindcli/app/cli/MainCommandHandlerRefactorTest.java
git commit -m "feat: route plan runs through exact resume"
```

### Task 5: Offline JsonlRunStore Outcome Evaluation

**Files:**
- Create: `src/test/java/com/mindcli/eval/PlanExactResumeEvalTest.java`

**Interfaces:**
- Consumes: real `JsonlRunStore`, real `ToolRegistry`, `AgentRuntime.resume`, and scripted offline `LlmClient`.
- Proves: process-boundary persistence, same run ID, completed write deduplication, remaining-task completion, and ambiguous-side-effect refusal.

- [ ] **Step 1: Write the failing end-to-end evaluations**

```java
@Test
void resumesSecondTaskAfterStoreReopenWithoutRepeatingFirstWrite(@TempDir Path root) throws Exception {
    Path workspace = root.resolve("workspace");
    Path runs = root.resolve("runs");
    Files.createDirectories(workspace);
    JsonlRunStore firstStore = new JsonlRunStore(runs);
    CancellationToken token = CancellationContext.startRun();
    try {
        CallbackLlmClient initial = initialTwoTaskScript(token);
        AgentRunContext context = fixedPlanContext("run-plan-resume", workspace);
        AgentRunResult cancelled = new AgentRuntime(firstStore).run(
                context, new PlanModeAdapter(planAgent(initial, firstStore, workspace)));
        assertEquals(AgentRunStatus.CANCELLED, cancelled.status());
        assertEquals("once", Files.readString(workspace.resolve("first.txt")));

        CancellationContext.clear(token);
        JsonlRunStore reopened = new JsonlRunStore(runs);
        AgentRunResult resumed = new AgentRuntime(reopened).resume(
                context.runId(), new PlanModeAdapter(planAgent(secondTaskScript(), reopened, workspace)));

        assertEquals(AgentRunStatus.SUCCESS, resumed.status());
        assertEquals(context.runId(), resumed.runId());
        assertEquals("once", Files.readString(workspace.resolve("first.txt")));
        assertEquals("done", Files.readString(workspace.resolve("second.txt")));
        assertEquals(1, completedToolCount(reopened, context.runId(), "write_first"));
        assertEquals(1, reopened.events(context.runId()).stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_RESUMED).count());
    } finally {
        CancellationContext.clear(token);
    }
}

@Test
void refusesAmbiguousSuccessfulWriteAfterRunningCheckpoint(@TempDir Path root) {
    JsonlRunStore store = ambiguousWriteLedger(root.resolve("runs"), root.resolve("workspace"));

    AgentRunResult result = new AgentRuntime(new JsonlRunStore(root.resolve("runs")))
            .resume("run-plan-ambiguous", new PlanModeAdapter(planAgent(noCalls(), store, root.resolve("workspace"))));

    assertEquals(AgentRunStatus.FAILED, result.status());
    assertTrue(result.errorMessage().contains("副作用"));
    assertTrue(store.events("run-plan-ambiguous").stream()
            .noneMatch(event -> event.type() == AgentRunEventType.RUN_RESUMED));
}
```

The scripted callback cancels the current token while delivering task 1's final assistant response. This leaves a terminal `COMPLETED` task checkpoint, then the existing loop observes cancellation before scheduling task 2. The resume script contains responses only for task 2, so any accidental replay of task 1 fails deterministically.

- [ ] **Step 2: Run the evaluation and verify RED**

Run: `mvn test -Dtest=PlanExactResumeEvalTest -DskipTests=false`

Expected: the test fails before Tasks 1-4 are complete; after those tasks it must pass without network access.

- [ ] **Step 3: Complete only fixture helpers needed by the evaluation**

Keep callback/script helpers nested in `PlanExactResumeEvalTest`; use production constructors and filesystem behavior. Do not add cancellation controls or special restore hooks to production code. Count tool outcomes by exact `toolId`, and use a fixed safe run ID so ledger reopening is deterministic.

- [ ] **Step 4: Run all outcome evaluations and verify GREEN**

Run: `mvn test -Dtest='com.mindcli.eval.*Test' -DskipTests=false`

Expected: the original 9 evaluation tests plus the 2 new Plan recovery evaluations pass with 0 failures and 0 errors.

- [ ] **Step 5: Commit the evaluation**

```bash
git add src/test/java/com/mindcli/eval/PlanExactResumeEvalTest.java
git commit -m "test: evaluate plan exact resume"
```

### Task 6: Documentation and Full Verification

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `ROADMAP.md`
- Add ignored plan with force: `docs/superpowers/plans/2026-09-03-plan-exact-resume.md`

**Interfaces:**
- Consumes: delivered behavior and verification evidence from Tasks 1-5.
- Produces: user-facing limitations and repository navigation consistent with code.

- [ ] **Step 1: Update behavior documentation**

Add to `README.md` and `AGENTS.md`: Plan runs created after this change persist an approved/replanned DAG plus task checkpoints; `/run resume` skips terminal tasks, retries only safe nonterminal tasks, never repeats review/planning, requires `--confirm` when already-completed tasks had side effects, and refuses old ledgers or ambiguous successful side effects. Keep Team documented as whole-run safe retry only.

Move Plan exact task-boundary recovery to completed in `ROADMAP.md`; leave Team parent/child/review exact recovery in the next phase.

- [ ] **Step 2: Run focused recovery and Plan suites**

Run: `mvn test -Dtest=PlanCheckpointCodecTest,RunRecoveryServiceTest,ModeAdapterTest,AgentRuntimeTest,PlanExecuteAgentTest,ExecutionPlanTest,CliRunResumerTest,MainCommandHandlerRefactorTest,PlanExactResumeEvalTest -DskipTests=false`

Expected: all selected tests pass with 0 failures and 0 errors.

- [ ] **Step 3: Run the complete offline outcome evaluation suite**

Run: `mvn test -Dtest='com.mindcli.eval.*Test' -DskipTests=false`

Expected: all outcome evaluation tests pass with 0 failures and 0 errors.

- [ ] **Step 4: Run the repository quick regression**

Run: `mvn test -Pquick -DskipTests=false`

Expected: the full quick-profile suite passes with 0 failures and 0 errors; environment-dependent skips remain skips.

- [ ] **Step 5: Check patch hygiene and changed files**

Run: `git diff --check`

Expected: no output and exit code 0.

Run: `git status --short`

Expected: only the Plan exact-resume implementation/documentation changes plus the pre-existing untracked HTML files are shown.

- [ ] **Step 6: Commit documentation and plan**

```bash
git add README.md AGENTS.md ROADMAP.md
git add -f docs/superpowers/plans/2026-09-03-plan-exact-resume.md
git commit -m "docs: document plan exact resume"
```

## Self-Review

- Spec coverage: Tasks 1-5 cover checkpoint schema, projection, safe ambiguity handling, restored DAG execution, runtime routing, CLI errors, process-boundary persistence, deduplication, and ReAct preservation. Task 6 covers required documentation and verification. Team recovery, token-stream recovery, session restoration, and legacy inference remain excluded.
- Placeholder scan: the plan contains no deferred implementation marker or unspecified error-handling instruction; each production behavior names exact validation and failure semantics.
- Type consistency: all tasks use `PlanResumeState`, `PlanTaskResumeState`, `PlanCheckpointCodec`, `RunRecoveryService.reconstructPlanState`, `PlanExecuteAgent.runRecovered`, and `PlanModeAdapter.executeRecovered` with the same signatures as the approved design.
