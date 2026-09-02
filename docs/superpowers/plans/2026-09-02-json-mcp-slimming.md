# JSON and MCP Slimming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Centralize Jackson mapper creation and reduce `McpServerManager` responsibilities without changing its public behavior or MCP protocol implementation.

**Architecture:** `JsonSupport` becomes the single production mapper factory. `McpServerManager` remains the public facade; startup and transport creation move behind package-private lifecycle modules, while content adaptation is extracted only if it materially reduces manager complexity.

**Tech Stack:** Java 17, Maven, Jackson 2.16/2.21, official MCP Java SDK 2.0.1, JUnit 5.

## Global Constraints

- Preserve all existing public `McpServerManager` methods, tool names, resource formats, and lifecycle behavior.
- Do not replace the official MCP SDK or add Spring/Picocli/dotenv/LSP4J.
- Keep shared `ObjectMapper` immutable after construction; constructor-injected mappers remain supported.
- Do not modify `.env`, real API keys, `target/`, or unrelated existing HTML artifacts.
- Run focused tests after each task and `mvn test -Pquick` before completion.

---

### Task 1: Add shared JSON support

**Files:**
- Create: `src/main/java/com/mindcli/platform/serialization/JsonSupport.java`
- Create: `src/test/java/com/mindcli/platform/serialization/JsonSupportTest.java`

**Interfaces:**
- Produces `JsonSupport.mapper()`, `JsonSupport.prettyMapper()`, and `JsonSupport.newMapper()`.

- [ ] **Step 1: Write the failing test**

Add tests asserting `mapper()` is shared, `prettyMapper()` emits indented JSON, and `newMapper()` returns an independent mapper.

- [ ] **Step 2: Run the focused test**

Run: `mvn test -Dtest=JsonSupportTest -DskipTests=false`

Expected: compilation/test failure because `JsonSupport` does not exist.

- [ ] **Step 3: Implement the minimal module**

Create a final utility with eagerly initialized mappers:

```java
public final class JsonSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static ObjectMapper mapper() { return MAPPER; }
    public static ObjectMapper prettyMapper() { return PRETTY_MAPPER; }
    public static ObjectMapper newMapper() { return new ObjectMapper(); }
    private JsonSupport() {}
}
```

- [ ] **Step 4: Run the focused test**

Run: `mvn test -Dtest=JsonSupportTest -DskipTests=false`

Expected: PASS.

### Task 2: Migrate production mapper construction

**Files:**
- Modify all production files currently declaring `new ObjectMapper()` (MCP, Agent, Memory, Web, Runtime, HITL, Browser, Skill, rendering, and tool packages).
- Modify: `src/main/java/com/mindcli/platform/config/MindCliConfig.java` to use `JsonSupport.prettyMapper()`.
- Modify: `src/main/java/com/mindcli/agent/plan/PlanSchemaParser.java` and `PlanRepairer.java` to use `JsonSupport.mapper()` only for null defaults.

**Interfaces:**
- Existing classes retain their fields and constructors; only mapper source changes.

- [ ] **Step 1: Replace default construction**

Change static/default expressions such as `new ObjectMapper()` to `JsonSupport.mapper()`, and the explicit pretty configuration to `JsonSupport.prettyMapper()`. Keep test-local mappers unchanged.

- [ ] **Step 2: Migrate MCP SDK mapper creation**

In `McpServerManager`, construct `JacksonMcpJsonMapper(JsonSupport.mapper())`; do not alter transport or SDK calls.

- [ ] **Step 3: Compile and run JSON-adjacent tests**

Run: `mvn -DskipTests compile`

Then run: `mvn test -Dtest=McpClientTest,McpServerManagerTest,JsonlRunStoreTest,MemoryManagerTest,ToolRegistryTest -DskipTests=false`

Expected: BUILD SUCCESS and all selected tests pass.

### Task 3: Extract MCP startup coordination

**Files:**
- Create: `src/main/java/com/mindcli/capability/mcp/lifecycle/McpStartupCoordinator.java`
- Modify: `src/main/java/com/mindcli/capability/mcp/McpServerManager.java`
- Modify: `src/test/java/com/mindcli/capability/mcp/McpServerManagerTest.java` only if a focused seam test is needed.

**Interfaces:**
- Package-private coordinator method accepts the target servers, progress stream, optional max wait, and `Consumer<McpServer>` start action; it owns only the daemon executor and wait/progress policy.

- [ ] **Step 1: Add a focused coordinator test or characterization test**

Cover bounded timeout returning while a server remains `STARTING`, and completed futures reaching `READY`/`ERROR` before the timeout.

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run: `mvn test -Dtest=McpStartupCoordinatorTest -DskipTests=false`

Expected: failure until the coordinator exists.

- [ ] **Step 3: Move startup code without changing semantics**

Move the current `startAll`, timeout notice, progress printer, and elapsed formatting logic into the coordinator. `McpServerManager.startAll(...)` delegates to it with `this::start`; existing overloads remain unchanged.

- [ ] **Step 4: Run MCP tests**

Run: `mvn test -Dtest=McpStartupCoordinatorTest,McpServerManagerTest,McpClientTest -DskipTests=false`

Expected: PASS.

### Task 4: Extract MCP transport creation

**Files:**
- Create: `src/main/java/com/mindcli/capability/mcp/lifecycle/McpTransportFactory.java`
- Modify: `src/main/java/com/mindcli/capability/mcp/McpServerManager.java`
- Reuse: `src/main/java/com/mindcli/capability/mcp/transport/MindCliStdioClientTransport.java`

**Interfaces:**
- Package-private `create(McpServer server)` returns the existing MindCLI `McpClient` wrapper and retains official stdio/Streamable HTTP transports.

- [ ] **Step 1: Add characterization coverage**

Exercise stdio and HTTP configuration through existing `McpServerManagerTest` fixtures; assert transport names and failure messages remain unchanged.

- [ ] **Step 2: Extract implementation**

Move only `createOfficialClient` and its direct helper (`resolveCommand`) into the factory. Inject project directory and the configured `McpConfigLoader`; call `prepare` in the same place and preserve all exception conversion.

- [ ] **Step 3: Delegate from manager and run tests**

Run: `mvn test -Dtest=McpServerManagerTest,McpClientTest,MindCliStdioClientTransportTest -DskipTests=false`

Expected: PASS with unchanged status and error output.

### Task 5: Evaluate content adapter and finish verification

**Files:**
- Modify: `src/main/java/com/mindcli/capability/mcp/McpServerManager.java`
- Optional create: `src/main/java/com/mindcli/capability/mcp/lifecycle/McpServerContentAdapter.java` only if extraction removes substantial logic.
- Update: `ROADMAP.md` and `AGENTS.md` only if the observable architecture or maintenance rule changes.

- [ ] **Step 1: Measure manager responsibilities**

Review remaining methods. If tool/resource/prompt conversion can move behind one coherent package-private interface without adding pass-through methods, extract it; otherwise keep the logic local and record the decision in the implementation commit.

- [ ] **Step 2: Run full verification**

Run:

```text
mvn test -Pquick -Dmindcli.runs.dir=target/test-runs-json-mcp-slimming
mvn -DskipTests compile
git diff --check
```

Expected: quick profile reports 0 failures and 0 errors; compile succeeds; diff check is clean.

- [ ] **Step 3: Inspect final changes**

Run: `git status --short` and `git diff --stat`.

Confirm no `.env`, API keys, `target/`, or unrelated generated files were changed.

