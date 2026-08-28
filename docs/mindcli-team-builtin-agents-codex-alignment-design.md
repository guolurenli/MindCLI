# Team 内置子代理 Codex 对齐设计

- 日期：2026-08-16
- 范围：`/team`（Multi-Agent）子代理模型第一步重构
- 状态：待实现

## 1. 背景与动机

MindCLI 的 `/team` 模式当前使用四角色子代理模型（`PLANNER` / `EXPLORER` / `WORKER` / `REVIEWER`），其中：

- `PLANNER` 是独立子代理，负责拆解任务；
- `REVIEWER` 已被废弃（默认由 Worker/Explorer 自审替代），但仍残留在枚举与消息工厂中；
- `EXPLORER` / `WORKER` 通过 `.mindcli/config.toml` 或 `.mindcli/agents.json` 定义，而非源码内置。

这与 Codex 的 multi-agent 设计不一致。Codex 使用 3 个内置 agent（`default` / `worker` / `explorer`），其中：

- 规划是主 agent（`default`）的内建动作，不是独立子代理；
- review 由 worker 的 review/fix 循环完成，无独立 reviewer；
- 内置 agent 硬编码在源码，TOML 仅用于自定义 agent。

本设计将 MindCLI 的子代理模型对齐 Codex：**仅保留 worker / explorer 两个内置子代理，拆解收编到 orchestrator，内置定义硬编码**。

## 2. 目标（第一步范围）

1. 角色精简：`AgentRole` 仅保留 `EXPLORER`、`WORKER`。
2. 拆解收编：规划职责从独立 `PLANNER` 子代理收编到 `AgentOrchestrator` 内部。
3. 内置硬编码：`EXPLORER` / `WORKER` 定义硬编码到源码，移除 TOML/JSON 的 team 配置读取。

## 3. 现状 vs 目标

| 维度 | 现状 | 目标 |
|------|------|------|
| 角色模型 | PLANNER / EXPLORER / WORKER / REVIEWER | EXPLORER / WORKER |
| 规划者 | 独立 PLANNER 子代理（TEAM_PLANNER） | orchestrator 内联规划（TEAM_PLANNER prompt） |
| review | Worker/Explorer 自审；REVIEWER 已废 | 保持自审，删除 REVIEWER 残留 |
| 内置定义 | TOML `[team.explorer]` / `[team.worker]` + JSON agents | 源码硬编码（`builtinExplorer` / `builtinWorker`） |

## 4. 目标架构

```
AgentOrchestrator（主 agent）
  ├── 内部直接调 LLM 拆解（TEAM_PLANNER prompt，无 planner 子代理）
  ├── explorers（内置硬编码：explorer#1, explorer#2）
  └── workers（内置硬编码：worker#1）
```

## 5. 详细改动

### 5.1 `agent/AgentRole.java`

仅保留两个内置角色：

```java
public enum AgentRole {
    EXPLORER("探索者", "负责只读探索代码库和项目上下文，收集证据并输出分析结论"),
    WORKER("执行者", "负责执行具体任务步骤，调用工具完成文件操作、命令执行等操作");
}
```

### 5.2 `platform/prompt/PromptMode.java`

删除 `TEAM_REVIEWER`；保留 `TEAM_PLANNER`（orchestrator 内联规划用）、`TEAM_EXPLORER`、`TEAM_WORKER`。`AGENT` / `PLAN` / `PLANNER` 属 `/plan` 与 ReAct，不动。

### 5.3 `agent/AgentMessage.java`

删除 `feedback()` / `approval()` / `rejection()` 三个方法（硬编码 `AgentRole.REVIEWER`，生产代码已不用，仅测试引用）。

### 5.4 `agent/SubAgent.java`

- `promptMode()` switch 删除 `PLANNER`、`REVIEWER` 分支。
- 内部流式渲染器 `reasoningLabel()` / `contentLabel()` switch 删除 `PLANNER`、`REVIEWER` 分支。
- 删除 legacy 构造器 `SubAgent(String, AgentRole, LlmClient, ToolRegistry)`。

### 5.5 `agent/profile/AgentProfile.java`

- 删除 `legacy()` 与 `defaultCommandAllowlist()`。
- 新增内置工厂（保留 `worker(String, List, int)`，被 `AgentPoolTest` 使用）：

```java
public static AgentProfile builtinExplorer(String name) {
    return new AgentProfile(name, AgentRole.EXPLORER, AgentRole.EXPLORER.getDescription(),
            List.of("@read"), List.of(), List.of(),
            "auto", 1, "READ_ONLY", "PARENT_SUMMARY", "balanced");
}

public static AgentProfile builtinWorker(String name) {
    return new AgentProfile(name, AgentRole.WORKER, AgentRole.WORKER.getDescription(),
            List.of("*"), List.of(), List.of(),
            "auto", 1, "LEGACY_COMPAT", "PARENT_SUMMARY", "balanced");
}
```

### 5.6 `agent/profile/AgentProfileLoader.java`

删除 TOML/JSON 解析逻辑，`load()` 返回内置：

```java
public static List<AgentProfile> load(Path projectRoot) {
    // 第一步：内置 worker/explorer 硬编码；projectRoot 参数留待未来自定义 agent
    return builtinDefaults();
}

public static List<AgentProfile> builtinDefaults() {
    return List.of(
            AgentProfile.builtinExplorer("explorer#1"),
            AgentProfile.builtinExplorer("explorer#2"),
            AgentProfile.builtinWorker("worker#1"));
}
```

删除方法：`compatDefaults`、`defaultProfile`、`configuredTomlPath`、`configuredJsonPath`、`parseJson`、`parseTomlConfig`、`expandTeamRole`、`parseTomlSections`、`stripTomlComment`、`intValue`、`stringValue`、`arrayValue`、`unquote`、`validate`、`text`、`stringList`。

### 5.7 `agent/AgentOrchestrator.java`

**移除 planner 子代理**：删除 `planner` 字段、构造器创建行、`setExternalContextSupplier` / `setSkillSystem` 中对 planner 的调用。

**新增内联规划**（参照 `Planner.createPlan`）：

```java
private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

private String planWithOrchestrator(String userInput) {
    String systemPrompt = promptAssembler.assemble(PromptMode.TEAM_PLANNER,
            PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .build());
    List<LlmClient.Message> messages = List.of(
            LlmClient.Message.system(systemPrompt),
            LlmClient.Message.user("请为以下任务制定执行计划：\n" + userInput));
    PlanningStreamRenderer renderer = new PlanningStreamRenderer(out);
    LlmClient.ChatResponse response = LlmRetryPolicy.withRetry(
            () -> llmClient.chat(messages, null, renderer), "team-planner");
    renderer.finish();
    return response.content();
}
```

新增 `buildProjectMemoryContext()`（复用 `ProjectMemoryLoader`，SubAgent 已有同款逻辑）与内部类 `PlanningStreamRenderer`（流式显示"规划思考"，参照 `Planner.PlanningStreamRenderer`）。

**`runTeam()` 规划阶段替换**：

```java
String planJson;
try {
    planJson = planWithOrchestrator(userInput);
} catch (Exception e) {
    return "❌ 规划阶段失败，LLM 调用出错：" + e.getMessage();
    // 同样写 RUN_FAILED 事件
}
List<ExecutionStep> steps = parsePlan(planJson);
```

`parsePlan()`、`PlanSchemaParser`、`PlanSchemaValidator` 原样复用，规划 JSON 格式不变（`team-planner.md` 保留）。

### 5.8 删除 `prompts/modes/team-reviewer.md`

## 6. 测试联动

| 测试文件 | 改动 |
|---------|------|
| `AgentRoleTest` | 删 PLANNER/REVIEWER 的 displayName 和 valueOf 断言 |
| `SubAgentTest` | 删 planner/reviewer 的 legacy 构造调用，改用 `builtinXxx` |
| `AgentMessageTest` | 删 feedback/approval/rejection 测试 |
| `AgentToolPolicyTest` | REVIEWER 构造改为 WORKER/EXPLORER |
| `AgentProfileLoaderTest` | 断言改为"仅含 explorer×2 + worker×1" |
| `AgentPoolTest` | 不变（用 `worker(String,List,int)`） |

验证命令：`mvn test -Dtest=AgentRoleTest,SubAgentTest,AgentMessageTest,AgentToolPolicyTest,AgentProfileLoaderTest,AgentPoolTest,AgentOrchestratorTest`

## 7. 文档联动

`AGENTS.md` Multi-Agent 段更新：

- 角色改为 orchestrator 内建规划 + 两个内置子代理（explorer/worker）。
- 移除 TOML `[team.explorer]` / `[team.worker]` 配置描述。

## 8. 边界（本步不做）

- 不引入 worktree 隔离（现有 `writeScope` + 串行 + `ResourceLockManager` 已够用）。
- 不引入 `max_threads` / `max_depth` 配置（当前 `roleParallelism` 已由 profile 并发数决定）。
- 不实现自定义 agent 的 TOML 支持（`projectRoot` 参数留待后续）。
- 规划仍用 JSON prompt（保留 `team-planner.md`），function calling 规划留待后续步骤。

## 9. 设计决策记录

1. **规划收编采用内联方式（方案 A）**：规划逻辑仅"一次 LLM 调用 + JSON 解析"，不值得单独建类；`Planner.java` 已证明直接调 `llmClient` 可行。复用 `Planner.java`（方案 B）会耦合 `/plan` 与 `/team` 语义，违反 AGENTS.md 边界。
2. **legacy 彻底删除（对齐 Codex）**：`AgentProfile.legacy()` 与 `SubAgent` legacy 构造器一并删除，内置定义用明确的 `builtinExplorer` / `builtinWorker` 工厂，无兼容层。
3. **内置实例数保持 explorer×2 + worker×1**：与现状 `compatDefaults()` 一致，不改并行度行为。
