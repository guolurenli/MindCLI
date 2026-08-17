# TeamScheduler 抽取设计

- 日期：2026-08-17
- 范围：`AgentOrchestrator` 的「调度决策」逻辑抽取为独立的 `TeamScheduler`
- 状态：已评审（待实现）

## 1. 背景与动机

`AgentOrchestrator` 已膨胀到 1800 行，混着 8 类职责：构造装配、规划、主循环、调度、计划解析、事件发射、步骤执行、上下文/结果格式化。其中「调度决策」是一块**纯逻辑、无副作用、零外部依赖**的职责——它只吃 `List<ExecutionStep>`，吐出「下一波可执行什么、怎么分组、谁要串行」，不碰 Agent 池、不碰 LLM、不碰 IO。

把这块抽成独立的 `TeamScheduler`，能同时获得：

1. **可读性**：`AgentOrchestrator` 专注「编排」（规划 → 解析 → 按调度执行 → 汇总），调度规则内聚到一个小类。
2. **可测试性**：调度规则（依赖就绪、去重、读写分区、scope 重叠串行判定）从复杂的 orchestration 测试中剥离，可独立单测。
3. **可演进性**：后续调度策略（如更细的 resource 锁定、优先级）只需改 `TeamScheduler`。

`docs/mindcli-custom-subagents-design.md` 已把「TeamScheduler 抽取」列为后续项，本设计落实该计划。

## 2. 目标与非目标

**目标**：

1. 把「调度决策」逻辑从 `AgentOrchestrator` 抽到独立的 `TeamScheduler`，行为与现状**完全一致**（增量式、按当前状态逐波计算）。
2. `ExecutionStep` / `StepStatus` 提为顶层类型，供 `AgentOrchestrator` 与 `TeamScheduler` 共享。
3. `AgentOrchestrator` 的主循环通过 `TeamScheduler.nextWave` 获取下一波工作，不再自行拼装分组与串行判定。

**非目标（本次不做）**：

- 不搬「并发度 / 角色选择」逻辑（`roleParallelism`、`batchParallelism`、`executionRoleFor` 等，依赖 `AgentPool`）。
- 不搬「步骤执行 / 重试 / 自审 / 事件发射」逻辑。
- 不改调度策略本身（仍保持增量、逐波、同一波内 scope 不重叠才并行）。
- 不一次性算全图（不做 `scheduleAll`）。

## 3. 现状 vs 目标

| 维度 | 现状 | 目标 |
|------|------|------|
| 调度决策归属 | 散落在 `AgentOrchestrator`（`getExecutableSteps`、`collapseExecutableGroups`、`mutatingSerialReasons` 等） | 集中到 `TeamScheduler` |
| 主循环 | 自行调用 `getExecutableSteps` + `collapseExecutableGroups` + 手动分区 | `nextWave(steps)` 一次拿到 `ScheduleWave` |
| 共享类型 | `ExecutionStep`/`StepStatus` 嵌套在 `AgentOrchestrator` | 顶层 `ExecutionStep`/`StepStatus` |
| 调度规则测试 | 混在 `AgentOrchestratorTest`（1456 行） | 独立 `TeamSchedulerTest` |

## 4. 目标架构

```
AgentOrchestrator.runTeam（主循环）
  └── while (wave = teamScheduler.nextWave(steps); wave.hasWork())
        ├── wave.readOnly()   -> runReadOnlyGroupBatch（并行）
        └── wave.mutating()   -> runMutatingGroups（串行 or worktree 并行，由 orchestrator 定）
                                    └── 依据 wave.serialReasons() 判定是否整批串行

TeamScheduler（纯逻辑、无状态、零依赖）
  nextWave(steps)
    ├── getExecutableSteps(steps)          // 依赖就绪
    ├── collapseExecutableGroups(executable) // 指纹去重
    ├── partitionReadWrite(groups)          // 读写二分
    └── mutatingSerialReasons(mutating)     // scope 重叠/未声明 -> 串行原因
```

## 5. 详细改动

### 5.1 类型提为顶层

新建 `com.mindcli.agent.ExecutionStep`（record）与 `com.mindcli.agent.StepStatus`（enum），内容与当前 `AgentOrchestrator` 内的嵌套定义一致，包括 `pending`/`withResult`/`withFailed`/`withSkipped`/`started` 等工厂方法。

- `isMutatingStep(step)` 的判定逻辑提为 `ExecutionStep` 的实例方法 `boolean isMutating()`（它是 step 的自身属性，调度与执行两边都要用，放 step 上最自然）。
- `StepExecutionGroup`（record，含 `fingerprint`/`leader`/`duplicates`/`mutating`）与 `ScheduleWave`（新 record）放 `com.mindcli.agent.scheduler` 包，作为顶层 public record。

> 可见性说明：因 `AgentOrchestrator`（`com.mindcli.agent`）与 `TeamScheduler`（`com.mindcli.agent.scheduler`）跨包共享这些类型，`ExecutionStep`、`StepStatus`、`StepExecutionGroup`、`ScheduleWave` 均需 `public`（record 的访问器随之 public）。它们本是内部模型，提为 public 属于「抽取的可见性代价」，可接受。

### 5.2 新增 `com.mindcli.agent.scheduler.TeamScheduler`

```java
public final class TeamScheduler {
    public ScheduleWave nextWave(List<ExecutionStep> steps) { ... }

    // 内部私有方法（由 AgentOrchestrator 迁入）：
    //   getExecutableSteps, collapseExecutableGroups, stepFingerprint,
    //   mutatingSerialReasons, writeScopesOverlap,
    //   normalizedScopeOverlaps, normalizeScopePrefix
    //
    // 共享静态 helper（package-private，供 TeamScheduler 与 AgentOrchestrator 两边用）：
    //   static normalizeScopes(List<String>) -> List<String>
    //   static formatScopes(List<String>) -> String
}

public record ScheduleWave(
    List<StepExecutionGroup> readOnly,
    List<StepExecutionGroup> mutating,
    Map<String, String> serialReasons
) {
    public boolean hasWork() {
        return !readOnly.isEmpty() || !mutating.isEmpty();
    }
}
```

设计要点：

- `nextWave` 为**增量**语义：每次调用基于传入 `steps` 的当前状态计算「下一波」，返回空波表示无更多可执行工作。这与现状 `runTeam` 主循环逐轮 `getExecutableSteps` 的行为一致。
- `readOnly` 分组可并行执行；`mutating` 分组是否 worktree 并行由 `AgentOrchestrator` 依据 `serialReasons` 决定（调度层只给出「谁必须串行 + 原因」，不决定执行手段）。
- `TeamScheduler` 无构造参数、无状态字段，可安全作为 `AgentOrchestrator` 的一个 final 字段复用。

### 5.3 `AgentOrchestrator` 主循环瘦身

`runTeam` 中（现 457–491 行）的「取可执行 → 分组 → 分区 → 串行原因」替换为：

```java
// AgentOrchestrator 新增 final 字段：private final TeamScheduler teamScheduler = new TeamScheduler();
...
while (true) {
    if (CancellationContext.isCancelled()) { ... }
    ScheduleWave wave = teamScheduler.nextWave(steps);
    if (!wave.hasWork()) {
        break;
    }
    if (!wave.readOnly().isEmpty()) {
        batchIndex++;
        runReadOnlyGroupBatch(runContext, wave.readOnly(), steps, retryCount, batchIndex);
    }
    if (!wave.mutating().isEmpty()) {
        batchIndex++;
        runMutatingGroups(runContext, wave.mutating(), steps, retryCount, batchIndex,
                wave.serialReasons());
    }
}
```

`runReadOnlyGroupBatch` 与 `runMutatingGroups` 签名保持不变（接收 `List<StepExecutionGroup>` 与 `Map<String,String> serialReasons`），只是分组类型来源从 `AgentOrchestrator` 嵌套类型改为顶层 `StepExecutionGroup`。

### 5.4 方法迁移清单

**迁入 `TeamScheduler`（调度专属，私有化）**：

- `getExecutableSteps`（依赖就绪判定）
- `collapseExecutableGroups` / `stepFingerprint`
- `mutatingSerialReasons`
- `writeScopesOverlap` / `normalizedScopeOverlaps` / `normalizeScopePrefix`

**迁入 `TeamScheduler`（共享 static helper，package-private）**：

- `normalizeScopes` / `formatScopes`（调度层与执行层的 context 构建都要用，放 `TeamScheduler` 作为 static，`AgentOrchestrator` 通过 `TeamScheduler.normalizeScopes(...)` 调用，依赖方向仍是 orchestrator → scheduler）

**提为 `ExecutionStep` 实例方法**：

- `isMutatingStep` → `ExecutionStep.isMutating()`（step 自身属性，调度与执行两边共用）

**留在 `AgentOrchestrator`（执行侧，依赖 AgentPool/agentProfiles 或纯执行）**：

- `roleParallelism` / `batchParallelism` / `executionRoleFor` / `readOnlyExecutionRole`
- `roleHasProfileForTools` / `profileAllowsAll`
- `executionRolesIn` / `batchRoleLabel` / `childRoleName`
- `writeScopeFor` / `forbiddenWriteScopes` / `appendFileOwnershipContext`（执行/context 构建，调用 `ExecutionStep.isMutating()` 与 `TeamScheduler.normalizeScopes/formatScopes`）
- `stepById` / `getStepStatus` / `copyExecutionOutcome` / `propagateDuplicateResult`

**注意**：`writeScopeFor` / `forbiddenWriteScopes` / `appendFileOwnershipContext` 虽为纯函数，但属于「执行阶段设置 registry writeScope / 构建子代理上下文」的职责，按「只移调度决策」的原则留在 `AgentOrchestrator`。

### 5.5 依赖方向

```
AgentOrchestrator ──> TeamScheduler   （单向，orchestrator 持有 scheduler）
AgentOrchestrator ──> ExecutionStep / StepStatus   （共享顶层类型）
TeamScheduler     ──> ExecutionStep / StepStatus / DependencyGraph
```

`TeamScheduler` 不反向依赖 `AgentOrchestrator`，不依赖 `AgentPool`/`AgentProfile`/`LlmClient`/`ToolRegistry`。

## 6. 测试联动

| 测试文件 | 改动 |
|---------|------|
| `TeamSchedulerTest`（新增） | 覆盖：依赖就绪、空波、指纹去重、读写分区、scope 重叠串行原因、scope 未声明串行、scope 不重叠不串行 |
| `AgentOrchestratorTest` | 删/改直接测 `getExecutableSteps`、去重分组、scope 串行判定的用例，改为走 `TeamScheduler.nextWave`；`parsePlan`/`parseReviewApproval`/事件发射/执行流程用例不动 |

验证命令：`mvn test -DskipTests=false`

## 7. 边界（本次不做）

- 不搬并发度/角色选择（B 组）。
- 不搬步骤执行、重试、自审、事件发射。
- 不改调度策略（增量、逐波、scope 不重叠才并行）。
- 不做 `scheduleAll`（一次性算全图）。

## 8. 设计决策记录

1. **只抽 A 组（纯逻辑）**：B 组并发度/角色选择依赖 `AgentPool`，搬走会让 `TeamScheduler` 变成有状态、依赖 Agent 池，破坏「纯函数式、零依赖、好测」的目标。
2. **单入口 `nextWave` 而非细粒度方法**：`nextWave` 把「取可执行 → 分组 → 分区 → 串行原因」封装成一次调用，主循环最薄；细粒度方法会让主循环继续自己拼装，抽取价值打折。
3. **增量而非一次性全图**：现状主循环逐轮基于当前 `steps` 状态重算可执行集（含 SKIPPED/FAILED 传播），`nextWave` 保持增量语义可做到行为零变化，风险最低。
4. **`ExecutionStep`/`StepStatus` 提为顶层**：`TeamScheduler` 与 `AgentOrchestrator` 都要操作 `ExecutionStep`，嵌套会形成 `TeamScheduler` → `AgentOrchestrator.ExecutionStep` 的反向耦合；提为顶层后依赖方向干净。
