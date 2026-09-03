# Agent Outcome Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 MindCLI 增加 8 个离线、确定性的端到端 Agent 结果评测场景，同时验证最终工作区结果、安全边界和 RunStore 账本不变量。

**Architecture:** 所有新增代码只位于 `src/test/java/com/mindcli/eval/`。`AgentEvalFixture` 统一组装真实 `AgentRuntime`、mode adapter、`ToolRegistry`、`InMemoryRunStore` 与临时工作区；测试只用 scripted LLM 固定响应，并优先断言文件结果，再断言 ledger trace。

**Tech Stack:** Java 17、JUnit 5、现有 MindCLI Agent Runtime、现有 Maven Surefire `quick` profile。

## Global Constraints

- 不调用真实模型、网络服务或 API Key。
- 不新增生产代码、生产依赖、YAML DSL、数据库、Web 看板或 LLM Judge。
- 使用 `@TempDir` 隔离工作区，不写入真实项目业务文件。
- 不修改 ReAct、Plan、Team 的生产行为来迎合测试。
- 每个场景同时验证至少一个最终 Outcome 和对应 ledger invariant。
- 评测不断言 reasoning 文本、唯一搜索顺序或完整自然语言回复。
- 全部场景必须进入现有 `mvn test -Pquick -DskipTests=false`。
- 保留工作区既有未提交修改；每次提交只暂存本任务文件。

---

### Task 1: Shared Evaluation Fixture

**Files:**
- Create: `src/test/java/com/mindcli/eval/AgentEvalFixture.java`
- Create: `src/test/java/com/mindcli/eval/AgentEvalFixtureTest.java`

**Interfaces:**
- Consumes: `AgentRuntime.run(AgentRunContext, ModeAdapter)`、`ToolRegistry.setProjectPath(String)`、`InMemoryRunStore.events(String)`。
- Produces: `AgentEvalFixture.workspace(Path, Map<String,String>)`、`runReact(ScriptedLlmClient,String)`、`runPlan(ScriptedLlmClient,String)`、`runTeam(ScriptedLlmClient,String)`、嵌套 `ScriptedLlmClient` 与 `AgentEvalResult`。

- [ ] **Step 1: Write the failing fixture smoke test**

```java
@Test
void createsIsolatedWorkspaceAndRunsReactThroughAgentRuntime(@TempDir Path root) throws Exception {
    AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of("src/App.java", "class App {}\n"));
    AgentEvalFixture.ScriptedLlmClient llm = AgentEvalFixture.ScriptedLlmClient.sequence(
            AgentEvalFixture.response("done"));

    AgentEvalFixture.AgentEvalResult result = fixture.runReact(llm, "inspect app");

    assertEquals("class App {}\n", result.read("src/App.java"));
    assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
    assertEquals(List.of(AgentRunEventType.RUN_STARTED, AgentRunEventType.MODE_SELECTED,
                    AgentRunEventType.LLM_REQUEST, AgentRunEventType.LLM_RESPONSE,
                    AgentRunEventType.RUN_FINISHED),
            result.events().stream().map(AgentRunEvent::type).toList());
}
```

- [ ] **Step 2: Run the smoke test and verify RED**

Run: `mvn test -Dtest=AgentEvalFixtureTest -DskipTests=false`

Expected: compilation fails because `AgentEvalFixture` does not exist.

- [ ] **Step 3: Implement the minimal fixture**

```java
final class AgentEvalFixture {
    static AgentEvalFixture workspace(Path root, Map<String, String> files) throws IOException;
    AgentEvalResult runReact(ScriptedLlmClient llm, String prompt);
    AgentEvalResult runPlan(ScriptedLlmClient llm, String prompt);
    AgentEvalResult runTeam(ScriptedLlmClient llm, String prompt);
    static LlmClient.ChatResponse response(String content);
    static LlmClient.ChatResponse toolResponse(String content, String id, String name, String argumentsJson);

    record AgentEvalResult(Path workspace, AgentRunResult runResult, List<AgentRunEvent> events) {
        String read(String relativePath) throws IOException;
        List<String> files() throws IOException;
        long successfulToolCalls(String toolName);
        List<AgentRunEvent> toolOutcomes(String toolName);
    }

    static final class ScriptedLlmClient implements LlmClient {
        static ScriptedLlmClient sequence(LlmClient.ChatResponse... responses);
        static ScriptedLlmClient dispatch(Function<String, LlmClient.ChatResponse> dispatcher);
        int calls();
    }
}
```

Implementation details:

- `workspace` 创建所有父目录并写入 UTF-8 内容。
- 每个 `run*` 创建新的 `InMemoryRunStore` 和 `AgentRunContext`，统一从 `AgentRuntime` 进入对应 adapter。
- ReAct 使用 `new Agent(llm, registry, store)`。
- Plan 使用自动批准的 `PlanReviewHandler` 和静默 `PrintStream`。
- Team 使用测试目录下的 `LongTermMemory`、静默 `PrintStream` 和同一 store。
- scripted client 的 sequence 用线程安全队列；无响应时抛出带最后 user 消息的 `IOException`。
- `dispatch` 依据最后一条 user 消息返回响应，适配 Team 并发 child 调用。

- [ ] **Step 4: Run the smoke test and verify GREEN**

Run: `mvn test -Dtest=AgentEvalFixtureTest -DskipTests=false`

Expected: 1 test passes, 0 failures, 0 errors.

- [ ] **Step 5: Commit the fixture**

```bash
git add src/test/java/com/mindcli/eval/AgentEvalFixture.java src/test/java/com/mindcli/eval/AgentEvalFixtureTest.java
git commit -m "test: add agent outcome evaluation fixture"
```

### Task 2: ReAct Outcome Scenarios

**Files:**
- Create: `src/test/java/com/mindcli/eval/ReactOutcomeEvalTest.java`

**Interfaces:**
- Consumes: `AgentEvalFixture.workspace(...)`、`toolResponse(...)`、`runReact(...)`、`AgentEvalResult.read/files/successfulToolCalls/toolOutcomes`。
- Produces: 两个 ReAct 评测：实时代码定位与读取、单文件安全修改。

- [ ] **Step 1: Write the two ReAct tests**

```java
@Test
void locatesAndReadsTheMatchingImplementation(@TempDir Path root) throws Exception {
    AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of(
            "src/FastHasher.java", "class FastHasher { String marker = \"TARGET_IMPL\"; }\n",
            "src/LegacyHasher.java", "class LegacyHasher { String marker = \"legacy\"; }\n"));
    var llm = AgentEvalFixture.ScriptedLlmClient.sequence(
            AgentEvalFixture.toolResponse("search", "grep_1", "grep_code",
                    "{\"query\":\"TARGET_IMPL\",\"path\":\"src\"}"),
            AgentEvalFixture.toolResponse("read", "read_1", "read_file",
                    "{\"path\":\"src/FastHasher.java\"}"),
            AgentEvalFixture.response("found"));

    var result = fixture.runReact(llm, "find TARGET_IMPL");

    assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
    assertEquals(1, result.successfulToolCalls("grep_code"));
    assertEquals(1, result.successfulToolCalls("read_file"));
    assertTrue(result.toolOutcomes("read_file").get(0).attributes().get("text").contains("TARGET_IMPL"));
}

@Test
void changesOnlyTheRequestedFileAndWritesOnce(@TempDir Path root) throws Exception {
    AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of(
            "src/App.java", "class App { int port = 1; }\n",
            "src/Keep.java", "class Keep {}\n"));
    var before = fixture.snapshotFiles();
    var llm = AgentEvalFixture.ScriptedLlmClient.sequence(
            AgentEvalFixture.toolResponse("read", "read_1", "read_file", "{\"path\":\"src/App.java\"}"),
            AgentEvalFixture.toolResponse("write", "write_1", "write_file",
                    "{\"path\":\"src/App.java\",\"content\":\"class App { int port = 2; }\\n\"}"),
            AgentEvalFixture.response("updated"));

    var result = fixture.runReact(llm, "change port to 2");

    assertEquals("class App { int port = 2; }\n", result.read("src/App.java"));
    assertEquals("class Keep {}\n", result.read("src/Keep.java"));
    assertEquals(List.of("src/App.java", "src/Keep.java"), result.files());
    assertEquals(1, result.successfulToolCalls("write_file"));
    assertEquals(before.keySet(), result.snapshotFiles().keySet());
}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn test -Dtest=ReactOutcomeEvalTest -DskipTests=false`

Expected: tests fail until fixture exposes `snapshotFiles()` and real tool outcomes are asserted correctly.

- [ ] **Step 3: Add only the missing fixture helper and align assertions to real output**

```java
Map<String, String> snapshotFiles() throws IOException {
    try (Stream<Path> paths = Files.walk(workspace)) {
        return paths.filter(Files::isRegularFile)
                .collect(toMap(this::relativeName, this::readPath,
                        (left, right) -> right, TreeMap::new));
    }
}
```

- [ ] **Step 4: Run and verify GREEN**

Run: `mvn test -Dtest=AgentEvalFixtureTest,ReactOutcomeEvalTest -DskipTests=false`

Expected: 3 tests pass, 0 failures, 0 errors.

- [ ] **Step 5: Commit ReAct scenarios**

```bash
git add src/test/java/com/mindcli/eval/AgentEvalFixture.java src/test/java/com/mindcli/eval/ReactOutcomeEvalTest.java
git commit -m "test: evaluate react workspace outcomes"
```

### Task 3: Plan Outcome Scenarios

**Files:**
- Modify: `src/test/java/com/mindcli/eval/AgentEvalFixture.java`
- Create: `src/test/java/com/mindcli/eval/PlanOutcomeEvalTest.java`

**Interfaces:**
- Consumes: fixture Plan assembly and `AgentEvalResult.events()`.
- Produces: `runPlan(LlmClient, Planner, String)` overload used to supply deterministic plans; DAG ordering and explicit SKIP scenarios.

- [ ] **Step 1: Write the Plan tests with explicit plans**

```java
@Test
void executesDependentTaskOnlyAfterProducerCompletes(@TempDir Path root) throws Exception {
    AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of());
    Task producer = new Task("produce", "write input.txt", Task.TaskType.FILE_WRITE);
    Task consumer = new Task("consume", "read input.txt", Task.TaskType.ANALYSIS, List.of("produce"));
    ExecutionPlan plan = AgentEvalFixture.plan("dag", producer, consumer);
    var llm = AgentEvalFixture.ScriptedLlmClient.sequence(
            AgentEvalFixture.toolResponse("write", "write_1", "write_file",
                    "{\"path\":\"input.txt\",\"content\":\"ready\"}"),
            AgentEvalFixture.response("producer done"),
            AgentEvalFixture.toolResponse("read", "read_1", "read_file", "{\"path\":\"input.txt\"}"),
            AgentEvalFixture.response("consumer done"));

    var result = fixture.runPlan(llm, AgentEvalFixture.fixedPlanner(llm, plan), "prepare and consume");

    assertEquals("ready", result.read("input.txt"));
    assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
    assertTrue(result.firstToolOutcomeIndex("write_1") < result.firstToolOutcomeIndex("read_1"));
}

@Test
void skipsExplicitlyDegradableTaskAndContinuesDownstream(@TempDir Path root) {
    AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of());
    Task optional = new Task("optional", "optional context", Task.TaskType.ANALYSIS);
    optional.setCritical(false);
    optional.setDegradation("SKIP");
    optional.setMaxRetries(0);
    Task downstream = new Task("downstream", "continue summary", Task.TaskType.ANALYSIS, List.of("optional"));
    ExecutionPlan plan = AgentEvalFixture.plan("skip", optional, downstream);
    var llm = AgentEvalFixture.ScriptedLlmClient.failingThen(
            new IOException("optional unavailable"), AgentEvalFixture.response("downstream done"));

    var result = fixture.runPlan(llm, AgentEvalFixture.fixedPlanner(llm, plan), "degrade safely");

    assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
    assertEquals(Task.TaskStatus.SKIPPED, optional.getStatus());
    assertEquals(Task.TaskStatus.COMPLETED, downstream.getStatus());
    assertTrue(result.events().stream().anyMatch(event -> event.type() == AgentRunEventType.RUN_FINISHED));
}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn test -Dtest=PlanOutcomeEvalTest -DskipTests=false`

Expected: compilation fails because the explicit planner overload and event-index helper do not exist.

- [ ] **Step 3: Implement the minimal Plan fixture seam**

```java
AgentEvalResult runPlan(LlmClient llm, Planner planner, String prompt) {
    PlanExecuteAgent agent = new PlanExecuteAgent(llm, registry, planner, memoryManager(llm),
            (goal, candidate) -> PlanExecuteAgent.PlanReviewDecision.execute(), silentOut, store);
    return run(AgentMode.PLAN, prompt, new PlanModeAdapter(agent), store);
}

static ExecutionPlan plan(String id, Task... tasks) {
    ExecutionPlan plan = new ExecutionPlan(id, id);
    for (Task task : tasks) plan.addTask(task);
    plan.computeExecutionOrder();
    return plan;
}

int firstToolOutcomeIndex(String toolId) {
    for (int i = 0; i < events.size(); i++)
        if (events.get(i).type() == AgentRunEventType.TOOL_OUTCOME
                && toolId.equals(events.get(i).attributes().get("toolId"))) return i;
    return -1;
}
```

- [ ] **Step 4: Run and verify GREEN**

Run: `mvn test -Dtest=AgentEvalFixtureTest,PlanOutcomeEvalTest -DskipTests=false`

Expected: 3 tests pass, 0 failures, 0 errors.

- [ ] **Step 5: Commit Plan scenarios**

```bash
git add src/test/java/com/mindcli/eval/AgentEvalFixture.java src/test/java/com/mindcli/eval/PlanOutcomeEvalTest.java
git commit -m "test: evaluate plan execution outcomes"
```

### Task 4: Team Outcome Scenarios

**Files:**
- Create: `src/test/java/com/mindcli/eval/TeamOutcomeEvalTest.java`

**Interfaces:**
- Consumes: fixture Team assembly、dispatching scripted client、parent and child events recorded in `InMemoryRunStore`.
- Produces: Profile routing/permission scenario and review fail-closed scenario.

- [ ] **Step 1: Write Team tests using planner/execute/review message dispatch**

```java
@Test
void routesReadStepToExplorerAndWriteStepToWorker(@TempDir Path root) throws Exception {
    AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of("input.txt", "seed"));
    var llm = AgentEvalFixture.teamRoutingScript(false);

    var result = fixture.runTeam(llm, "inspect then update");

    assertEquals("updated", result.read("output.txt"));
    assertEquals(AgentRunStatus.SUCCESS, result.runResult().status());
    assertTrue(result.allEvents().stream().anyMatch(AgentEvalFixture::isExplorerExecute));
    assertTrue(result.allEvents().stream().anyMatch(AgentEvalFixture::isWorkerExecute));
    assertTrue(result.allEvents().stream().noneMatch(AgentEvalFixture::isExplorerSuccessfulWrite));
}

@Test
void rejectedReviewsFailClosed(@TempDir Path root) {
    AgentEvalFixture fixture = AgentEvalFixture.workspace(root, Map.of());
    var llm = AgentEvalFixture.teamRoutingScript(true);

    var result = fixture.runTeam(llm, "produce reviewed output");

    assertNotEquals(AgentRunStatus.SUCCESS, result.runResult().status());
    assertTrue(result.allEvents().stream().anyMatch(event ->
            "review".equals(event.attributes().get("phase"))
                    && "false".equals(event.attributes().get("approved"))
                    && "BLOCKED".equals(event.attributes().get("businessStatus"))));
    assertTrue(result.events().stream().noneMatch(event -> event.type() == AgentRunEventType.RUN_FINISHED));
}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn test -Dtest=TeamOutcomeEvalTest -DskipTests=false`

Expected: compilation fails because the fixture does not yet expose all child events or Team scripts.

- [ ] **Step 3: Add minimal Team-specific test helpers**

```java
record AgentEvalResult(Path workspace, AgentRunResult runResult,
                       List<AgentRunEvent> events, List<AgentRunEvent> allEvents) { }

static boolean isExplorerExecute(AgentRunEvent event) {
    return event.type() == AgentRunEventType.AGENT_STARTED
            && "explorer".equals(event.attributes().get("role"))
            && "execute".equals(event.attributes().get("phase"));
}

static boolean isWorkerExecute(AgentRunEvent event) {
    return event.type() == AgentRunEventType.AGENT_STARTED
            && "worker".equals(event.attributes().get("role"))
            && "execute".equals(event.attributes().get("phase"));
}
```

`teamRoutingScript` 返回完整、可解析的 Team planner JSON；execute 响应按步骤描述分别调用 `read_file` 或 `write_file`；review 响应在通过场景返回 `approved=true`，拒绝场景始终返回 `approved=false`。所有响应同时提供 `role/content/reasoning/toolCalls/usage` 的真实完整结构。

- [ ] **Step 4: Run and verify GREEN**

Run: `mvn test -Dtest=TeamOutcomeEvalTest -DskipTests=false`

Expected: 2 tests pass, 0 failures, 0 errors.

- [ ] **Step 5: Commit Team scenarios**

```bash
git add src/test/java/com/mindcli/eval/AgentEvalFixture.java src/test/java/com/mindcli/eval/TeamOutcomeEvalTest.java
git commit -m "test: evaluate team routing and review outcomes"
```

### Task 5: Runtime Safety and Recovery Idempotency Scenarios

**Files:**
- Create: `src/test/java/com/mindcli/eval/RuntimeSafetyEvalTest.java`

**Interfaces:**
- Consumes: real `ToolRegistry` path policy、`ToolDispatcher(ToolInvocationExecutor, RunStore)`、resume metadata and ledger outcomes.
- Produces: denied-write no-side-effect evaluation and replay/collision recovery evaluation.

- [ ] **Step 1: Write the safety and idempotency tests**

```java
@Test
void policyDeniedWriteLeavesOutsideTargetUntouched(@TempDir Path root) throws Exception {
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    Path outside = Files.writeString(root.resolve("outside.txt"), "original");
    AgentEvalFixture fixture = AgentEvalFixture.workspace(workspace, Map.of());
    var llm = AgentEvalFixture.ScriptedLlmClient.sequence(
            AgentEvalFixture.toolResponse("write", "write_outside", "write_file",
                    AgentEvalFixture.writeArgs(outside, "changed")),
            AgentEvalFixture.response("unable"));

    var result = fixture.runReact(llm, "write outside workspace");

    assertEquals("original", Files.readString(outside));
    assertNotEquals("COMPLETED", result.toolOutcome("write_outside").attributes().get("status"));
    assertTrue(Set.of("DENIED_BY_POLICY", "FAILED").contains(
            result.toolOutcome("write_outside").attributes().get("status")));
}

@Test
void resumedDispatchReusesExactOutcomeAndRejectsArgumentCollision(@TempDir Path root) {
    InMemoryRunStore store = new InMemoryRunStore();
    AgentRunContext context = AgentEvalFixture.resumedContext("eval-resume", root);
    AgentEvalFixture.appendCompletedOutcome(store, context, "call_1", "write_file",
            "{\"path\":\"a.txt\",\"content\":\"original\"}", "already written");
    AtomicInteger executions = new AtomicInteger();
    ToolDispatcher dispatcher = new ToolDispatcher(invocation -> {
        executions.incrementAndGet();
        return AgentEvalFixture.completed(invocation, "unexpected");
    }, store);

    ToolOutcome replayed = dispatcher.dispatch(List.of(AgentEvalFixture.toolCall(
            "call_1", "write_file", "{\"path\":\"a.txt\",\"content\":\"original\"}")), context).get(0);
    ToolOutcome collision = dispatcher.dispatch(List.of(AgentEvalFixture.toolCall(
            "call_1", "write_file", "{\"path\":\"a.txt\",\"content\":\"changed\"}")), context).get(0);

    assertEquals(0, executions.get());
    assertEquals(ToolOutcomeStatus.COMPLETED, replayed.status());
    assertEquals("replayed", replayed.metadata().get("idempotency"));
    assertEquals(ToolOutcomeStatus.FAILED, collision.status());
    assertEquals("IDEMPOTENCY_KEY_COLLISION", collision.errorCategory());
}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn test -Dtest=RuntimeSafetyEvalTest -DskipTests=false`

Expected: compilation fails until the small ledger/tool-call helpers exist; the safety assertion may expose the actual registry error category and must retain the no-side-effect assertion.

- [ ] **Step 3: Implement test-only helpers without changing production behavior**

```java
static AgentRunContext resumedContext(String runId, Path workspace) {
    return new AgentRunContext(runId, AgentMode.REACT, "resume", workspace.toString(),
            Instant.now(), Map.of("resumed", "true"));
}

static void appendCompletedOutcome(InMemoryRunStore store, AgentRunContext context,
                                   String id, String name, String args, String text) {
    store.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, Map.of(
            "toolId", id, "toolName", name, "argumentsJson", args,
            "status", ToolOutcomeStatus.COMPLETED.name(), "text", text)));
}
```

- [ ] **Step 4: Run and verify GREEN**

Run: `mvn test -Dtest=RuntimeSafetyEvalTest -DskipTests=false`

Expected: 2 tests pass, 0 failures, 0 errors.

- [ ] **Step 5: Commit safety scenarios**

```bash
git add src/test/java/com/mindcli/eval/AgentEvalFixture.java src/test/java/com/mindcli/eval/RuntimeSafetyEvalTest.java
git commit -m "test: evaluate runtime safety and recovery idempotency"
```

### Task 6: Documentation and Full Verification

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `ROADMAP.md`
- Modify: `docs/superpowers/plans/2026-09-03-agent-outcome-evaluation.md`

**Interfaces:**
- Consumes: all eight test methods and existing Maven quick profile.
- Produces: discoverable evaluation command and documented phase status.

- [ ] **Step 1: Document the evaluation baseline**

Add the following concise facts without rewriting unrelated sections:

```markdown
- Agent 结果评测第一阶段包含 8 个离线确定性场景，覆盖 ReAct、Plan、Team、策略拒绝和恢复幂等；它验证最终工作区 Outcome 与 RunStore ledger 一致，不调用真实模型。
- 针对性运行：`mvn test -Dtest='com.mindcli.eval.*Test' -DskipTests=false`
```

In `ROADMAP.md`, mark only the offline baseline as delivered; keep real-model evaluation and Plan/Team exact recovery as future work.

- [ ] **Step 2: Run focused evaluation verification**

Run: `mvn test -Dtest='com.mindcli.eval.*Test' -DskipTests=false`

Expected: 9 tests pass (1 fixture smoke + 8 evaluation scenarios), 0 failures, 0 errors.

- [ ] **Step 3: Run the required quick regression**

Run: `mvn test -Pquick -DskipTests=false`

Expected: all tests pass, 0 failures, 0 errors; skipped tests may remain the repository's documented optional tests.

- [ ] **Step 4: Verify formatting and scope**

Run: `git diff --check`

Expected: no output and exit code 0.

Run: `git status --short`

Expected: evaluation files and the three synchronized docs are visible alongside preserved pre-existing user changes; no `.env`, API key, `target/`, or unrelated HTML file is staged.

- [ ] **Step 5: Commit the evaluation baseline**

```bash
git add AGENTS.md README.md ROADMAP.md src/test/java/com/mindcli/eval
git add -f docs/superpowers/plans/2026-09-03-agent-outcome-evaluation.md
git commit -m "test: add offline agent outcome evaluation baseline"
```

## Self-Review Result

- Spec coverage: Tasks 2-5 map one-to-one to all 8 required scenarios; Task 1 provides the single shared test module; Task 6 covers Maven integration, docs, and verification.
- Scope: no production source or dependency changes are planned.
- Type consistency: fixture method names and result helpers are introduced before downstream use; Plan uses explicit `Planner`, Team uses dispatch-by-last-user-message, runtime safety uses existing `ToolDispatcher` constructors.
- Placeholder scan: no deferred implementation markers are present.
- Risk control: assertions prioritize file Outcome and structured ledger fields; exact prose and incidental event counts are avoided except for the fixture lifecycle smoke test.
