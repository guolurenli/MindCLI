# Team Step Exact Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Team parent-step checkpoint recovery that reuses the original plan, skips terminal steps, and refuses ambiguous child side effects or unconfirmed worktree merges.

**Architecture:** Persist `TEAM_PLAN_DEFINED` and `TEAM_STEP_CHECKPOINT` in the parent JSONL ledger, link every execute/review child before it starts, and reconstruct an immutable `TeamResumeState` inside `runtime/run/recovery`. `TeamModeAdapter` bridges that state back into `AgentOrchestrator`; the existing `TeamScheduler` remains the sole DAG scheduler. Child loops gain ledger evidence before tool dispatch, but are not themselves resumed.

**Tech Stack:** Java 17, Maven, JUnit 5, Jackson through `JsonSupport`, existing `RunStore`/`JsonlRunStore`, existing `AgentTurnKernel`, existing Git worktree support.

## Global Constraints

- JSONL ledger remains the only source of truth; do not add `team.state.json` or another persistence path.
- Keep `ExecutionStep`, `StepStatus`, and `TeamScheduler` package-private; recovery DTOs live in `runtime/run/recovery`.
- Do not re-run the Team planner on resume.
- Do not restore inside a child LLM/tool loop.
- Write the parent child-link checkpoint before the child `RUN_STARTED` event.
- Persist worktree steps as `COMPLETED` only after `mergeBatchAndDispose(...)` returns `CLEAN` or `NOTHING`.
- `REVIEWING`, `AWAITING_MERGE`, incomplete child calls, and non-terminal successful side effects fail closed with risk `UNKNOWN`.
- A terminal step with known side effects may resume only with the existing `--confirm` path.
- Preserve the existing ReAct and Plan recovery behavior.
- Update `AGENTS.md`, `README.md`, and `ROADMAP.md` only when implementation behavior is delivered.
- Do not modify or commit the five existing untracked files under `html/`.

## File Map

**Create**

- `src/main/java/com/mindcli/runtime/run/recovery/TeamResumeState.java` — immutable recovered Team plan.
- `src/main/java/com/mindcli/runtime/run/recovery/TeamStepResumeState.java` — immutable recovered step definition and latest checkpoint.
- `src/main/java/com/mindcli/runtime/run/recovery/TeamCheckpointCodec.java` — canonical Team plan JSON codec and step-id JSON codec.
- `src/test/java/com/mindcli/runtime/run/recovery/TeamCheckpointCodecTest.java` — codec and plan validation tests.
- `src/test/java/com/mindcli/eval/TeamExactResumeEvalTest.java` — offline fault-injection acceptance evaluation.

**Modify**

- `src/main/java/com/mindcli/runtime/run/AgentRunEventType.java` — add the two Team event types.
- `src/main/java/com/mindcli/runtime/run/store/RunStateProjector.java` — recognize Team checkpoints as resumable progress.
- `src/main/java/com/mindcli/runtime/run/recovery/RunRecoveryService.java` — project parent/child events and classify Team recovery risk.
- `src/main/java/com/mindcli/agent/team/SubAgent.java` — persist per-turn tool request evidence before dispatch and full outcome arguments.
- `src/main/java/com/mindcli/agent/team/AgentOrchestrator.java` — write parent checkpoints, restore steps, and enforce the merge completion boundary.
- `src/main/java/com/mindcli/runtime/run/mode/TeamModeAdapter.java` — expose `executeRecovered(...)`.
- `src/main/java/com/mindcli/runtime/run/AgentRuntime.java` — reconstruct Team state before `RUN_RESUMED`.
- `src/main/java/com/mindcli/app/cli/command/RunCommandHandler.java` — show recovery risk and reason in `/run inspect`.
- `src/test/java/com/mindcli/runtime/run/recovery/RunRecoveryServiceTest.java`
- `src/test/java/com/mindcli/agent/team/SubAgentTest.java`
- `src/test/java/com/mindcli/agent/team/AgentOrchestratorTest.java`
- `src/test/java/com/mindcli/runtime/run/mode/AgentRuntimeTest.java`
- `src/test/java/com/mindcli/app/cli/runtime/CliRunResumerTest.java`
- `src/test/java/com/mindcli/app/cli/MainCommandHandlerRefactorTest.java`
- `AGENTS.md`, `README.md`, `ROADMAP.md`

---

### Task 1: Team checkpoint records and codec

**Files:**

- Create: `src/main/java/com/mindcli/runtime/run/recovery/TeamResumeState.java`
- Create: `src/main/java/com/mindcli/runtime/run/recovery/TeamStepResumeState.java`
- Create: `src/main/java/com/mindcli/runtime/run/recovery/TeamCheckpointCodec.java`
- Create: `src/test/java/com/mindcli/runtime/run/recovery/TeamCheckpointCodecTest.java`
- Modify: `src/main/java/com/mindcli/runtime/run/AgentRunEventType.java`

**Interfaces:**

- Produces: `TeamResumeState.unavailable(String)` and immutable state fields used by recovery and the Team adapter.
- Produces: `String TeamCheckpointCodec.encodePlan(TeamResumeState)`.
- Produces: `TeamResumeState TeamCheckpointCodec.decodePlan(String)`.
- Produces: `String TeamCheckpointCodec.encodeStepIds(List<String>)`.
- Produces: `List<String> TeamCheckpointCodec.decodeStepIds(String)`; throws `IllegalArgumentException` on malformed or duplicate IDs.

- [ ] **Step 1: Write the failing codec tests**

Create `TeamCheckpointCodecTest` with concrete round-trip and rejection cases:

```java
class TeamCheckpointCodecTest {
    private final TeamCheckpointCodec codec = new TeamCheckpointCodec();

    @Test
    void roundTripsEveryTeamStepField() {
        TeamResumeState state = new TeamResumeState(true, 1, 1, List.of(
                new TeamStepResumeState("step_1", "inspect code", "FILE_READ", List.of(),
                        List.of("read_file"), "explorer", "low", "COMPLETED", "", 0,
                        "evidence", "", List.of("child-execute", "child-review"))), "");

        assertEquals(state, codec.decodePlan(codec.encodePlan(state)));
    }

    @Test
    void rejectsDuplicateIdsUnknownDependenciesAndCycles() {
        TeamResumeState decoded = codec.decodePlan("""
                {"schemaVersion":1,"planVersion":1,"steps":[
                  {"id":"a","description":"a","type":"ANALYSIS","dependencies":["b"],
                   "requiredTools":[],"preferredAgent":"","riskLevel":"low","status":"PENDING",
                   "phase":"","attempt":0,"result":"","error":"","childRunIds":[]},
                  {"id":"b","description":"b","type":"ANALYSIS","dependencies":["a"],
                   "requiredTools":[],"preferredAgent":"","riskLevel":"low","status":"PENDING",
                   "phase":"","attempt":0,"result":"","error":"","childRunIds":[]}
                ]}
                """);
        assertFalse(decoded.available());
        assertTrue(decoded.reason().contains("环"));
    }

    @Test
    void stepIdsRoundTripAndRejectDuplicates() {
        assertEquals(List.of("step_1", "step_2"),
                codec.decodeStepIds(codec.encodeStepIds(List.of("step_1", "step_2"))));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeStepIds("[\"step_1\",\"step_1\"]"));
    }
}
```

- [ ] **Step 2: Run the codec test and verify RED**

Run:

```bash
mvn test -Dtest=TeamCheckpointCodecTest -DskipTests=false
```

Expected: compilation fails because the Team recovery records and codec do not exist.

- [ ] **Step 3: Add the event types and minimal immutable records**

Add after the Plan events:

```java
TEAM_PLAN_DEFINED,
TEAM_STEP_CHECKPOINT,
```

Implement these exact record shapes:

```java
public record TeamResumeState(
        boolean available,
        int schemaVersion,
        int planVersion,
        List<TeamStepResumeState> steps,
        String reason) {
    public TeamResumeState {
        steps = steps == null ? List.of() : List.copyOf(steps);
        reason = reason == null ? "" : reason;
    }

    public static TeamResumeState unavailable(String reason) {
        return new TeamResumeState(false, 0, 0, List.of(), reason);
    }
}
```

```java
public record TeamStepResumeState(
        String id,
        String description,
        String type,
        List<String> dependencies,
        List<String> requiredTools,
        String preferredAgent,
        String riskLevel,
        String status,
        String phase,
        int attempt,
        String result,
        String error,
        List<String> childRunIds) {
    public TeamStepResumeState {
        id = id == null ? "" : id;
        description = description == null ? "" : description;
        type = type == null ? "" : type;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        preferredAgent = preferredAgent == null ? "" : preferredAgent;
        riskLevel = riskLevel == null ? "" : riskLevel;
        status = status == null ? "" : status;
        phase = phase == null ? "" : phase;
        result = result == null ? "" : result;
        error = error == null ? "" : error;
        childRunIds = childRunIds == null ? List.of() : List.copyOf(childRunIds);
    }
}
```

- [ ] **Step 4: Implement canonical JSON validation**

Use `JsonSupport.mapper()` as in `PlanCheckpointCodec`. Accept schema/version `1`; accept statuses `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `SKIPPED`; accept only empty phase or `EXECUTING`, `REVIEWING`, `AWAITING_MERGE`. Validate non-empty unique IDs, known dependencies, no self-dependency, acyclic DAG, non-negative attempt, and safe child IDs matching `AgentRunEvent` path rules. `encodePlan` must reject unavailable state and any state that fails the same validation.

Keep the four public method signatures from the Interfaces block exact. Their bodies perform the
validation listed above and use `JsonSupport.mapper()` for every JSON read and write.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
mvn test -Dtest=TeamCheckpointCodecTest -DskipTests=false
```

Expected: all `TeamCheckpointCodecTest` tests pass.

Commit:

```bash
git add src/main/java/com/mindcli/runtime/run/AgentRunEventType.java src/main/java/com/mindcli/runtime/run/recovery/TeamResumeState.java src/main/java/com/mindcli/runtime/run/recovery/TeamStepResumeState.java src/main/java/com/mindcli/runtime/run/recovery/TeamCheckpointCodec.java src/test/java/com/mindcli/runtime/run/recovery/TeamCheckpointCodecTest.java
git commit -m "feat: add team checkpoint model"
```

---

### Task 2: Child tool-call ledger evidence

**Files:**

- Modify: `src/main/java/com/mindcli/agent/team/SubAgent.java`
- Modify: `src/main/java/com/mindcli/agent/team/AgentOrchestrator.java`
- Modify: `src/test/java/com/mindcli/agent/team/SubAgentTest.java`
- Modify: `src/test/java/com/mindcli/agent/team/AgentOrchestratorTest.java`

**Interfaces:**

- Consumes: existing `AgentLoopObserver` callbacks, `AgentRunContext`, and `RunStore` ThreadLocals.
- Produces: child `LLM_RESPONSE(recordKind=turn)` before tool dispatch, matching `TOOL_CALL_REQUESTED`, and `TOOL_OUTCOME(argumentsJson=...)`.
- Produces: orchestrator summary responses tagged `recordKind=child_summary`.

- [ ] **Step 1: Write failing ordering and payload tests**

Add a `SubAgentTest` case using a recording RunStore and a one-tool scripted response:

```java
@Test
void recordsToolRequestBeforeOutcomeWithArguments(@TempDir Path root) {
    MultiCallStreamClient llm = new MultiCallStreamClient(List.of(
            new CallScript(listener -> { }, new LlmClient.ChatResponse(
                    "assistant", "", null,
                    List.of(new LlmClient.ToolCall("call_read",
                            new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"))),
                    10, 5)),
            new CallScript(listener -> { }, new LlmClient.ChatResponse(
                    "assistant", "done", null, null, 10, 5))));
    ToolRegistry registry = new ToolRegistry() {
        @Override
        public ToolExecution executeToolExecution(String name, String argumentsJson) {
            return ToolExecution.completed(ToolOutput.text("content"), argumentsJson);
        }
    };
    registry.setProjectPath(root.toString());
    SubAgent agent = new SubAgent(AgentProfile.builtinExplorer("explorer#ledger"), llm, registry);
    RecordingRunStore store = new RecordingRunStore();
    AgentRunContext child = AgentRunContext.create(AgentMode.TEAM, "read a.txt", root.toString());

    agent.executeWithRunContext(AgentMessage.task("orchestrator", "read a.txt"),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), child, store);

    List<AgentRunEvent> events = store.events(child.runId());
    int request = IntStream.range(0, events.size())
            .filter(i -> events.get(i).type() == AgentRunEventType.TOOL_CALL_REQUESTED)
            .findFirst().orElseThrow();
    int outcome = IntStream.range(0, events.size())
            .filter(i -> events.get(i).type() == AgentRunEventType.TOOL_OUTCOME)
            .findFirst().orElseThrow();
    assertTrue(request < outcome);
    assertEquals("turn", events.stream()
            .filter(e -> e.type() == AgentRunEventType.LLM_RESPONSE)
            .findFirst().orElseThrow().attributes().get("recordKind"));
    assertEquals("{\"path\":\"a.txt\"}",
            events.get(outcome).attributes().get("argumentsJson"));
}
```

Add an `AgentOrchestratorTest` assertion that the existing post-child summary event contains `recordKind=child_summary`.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn test -Dtest=SubAgentTest,AgentOrchestratorTest -DskipTests=false
```

Expected: new assertions fail because Team child only records outcomes and untagged summary responses.

- [ ] **Step 3: Record per-turn LLM and request events before dispatch**

Extend the existing `AgentLoopObserver` in `SubAgent.execute(...)`:

```java
@Override
public void afterLlmResponse(int iteration, LlmClient.ChatResponse response) {
    LlmTraceLogger.logReasoning(log, traceName, llmClient, response.reasoningContent());
    appendTurnLlmResponse(iteration, response);
}

@Override
public void beforeToolDispatch(int iteration, List<LlmClient.ToolCall> toolCalls) {
    appendToolCallRequested(iteration, toolCalls);
    printToolCalls(out, toolCalls);
    streamRenderer.resetBetweenIterations();
}
```

`appendTurnLlmResponse` must store `recordKind=turn`, iteration, token counts, content/reasoning, toolCallCount, and canonical `toolCallsJson` with `id`, `function.name`, and `function.arguments`. `appendToolCallRequested` must store `recordKind=turn`, iteration, count, names, and IDs. Both methods are no-ops when the active child context or RunStore is absent.

- [ ] **Step 4: Store outcome arguments and tag child summaries**

Change child outcome persistence to include the invocation arguments:

```java
Map<String, String> extra = new LinkedHashMap<>();
extra.put("argumentsJson", outcome.argumentsJson());
runStore.append(ToolOutcomeEventFactory.create(context, outcome, extra));
```

In `executeWorkerChild(...)` and `executeSelfReviewChild(...)`, add `recordKind=child_summary` to the orchestrator-authored post-child `LLM_RESPONSE`. Do not remove the existing `phase`, `agent`, or `messageType` fields.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
mvn test -Dtest=SubAgentTest,AgentOrchestratorTest -DskipTests=false
```

Expected: both suites pass, and the request event precedes the tool outcome.

Commit:

```bash
git add src/main/java/com/mindcli/agent/team/SubAgent.java src/main/java/com/mindcli/agent/team/AgentOrchestrator.java src/test/java/com/mindcli/agent/team/SubAgentTest.java src/test/java/com/mindcli/agent/team/AgentOrchestratorTest.java
git commit -m "feat: persist team child tool requests"
```

---

### Task 3: Team recovery projection and safety classification

**Files:**

- Modify: `src/main/java/com/mindcli/runtime/run/recovery/RunRecoveryService.java`
- Modify: `src/main/java/com/mindcli/runtime/run/store/RunStateProjector.java`
- Modify: `src/test/java/com/mindcli/runtime/run/recovery/RunRecoveryServiceTest.java`
- Modify: `src/test/java/com/mindcli/runtime/run/store/RunStateProjectorTest.java`

**Interfaces:**

- Consumes: Task 1 records/codec and child evidence from Task 2.
- Produces: `public TeamResumeState reconstructTeamState(String runId)`.
- Produces internally: one `TeamRecoveryProjection` containing state, observed tools, historical side-effect flag, and failure reason so `inspect` and reconstruction share one projection pass.

- [ ] **Step 1: Write failing recovery projection tests**

Add helpers that append a Team definition and parent checkpoint, then cover these exact assertions:

```java
@Test
void teamExecutingReadOnlyChildReturnsToPending() {
    TeamFixture f = teamFixture("RUNNING", "EXECUTING", "child-read");
    appendCompleteChildToolTurn(f.store(), f.child("child-read"),
            "call-1", "read_file", "{\"path\":\"a.txt\"}");
    f.cancelParent();

    TeamResumeState state = new RunRecoveryService(f.store()).reconstructTeamState(f.parent().runId());

    assertTrue(state.available(), state.reason());
    assertEquals("PENDING", state.steps().get(0).status());
    assertEquals("", state.steps().get(0).phase());
}

@Test
void teamExecutingWriteWithoutTerminalCheckpointFailsClosed() {
    TeamFixture f = teamFixture("RUNNING", "EXECUTING", "child-write");
    appendCompleteChildToolTurn(f.store(), f.child("child-write"),
            "call-1", "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");
    f.cancelParent();

    RunRecoveryPlan plan = new RunRecoveryService(f.store()).inspect(f.parent().runId());

    assertFalse(plan.resumeAvailable());
    assertEquals("UNKNOWN", plan.resumePlan().risk());
    assertTrue(plan.resumePlan().reason().contains("step_1"));
}

@Test
void teamReviewingAndAwaitingMergeAlwaysFailClosed() {
    assertFalse(inspectTeamAtPhase("REVIEWING").resumeAvailable());
    assertFalse(inspectTeamAtPhase("AWAITING_MERGE").resumeAvailable());
}
```

Also test: empty referenced child is safe only from `EXECUTING`; started-but-nonterminal child is unsafe; mismatched ID/name/arguments is unsafe; terminal completed/failed/skipped steps retain results; completed write produces `HIGH` plus confirmation; old Team ledger is rejected.

Define the fixture used above in `RunRecoveryServiceTest`; it writes real events rather than mocking
`RunRecoveryService`:

```java
private record TeamFixture(InMemoryRunStore store, AgentRunContext parent, String childId) {
    AgentRunContext child(String id) {
        return new AgentRunContext(id, AgentMode.TEAM, parent.input(), parent.workspace(),
                parent.startedAt(), Map.of("parentRunId", parent.runId(), "stepId", "step_1"));
    }

    void cancelParent() {
        store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_CANCELLED));
    }
}

private static TeamFixture teamFixture(String status, String phase, String childId) {
    InMemoryRunStore store = new InMemoryRunStore();
    AgentRunContext parent = new AgentRunContext("team-parent-" + childId, AgentMode.TEAM,
            "goal", "workspace", Instant.now(), Map.of());
    store.append(AgentRunEvent.of(parent, AgentRunEventType.RUN_STARTED, Map.of("input", "goal")));
    TeamResumeState initial = new TeamResumeState(true, 1, 1, List.of(
            new TeamStepResumeState("step_1", "step", "ANALYSIS", List.of(), List.of(),
                    "", "low", "PENDING", "", 0, "", "", List.of())), "");
    TeamCheckpointCodec codec = new TeamCheckpointCodec();
    store.append(AgentRunEvent.of(parent, AgentRunEventType.TEAM_PLAN_DEFINED, Map.of(
            "schemaVersion", "1", "planVersion", "1", "planJson", codec.encodePlan(initial))));
    store.append(AgentRunEvent.of(parent, AgentRunEventType.TEAM_STEP_CHECKPOINT, Map.of(
            "schemaVersion", "1", "planVersion", "1",
            "stepIdsJson", codec.encodeStepIds(List.of("step_1")),
            "stepStatus", status, "phase", phase, "attempt", "0",
            "childRunId", childId, "result", "", "error", "")));
    return new TeamFixture(store, parent, childId);
}

private static void appendCompleteChildToolTurn(InMemoryRunStore store, AgentRunContext child,
                                                String id, String name, String arguments) {
    String calls = "[{\"id\":\"" + id + "\",\"function\":{\"name\":\""
            + name + "\",\"arguments\":" + JsonSupport.mapper().valueToTree(arguments) + "}}]";
    store.append(AgentRunEvent.of(child, AgentRunEventType.RUN_STARTED, Map.of("phase", "execute")));
    store.append(AgentRunEvent.of(child, AgentRunEventType.LLM_RESPONSE, Map.of(
            "recordKind", "turn", "iteration", "1", "toolCallCount", "1", "toolCallsJson", calls)));
    store.append(AgentRunEvent.of(child, AgentRunEventType.TOOL_CALL_REQUESTED, Map.of(
            "recordKind", "turn", "iteration", "1", "toolCallCount", "1", "toolNames", name)));
    store.append(AgentRunEvent.of(child, AgentRunEventType.TOOL_OUTCOME, Map.of(
            "toolId", id, "toolName", name, "argumentsJson", arguments, "status", "COMPLETED")));
    store.append(AgentRunEvent.of(child, AgentRunEventType.RUN_FINISHED,
            Map.of("phase", "execute", "status", "SUCCESS")));
}

private static RunRecoveryPlan inspectTeamAtPhase(String phase) {
    TeamFixture fixture = teamFixture("RUNNING", phase, "child-" + phase.toLowerCase());
    fixture.cancelParent();
    return new RunRecoveryService(fixture.store()).inspect(fixture.parent().runId());
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn test -Dtest=RunRecoveryServiceTest,RunStateProjectorTest -DskipTests=false
```

Expected: Team reconstruction methods and event handling are absent.

- [ ] **Step 3: Implement one internal Team projection pass**

Add:

```java
public TeamResumeState reconstructTeamState(String runId) {
    return projectTeam(runId).state();
}

private record TeamRecoveryProjection(
        TeamResumeState state,
        List<String> toolNames,
        boolean completedSideEffect,
        String reason) {
}
```

Do not expose child events through `RunRecoveryPlan.events`. Validate `stepIdsJson` against the plan and the same normalized fingerprint fields used by Team scheduling: type, description, sorted requiredTools, preferredAgent, riskLevel, and sorted dependencies.

- [ ] **Step 4: Integrate Team inspection and RunState projection**

In `inspect(...)`, add the Team branch beside the Plan branch:

```java
if (resumeAvailable && mode == AgentMode.TEAM) {
    TeamRecoveryProjection team = projectTeam(runId);
    if (!team.state().available()) {
        resumeAvailable = false;
        resumePlan = new RunResumePlan(false, true, "UNKNOWN", team.reason(), team.toolNames());
    } else if (team.completedSideEffect()) {
        resumePlan = new RunResumePlan(false, true, "HIGH",
                "已完成 Team step 包含可能产生副作用的工具调用", team.toolNames());
    } else {
        resumePlan = new RunResumePlan(true, false, "LOW",
                "Team checkpoint 完整，仅继续安全的未完成步骤", team.toolNames());
    }
}
```

Update `RunStateProjector` so `TEAM_PLAN_DEFINED` and `TEAM_STEP_CHECKPOINT` set `resumable=true` and update the last completed event fields, matching other progress events.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
mvn test -Dtest=TeamCheckpointCodecTest,RunRecoveryServiceTest,RunStateProjectorTest -DskipTests=false
```

Expected: all selected suites pass; existing ReAct and Plan cases remain green.

Commit:

```bash
git add src/main/java/com/mindcli/runtime/run/recovery/RunRecoveryService.java src/main/java/com/mindcli/runtime/run/store/RunStateProjector.java src/test/java/com/mindcli/runtime/run/recovery/RunRecoveryServiceTest.java src/test/java/com/mindcli/runtime/run/store/RunStateProjectorTest.java
git commit -m "feat: reconstruct team checkpoints"
```

---

### Task 4: Parent plan and child-link checkpoints

**Files:**

- Modify: `src/main/java/com/mindcli/agent/team/AgentOrchestrator.java`
- Modify: `src/test/java/com/mindcli/agent/team/AgentOrchestratorTest.java`

**Interfaces:**

- Consumes: `TeamCheckpointCodec` and Team recovery record shapes.
- Produces: parent `TEAM_PLAN_DEFINED` before execution.
- Produces: parent `TEAM_STEP_CHECKPOINT(RUNNING, EXECUTING|REVIEWING)` before each child start.
- Produces: `public String runRecovered(AgentRunContext, RunStore, TeamResumeState)`.

- [ ] **Step 1: Write failing checkpoint-order and no-replan tests**

Add tests with a recording RunStore:

```java
@Test
void writesPlanAndChildLinkBeforeChildStarts(@TempDir Path root) {
    StubGLMClient llm = new StubGLMClient(List.of(
            response("""{"summary":"one","steps":[
              {"id":"a","description":"inspect","type":"ANALYSIS","dependencies":[]}
            ]}"""),
            response("inspection result"),
            response("{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}")));
    RecordingRunStore recordingStore = new RecordingRunStore();
    ToolRegistry registry = new ToolRegistry();
    registry.setProjectPath(root.toString());
    AgentOrchestrator orchestrator = new AgentOrchestrator(llm, registry,
            new NoOpMemoryManager(root.toFile()),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), recordingStore);
    orchestrator.run("inspect project");

    List<AgentRunEvent> all = recordingStore.allEvents();
    String parentId = all.stream().filter(e -> e.type() == AgentRunEventType.RUN_STARTED)
            .filter(e -> !e.attributes().containsKey("parentRunId"))
            .findFirst().orElseThrow().runId();
    AgentRunEvent executing = all.stream()
            .filter(e -> e.runId().equals(parentId))
            .filter(e -> e.type() == AgentRunEventType.TEAM_STEP_CHECKPOINT)
            .filter(e -> "EXECUTING".equals(e.attributes().get("phase")))
            .findFirst().orElseThrow();
    AgentRunEvent childStart = all.stream()
            .filter(e -> e.type() == AgentRunEventType.RUN_STARTED)
            .filter(e -> "execute".equals(e.attributes().get("phase")))
            .findFirst().orElseThrow();
    assertEquals(childStart.runId(), executing.attributes().get("childRunId"));
    assertTrue(all.indexOf(executing) < all.indexOf(childStart));
    assertTrue(all.indexOf(all.stream()
            .filter(e -> e.runId().equals(parentId))
            .filter(e -> e.type() == AgentRunEventType.TEAM_PLAN_DEFINED)
            .findFirst().orElseThrow()) < all.indexOf(childStart));
}

@Test
void recoveredRunSkipsPlannerAndCompletedStep(@TempDir Path root) {
    TeamResumeState state = new TeamResumeState(true, 1, 1, List.of(
            new TeamStepResumeState("step_1", "first", "ANALYSIS", List.of(), List.of(),
                    "", "low", "COMPLETED", "", 0, "first result", "", List.of()),
            new TeamStepResumeState("step_2", "second", "ANALYSIS", List.of("step_1"), List.of(),
                    "", "low", "PENDING", "", 0, "", "", List.of())), "");
    StubGLMClient llm = new StubGLMClient(List.of(
            response("second result"),
            response("{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}")));
    RecordingRunStore store = new RecordingRunStore();
    ToolRegistry registry = new ToolRegistry();
    registry.setProjectPath(root.toString());
    AgentOrchestrator orchestrator = new AgentOrchestrator(llm, registry,
            new NoOpMemoryManager(root.toFile()),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), store);
    AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "original task", root.toString());

    String result = orchestrator.runRecovered(context, store, state);

    assertTrue(result.contains("second result"));
    assertTrue(store.allEvents().stream()
            .filter(e -> e.type() == AgentRunEventType.RUN_STARTED)
            .filter(e -> e.attributes().containsKey("parentRunId"))
            .noneMatch(e -> "step_1".equals(e.attributes().get("stepId"))));
    assertEquals(2, store.allEvents().stream()
            .filter(e -> e.type() == AgentRunEventType.RUN_STARTED)
            .filter(e -> "step_2".equals(e.attributes().get("stepId"))).count());
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn test -Dtest=AgentOrchestratorTest -DskipTests=false
```

Expected: missing Team events, missing `runRecovered`, or incorrect event ordering.

- [ ] **Step 3: Split planning from the existing scheduling loop**

Keep public entry points small:

```java
public String runRecovered(AgentRunContext context, RunStore store, TeamResumeState state) {
    Objects.requireNonNull(state, "state");
    if (!state.available()) throw new IllegalArgumentException(state.reason());
    return runInternal(context, store == null ? this.runStore : store, false, state);
}
```

Change `runInternal` to accept nullable recovered state. Initial execution calls planner, parses steps, writes `TEAM_PLAN_DEFINED`, then calls one extracted scheduling method. Recovery converts DTOs to `ExecutionStep` and calls the same scheduling method without planner or plan review.

Use exact conversion rules:

```java
private static ExecutionStep toExecutionStep(TeamStepResumeState s) {
    return new ExecutionStep(s.id(), s.description(), s.type(), s.dependencies(), s.requiredTools(),
            s.preferredAgent(), s.riskLevel(), s.result().isBlank() ? null : s.result(),
            StepStatus.valueOf(s.status()));
}
```

- [ ] **Step 4: Link execute and review children before start**

Refactor both child methods to create context, append parent phase, then start child:

```java
AgentRunContext child = childRunContext(parent, role, step.id(), attempt, profile, requirements, reason);
appendStepCheckpoint(parent, List.of(step.id()), StepStatus.RUNNING,
        "EXECUTING", attempt, child.runId(), "", "");
appendChildRunStarted(child, "execute");
```

Do the same with phase `REVIEWING` before review `RUN_STARTED`. If checkpoint append throws, let the exception escape so no child starts. Do not catch and downgrade checkpoint failures to log-only warnings.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
mvn test -Dtest=AgentOrchestratorTest,TeamCheckpointCodecTest -DskipTests=false
```

Expected: plan, ordering, no-replan, and existing Team tests pass.

Commit:

```bash
git add src/main/java/com/mindcli/agent/team/AgentOrchestrator.java src/test/java/com/mindcli/agent/team/AgentOrchestratorTest.java
git commit -m "feat: checkpoint team step phases"
```

---

### Task 5: Atomic duplicate outcomes and worktree merge boundary

**Files:**

- Modify: `src/main/java/com/mindcli/agent/team/AgentOrchestrator.java`
- Modify: `src/test/java/com/mindcli/agent/team/AgentOrchestratorTest.java`

**Interfaces:**

- Consumes: parent phase checkpoint helpers from Task 4.
- Produces: one terminal checkpoint for a leader plus its duplicates.
- Produces: `AWAITING_MERGE` after worktree review approval and `COMPLETED` only after confirmed merge.

- [ ] **Step 1: Write failing duplicate and worktree-boundary tests**

Add tests that observe parent events rather than only final in-memory status:

```java
@Test
void leaderAndDuplicateCompleteInOneCheckpoint(@TempDir Path root) {
    StubGLMClient llm = new StubGLMClient(List.of(
            response("""{"summary":"duplicates","steps":[
              {"id":"a","description":"inspect same symbol","type":"ANALYSIS","dependencies":[]},
              {"id":"b","description":"inspect same symbol","type":"ANALYSIS","dependencies":[]}
            ]}"""),
            response("one result"),
            response("{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}")));
    RecordingRunStore store = new RecordingRunStore();
    ToolRegistry registry = new ToolRegistry();
    registry.setProjectPath(root.toString());
    AgentOrchestrator orchestrator = new AgentOrchestrator(llm, registry,
            new NoOpMemoryManager(root.toFile()), System.out, store);
    orchestrator.run("inspect once");

    List<AgentRunEvent> completed = store.allEvents().stream()
            .filter(e -> e.type() == AgentRunEventType.TEAM_STEP_CHECKPOINT)
            .filter(e -> "COMPLETED".equals(e.attributes().get("stepStatus"))).toList();
    assertEquals(1, completed.size());
    assertEquals(List.of("step_1", "step_2"),
            new TeamCheckpointCodec().decodeStepIds(completed.get(0).attributes().get("stepIdsJson")));
}

@Test
void worktreeCompletesOnlyAfterSuccessfulMerge(@TempDir Path root) {
    RecordingRunStore store = new RecordingRunStore();
    RecordingWorktreeManager worktrees = new RecordingWorktreeManager(store, false);
    AgentOrchestrator orchestrator = parallelWriteOrchestrator(root, store, worktrees);
    orchestrator.run("write two independent files");

    assertTrue(worktrees.sawAwaitingMerge());
    assertTrue(store.allEvents().stream().anyMatch(e ->
            e.type() == AgentRunEventType.TEAM_STEP_CHECKPOINT
                    && "COMPLETED".equals(e.attributes().get("stepStatus"))));
}

@Test
void ambiguousMergeFailureNeverPersistsCompletedOrFailed(@TempDir Path root) {
    RecordingRunStore store = new RecordingRunStore();
    RecordingWorktreeManager worktrees = new RecordingWorktreeManager(store, true);
    AgentOrchestrator orchestrator = parallelWriteOrchestrator(root, store, worktrees);
    orchestrator.run("write two independent files");

    assertTrue(worktrees.sawAwaitingMerge());
    assertTrue(store.allEvents().stream()
            .filter(e -> e.type() == AgentRunEventType.TEAM_STEP_CHECKPOINT)
            .noneMatch(e -> "COMPLETED".equals(e.attributes().get("stepStatus"))
                    || "FAILED".equals(e.attributes().get("stepStatus"))));
}
```

Add `parallelWriteOrchestrator(...)` with the existing `DispatchingStubGLMClient`: return a two-step
plan with `requiredTools:["write_file"]` for the planner input, an approved review JSON for inputs
containing `原始任务`, and a plain candidate result for each step input. Add this concrete nested fake:

```java
private static AgentOrchestrator parallelWriteOrchestrator(
        Path root, RecordingRunStore store, RecordingWorktreeManager worktrees) {
    DispatchingStubGLMClient llm = new DispatchingStubGLMClient(body -> {
        if (body.contains("请为以下任务制定执行计划")) {
            return response("""{"summary":"two writes","steps":[
              {"id":"a","description":"write a","type":"FILE_WRITE","dependencies":[],
               "requiredTools":["write_file"],"riskLevel":"medium"},
              {"id":"b","description":"write b","type":"FILE_WRITE","dependencies":[],
               "requiredTools":["write_file"],"riskLevel":"medium"}
            ]}""");
        }
        if (body.contains("原始任务")) {
            return response("{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}");
        }
        return response(body.contains("write a") ? "candidate a" : "candidate b");
    });
    ToolRegistry registry = new ToolRegistry();
    registry.setProjectPath(root.toString());
    AgentOrchestrator orchestrator = new AgentOrchestrator(llm, registry,
            new NoOpMemoryManager(root.toFile()), System.out, store);
    orchestrator.setWorktreeManager(worktrees);
    return orchestrator;
}

private static final class RecordingWorktreeManager extends GitWorktreeManager {
    private final RecordingRunStore store;
    private final boolean failMerge;
    private boolean sawAwaitingMerge;

    private RecordingWorktreeManager(RecordingRunStore store, boolean failMerge) {
        this.store = store;
        this.failMerge = failMerge;
    }

    @Override public boolean isGitRepository(Path root) { return true; }
    @Override public boolean isGitAvailable() { return true; }
    @Override public synchronized boolean commitCheckpoint(Path root, String message) { return false; }

    @Override
    public synchronized WorktreeHandle create(Path root, Path path, String branch) throws IOException {
        Files.createDirectories(path);
        return new WorktreeHandle(path, branch);
    }

    @Override
    public synchronized BatchMergeResult mergeBatchAndDispose(
            Path root, List<WorktreeHandle> handles, String message) throws IOException {
        sawAwaitingMerge = store.allEvents().stream().anyMatch(e ->
                e.type() == AgentRunEventType.TEAM_STEP_CHECKPOINT
                        && "AWAITING_MERGE".equals(e.attributes().get("phase")));
        if (failMerge) throw new IOException("ambiguous merge failure");
        return new BatchMergeResult(BatchMergeResult.Status.CLEAN, List.of());
    }

    @Override public synchronized void dispose(Path root, WorktreeHandle handle) { }
    boolean sawAwaitingMerge() { return sawAwaitingMerge; }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn test -Dtest=AgentOrchestratorTest -DskipTests=false
```

Expected: leader is completed before duplicate propagation and worktree review currently marks completed before merge.

- [ ] **Step 3: Move terminal persistence to group finalization**

Do not append `COMPLETED/FAILED/SKIPPED` inside `runStepWithWorker`. Keep its in-memory updates, then let each outer group path call:

```java
private void persistGroupOutcome(AgentRunContext context, StepExecutionGroup group,
                                 List<ExecutionStep> steps, int attempt) {
    if (CancellationContext.isCancelled()) return;
    ExecutionStep leader = stepById(steps, group.leader().id());
    if (leader == null || leader.status() == StepStatus.PENDING || leader.status() == StepStatus.RUNNING) return;
    propagateDuplicateResult(group, steps);
    List<String> ids = Stream.concat(Stream.of(group.leader()), group.duplicates().stream())
            .map(ExecutionStep::id).toList();
    appendStepCheckpoint(context, ids, leader.status(), "", attempt, "",
            Objects.toString(leader.result(), ""), leader.status() == StepStatus.FAILED
                    ? Objects.toString(leader.result(), "") : "");
}
```

Pass the final attempt from the existing retry map when calling this method. The checkpoint must be one append for the whole fingerprint group.

- [ ] **Step 4: Defer worktree terminal persistence until merge**

When review approves inside a worktree, append:

```java
appendStepCheckpoint(runContext, List.of(step.id()), StepStatus.RUNNING,
        "AWAITING_MERGE", attempt, "", acceptedResult, "");
```

After `mergeBatchAndDispose(...)`:

- `CLEAN` or `NOTHING`: finalize each group with one terminal group checkpoint.
- `CONFLICTING`: mark groups failed and persist terminal failures because the integration worktree never updated the main workspace.
- thrown `IOException` or interrupted merge: leave the latest persisted state as `AWAITING_MERGE`; update only the transient output/status needed to end this run, so a later resume fails closed.
- a batch with an execution/review failure before merge: persist its known failed groups; successful-but-discarded worktree groups remain non-terminal and therefore fail closed rather than pretending their side effects were committed.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
mvn test -Dtest=AgentOrchestratorTest,TeamSchedulerTest -DskipTests=false
```

Expected: existing Team scheduling behavior passes; new event-order and duplicate atomicity tests pass.

Commit:

```bash
git add src/main/java/com/mindcli/agent/team/AgentOrchestrator.java src/test/java/com/mindcli/agent/team/AgentOrchestratorTest.java
git commit -m "fix: align team checkpoints with merge commits"
```

---

### Task 6: Runtime, adapter, and CLI recovery wiring

**Files:**

- Modify: `src/main/java/com/mindcli/runtime/run/mode/TeamModeAdapter.java`
- Modify: `src/main/java/com/mindcli/runtime/run/AgentRuntime.java`
- Modify: `src/main/java/com/mindcli/app/cli/command/RunCommandHandler.java`
- Modify: `src/test/java/com/mindcli/runtime/run/mode/AgentRuntimeTest.java`
- Modify: `src/test/java/com/mindcli/app/cli/runtime/CliRunResumerTest.java`
- Modify: `src/test/java/com/mindcli/app/cli/MainCommandHandlerRefactorTest.java`

**Interfaces:**

- Consumes: `AgentOrchestrator.runRecovered(...)` and `RunRecoveryService.reconstructTeamState(...)`.
- Produces: `TeamModeAdapter.executeRecovered(AgentRunContext, RunStore, TeamResumeState)`.
- Preserves: existing `/run resume <runId> [--confirm]` syntax.

- [ ] **Step 1: Write failing runtime and CLI tests**

Add these two `AgentRuntimeTest` cases for successful and unsafe Team recovery:

```java
@Test
void resumesTeamThroughRecoveredAdapterWithoutDuplicateResumeMarker() {
    InMemoryRunStore store = new InMemoryRunStore();
    AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "resume team", tempDir.toString());
    TeamResumeState state = new TeamResumeState(true, 1, 1, List.of(
            new TeamStepResumeState("step_1", "done", "ANALYSIS", List.of(), List.of(),
                    "", "low", "COMPLETED", "", 0, "result", "", List.of())), "");
    appendTeamLedger(store, context, state, "COMPLETED", "", "");
    ToolRegistry registry = new ToolRegistry();
    registry.setProjectPath(tempDir.toString());
    LlmClient llm = new ScriptedClient(List.of());
    MemoryManager memory = new MemoryManager(llm, llm.maxContextWindow(),
            new LongTermMemory(tempDir.resolve("memory").toFile()));
    TeamModeAdapter teamAdapter = new TeamModeAdapter(new AgentOrchestrator(llm, registry, memory,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), store));

    AgentRunResult result = new AgentRuntime(store).resume(context.runId(), teamAdapter);

    assertEquals(AgentRunStatus.SUCCESS, result.status());
    assertEquals(1, store.events(context.runId()).stream()
            .filter(e -> e.type() == AgentRunEventType.RUN_RESUMED).count());
}

@Test
void doesNotAppendRunResumedWhenTeamStateIsUnsafe() {
    InMemoryRunStore store = new InMemoryRunStore();
    AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "resume team", tempDir.toString());
    TeamResumeState state = new TeamResumeState(true, 1, 1, List.of(
            new TeamStepResumeState("step_1", "write", "FILE_WRITE", List.of(),
                    List.of("write_file"), "worker", "high", "PENDING", "", 0, "", "", List.of())), "");
    appendTeamLedger(store, context, state, "RUNNING", "AWAITING_MERGE", "");
    TeamModeAdapter teamAdapter = new TeamModeAdapter((ContextualLegacyAgentRunner) (c, s) -> "unused");

    AgentRunResult result = new AgentRuntime(store).resume(context.runId(), teamAdapter);

    assertEquals(AgentRunStatus.FAILED, result.status());
    assertTrue(store.events(context.runId()).stream()
            .noneMatch(e -> e.type() == AgentRunEventType.RUN_RESUMED));
}

private static void appendTeamLedger(InMemoryRunStore store, AgentRunContext context,
                                     TeamResumeState state, String status, String phase, String childRunId) {
    TeamCheckpointCodec codec = new TeamCheckpointCodec();
    store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED, Map.of("input", context.input())));
    store.append(AgentRunEvent.of(context, AgentRunEventType.TEAM_PLAN_DEFINED, Map.of(
            "schemaVersion", "1", "planVersion", "1", "planJson", codec.encodePlan(state))));
    store.append(AgentRunEvent.of(context, AgentRunEventType.TEAM_STEP_CHECKPOINT, Map.of(
            "schemaVersion", "1", "planVersion", "1",
            "stepIdsJson", codec.encodeStepIds(List.of("step_1")),
            "stepStatus", status, "phase", phase, "attempt", "0",
            "childRunId", childRunId, "result", "result", "error", "")));
    store.append(AgentRunEvent.of(context, AgentRunEventType.RUN_CANCELLED));
}
```

Add CLI assertions that inspect prints `Recovery risk:` and `Recovery reason:`, HIGH requires confirmation, and UNKNOWN remains blocked with `--confirm`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn test -Dtest=AgentRuntimeTest,CliRunResumerTest,MainCommandHandlerRefactorTest -DskipTests=false
```

Expected: Team adapter has no recovered entry point and inspect omits risk/reason.

- [ ] **Step 3: Add the Team adapter seam**

Mirror `PlanModeAdapter` by retaining a concrete orchestrator field:

```java
public AgentRunResult executeRecovered(AgentRunContext context, RunStore runStore,
                                       TeamResumeState state) {
    if (orchestrator == null) {
        return AgentRunResult.failed(context, "Team adapter 不支持 checkpoint 恢复");
    }
    try {
        return resultFromContent(context, orchestrator.runRecovered(context, runStore, state));
    } catch (Exception e) {
        return AgentRunResult.failed(context, errorMessage(e));
    }
}
```

Compatibility constructors keep `orchestrator=null` and continue supporting normal execution.

- [ ] **Step 4: Route Team recovery before RUN_RESUMED and expose diagnostics**

In `AgentRuntime.resumeLocked`, reconstruct Team state beside ReAct and Plan. If unavailable, return failed immediately. Only then append `RUN_RESUMED`; dispatch with:

```java
} else if (adapter instanceof TeamModeAdapter teamAdapter) {
    result = teamAdapter.executeRecovered(context, runStore, recoveredTeamState);
}
```

In `RunCommandHandler.printRunInspect`, print:

```java
out.println("   Recovery risk: " + plan.resumePlan().risk());
out.println("   Recovery reason: " + plan.resumePlan().reason());
```

Do not alter parser behavior or the `--confirm` gate.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
mvn test -Dtest=AgentRuntimeTest,CliRunResumerTest,MainCommandHandlerRefactorTest -DskipTests=false
```

Expected: selected runtime/CLI tests pass, unsafe Team state never receives `RUN_RESUMED`.

Commit:

```bash
git add src/main/java/com/mindcli/runtime/run/mode/TeamModeAdapter.java src/main/java/com/mindcli/runtime/run/AgentRuntime.java src/main/java/com/mindcli/app/cli/command/RunCommandHandler.java src/test/java/com/mindcli/runtime/run/mode/AgentRuntimeTest.java src/test/java/com/mindcli/app/cli/runtime/CliRunResumerTest.java src/test/java/com/mindcli/app/cli/MainCommandHandlerRefactorTest.java
git commit -m "feat: resume team runs from checkpoints"
```

---

### Task 7: Offline fault-injection evaluation and documentation

**Files:**

- Create: `src/test/java/com/mindcli/eval/TeamExactResumeEvalTest.java`
- Modify: `README.md`
- Modify: `ROADMAP.md`
- Modify: `AGENTS.md`

**Interfaces:**

- Consumes: complete Team exact-resume path from Tasks 1–6.
- Produces: deterministic regression coverage across a reopened `JsonlRunStore`.
- Produces: delivered-state documentation only after all tests pass.

- [ ] **Step 1: Write the failing reopened-store acceptance evaluation**

Create `TeamExactResumeEvalTest`. The primary case runs two dependent Team steps, cancels immediately after the first group `COMPLETED` checkpoint, reopens the JSONL store, and resumes:

```java
@Test
void resumesRemainingStepAfterStoreReopenWithoutRepeatingCompletedStep(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Files.writeString(workspace.resolve("marker.txt"), "marker", StandardCharsets.UTF_8);
    Path runsRoot = root.resolve("runs");
    String runId = "run-team-exact-resume";
    AgentRunContext context = new AgentRunContext(runId, AgentMode.TEAM,
            "read marker then write result", workspace.toString(), Instant.now(), Map.of());
    JsonlRunStore initialStore = new JsonlRunStore(runsRoot);
    CancellationToken token = CancellationContext.startRun();
    AgentRunResult first;
    try {
        RunStore cancelling = new CancelOnTeamCheckpointRunStore(initialStore, token, "step_1");
        ScriptedClient initialLlm = ScriptedClient.sequence(
                response("""{"summary":"two steps","steps":[
                  {"id":"read","description":"read marker","type":"FILE_READ","dependencies":[],
                   "requiredTools":["read_file"]},
                  {"id":"write","description":"write result","type":"FILE_WRITE","dependencies":["read"],
                   "requiredTools":["write_file"],"riskLevel":"medium"}
                ]}"""),
                toolResponse("read", "read_marker", "read_file", "{\"path\":\"marker.txt\"}"),
                response("marker read"),
                response("{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}"));
        first = new AgentRuntime(cancelling).run(context,
                new TeamModeAdapter(teamAgent(initialLlm, cancelling, workspace)));
    } finally {
        CancellationContext.clear(token);
    }
    assertEquals(AgentRunStatus.CANCELLED, first.status());

    JsonlRunStore reopened = new JsonlRunStore(runsRoot);
    ScriptedClient resumeLlm = ScriptedClient.sequence(
            toolResponse("write", "write_result", "write_file",
                    "{\"path\":\"result.txt\",\"content\":\"done\"}"),
            response("result written"),
            response("{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}"));
    AgentRunResult resumed = new AgentRuntime(reopened).resume(runId,
            new TeamModeAdapter(teamAgent(resumeLlm, reopened, workspace)));

    assertEquals(AgentRunStatus.SUCCESS, resumed.status(), resumed.errorMessage());
    assertEquals(1, childToolCount(reopened, runId, "read_marker"));
    assertEquals(1, childToolCount(reopened, runId, "write_result"));
    assertEquals("done", Files.readString(workspace.resolve("result.txt")));
}
```

Because Team tool events live under child run IDs, `childToolCount` must read child IDs from parent `TEAM_STEP_CHECKPOINT` events and then query `store.events(childRunId)`; do not scan filesystem paths in the test.

Add these concrete support methods/classes to the same test so it has no cross-test dependency.

```java
private static AgentOrchestrator teamAgent(LlmClient llm, RunStore store, Path workspace) {
    ToolRegistry registry = new ToolRegistry();
    registry.setProjectPath(workspace.toString());
    MemoryManager memory = new MemoryManager(llm, llm.maxContextWindow(),
            new LongTermMemory(workspace.resolve(".eval-memory").toFile()));
    return new AgentOrchestrator(llm, registry, memory,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), store);
}

private static long childToolCount(RunStore store, String parentRunId, String toolId) {
    return store.events(parentRunId).stream()
            .filter(e -> e.type() == AgentRunEventType.TEAM_STEP_CHECKPOINT)
            .map(e -> e.attributes().getOrDefault("childRunId", ""))
            .filter(id -> !id.isBlank()).distinct()
            .flatMap(id -> store.events(id).stream())
            .filter(e -> e.type() == AgentRunEventType.TOOL_OUTCOME)
            .filter(e -> toolId.equals(e.attributes().get("toolId")))
            .filter(e -> "COMPLETED".equals(e.attributes().get("status"))).count();
}

private static final class CancelOnTeamCheckpointRunStore implements RunStore {
    private final RunStore delegate;
    private final CancellationToken token;
    private final String stepId;

    private CancelOnTeamCheckpointRunStore(RunStore delegate, CancellationToken token, String stepId) {
        this.delegate = delegate;
        this.token = token;
        this.stepId = stepId;
    }

    @Override
    public void append(AgentRunEvent event) {
        delegate.append(event);
        if (event.type() == AgentRunEventType.TEAM_STEP_CHECKPOINT
                && "COMPLETED".equals(event.attributes().get("stepStatus"))) {
            List<String> ids = new TeamCheckpointCodec()
                    .decodeStepIds(event.attributes().get("stepIdsJson"));
            if (ids.contains(stepId)) token.cancel();
        }
    }

    @Override public List<AgentRunEvent> events(String runId) { return delegate.events(runId); }
}

private static LlmClient.ChatResponse response(String content) {
    return new LlmClient.ChatResponse("assistant", content, null, 10, 5);
}

private static LlmClient.ChatResponse toolResponse(String content, String id, String name,
                                                   String argumentsJson) {
    return new LlmClient.ChatResponse("assistant", content,
            List.of(new LlmClient.ToolCall(id,
                    new LlmClient.ToolCall.Function(name, argumentsJson))), 10, 5);
}

private static final class ScriptedClient implements LlmClient {
    private final Queue<LlmClient.ChatResponse> responses;

    private ScriptedClient(List<LlmClient.ChatResponse> responses) {
        this.responses = new ArrayDeque<>(responses);
    }

    static ScriptedClient sequence(LlmClient.ChatResponse... responses) {
        return new ScriptedClient(List.of(responses));
    }

    @Override
    public LlmClient.ChatResponse chat(List<LlmClient.Message> messages,
                                       List<LlmClient.Tool> tools) throws IOException {
        return chat(messages, tools, LlmClient.StreamListener.NO_OP);
    }

    @Override
    public synchronized LlmClient.ChatResponse chat(List<LlmClient.Message> messages,
                                                    List<LlmClient.Tool> tools,
                                                    LlmClient.StreamListener listener) throws IOException {
        LlmClient.ChatResponse response = responses.poll();
        if (response == null) throw new IOException("unexpected LLM call");
        if (response.content() != null && !response.content().isEmpty()) {
            listener.onContentDelta(response.content());
        }
        return response;
    }

    @Override public String getProviderName() { return "eval"; }
    @Override public String getModelName() { return "eval-scripted"; }
}
```

- [ ] **Step 2: Add the six safety scenarios**

Add deterministic cases for:

1. successful write before terminal checkpoint → resume refused;
2. review child started but parent not completed → resume refused;
3. `AWAITING_MERGE` → resume refused;
4. merge completed and terminal checkpoint exists → write not repeated;
5. leader/duplicate group checkpoint → duplicate not executed;
6. child tool request without matching outcome → resume refused even with confirmation.

Every test must assert both final workspace contents and ledger facts (`RUN_RESUMED` count, child tool count, or absence of terminal checkpoint).

- [ ] **Step 3: Run the focused recovery suite**

Run:

```bash
mvn test -Dtest=TeamCheckpointCodecTest,RunRecoveryServiceTest,SubAgentTest,AgentOrchestratorTest,AgentRuntimeTest,CliRunResumerTest,MainCommandHandlerRefactorTest,TeamExactResumeEvalTest -DskipTests=false
```

Expected: all selected tests pass without network or API keys.

- [ ] **Step 4: Update delivered-state documentation**

Update all three documents consistently:

- `AGENTS.md`: Team parent step checkpoint, child request evidence, skip-terminal behavior, worktree merge boundary, and fail-closed phases.
- `README.md`: recovery table now reads ReAct tool-call idempotency, Plan task boundary, Team step boundary.
- `ROADMAP.md`: mark Team step exact recovery first phase complete; retain child internal tool-loop recovery and automatic worktree takeover as future work.

Do not claim child-internal or cross-machine recovery.

- [ ] **Step 5: Run full verification**

Run:

```bash
mvn test -Pquick -DskipTests=false
git diff --check
git status --short
```

Expected: quick profile passes with zero failures/errors; `git diff --check` emits no errors; status contains only intended Team recovery changes plus the five pre-existing untracked `html/*.html` files.

- [ ] **Step 6: Commit evaluation and docs**

```bash
git add src/test/java/com/mindcli/eval/TeamExactResumeEvalTest.java AGENTS.md README.md ROADMAP.md
git commit -m "test: verify team exact resume boundaries"
```

- [ ] **Step 7: Final verification after commit**

Run:

```bash
git log -7 --oneline
git status --short
```

Expected: seven Team recovery commits are visible after the design/plan commits, and no intended source or test changes remain uncommitted.
