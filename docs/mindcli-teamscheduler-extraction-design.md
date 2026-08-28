# TeamScheduler 抽取设计

- 日期：2026-08-17
- 范围：`AgentOrchestrator` 的「调度决策」逻辑抽取为独立的 `TeamScheduler`
- 状态：已评审并按评审意见修订（待实现）

## 1. 背景与动机

`AgentOrchestrator` 已膨胀到 1800 行，混着 8 类职责：构造装配、规划、主循环、调度、计划解析、事件发射、步骤执行、上下文/结果格式化。其中「调度决策」是一块纯逻辑、无副作用、零外部依赖的职责：它只吃 `List<ExecutionStep>`，吐出「下一波可执行什么、怎么分组、谁要串行」，不碰 Agent 池、不碰 LLM、不碰 IO。

把这块抽成独立的 `TeamScheduler`，能同时获得：

1. 可读性：`AgentOrchestrator` 专注「编排」（规划 -> 解析 -> 按调度执行 -> 汇总），调度规则内聚到一个小类。
2. 可测试性：调度规则（依赖就绪、去重、读写分区、scope 重叠串行判定）从复杂的 orchestration 测试中剥离，可独立单测。
3. 可演进性：后续调度策略（如更细的 resource 锁定、优先级）只需改 `TeamScheduler`。

评审后补充一条约束：`writeScope` 不只是调度规则，也是 `ToolRegistry` 的硬约束规则。调度层判断「多个 step 能不能并行写」，工具层判断「当前写入路径是否越界」，必须使用同一套 scope 规范化与匹配规则，避免行为漂移。

`docs/mindcli-custom-subagents-design.md` 已把「TeamScheduler 抽取」列为后续项，本设计落实该计划。

## 2. 目标与非目标

**目标**：

1. 把「调度决策」逻辑从 `AgentOrchestrator` 抽到独立的 `TeamScheduler`，行为与现状完全一致（增量式、按当前状态逐波计算）。
2. `ExecutionStep` / `StepStatus` 提为顶层类型，供 `AgentOrchestrator` 与 `TeamScheduler` 共享，但默认保持 package-private，不把内部执行模型扩成公共接口。
3. `AgentOrchestrator` 的主循环通过 `TeamScheduler.nextWave` 获取下一波工作，不再自行拼装分组与串行判定。
4. 新增共享 `WriteScopeRules`，让 scheduler、orchestrator、`ToolRegistry` 共用同一套 `writeScope` normalize / format / overlap / path containment 规则。
5. 将写入型 step 判定集中到 `TeamStepClassifier`，避免 `AgentOrchestrator` 和 `TeamScheduler` 各维护一套 `isMutatingStep`。

**非目标（本次不做）**：

- 不搬「并发度 / 角色选择」逻辑（`roleParallelism`、`batchParallelism`、`executionRoleFor` 等，依赖 `AgentPool`）。
- 不搬「步骤执行 / 重试 / 自审 / 事件发射」逻辑。
- 不改调度策略本身（仍保持增量、逐波、同一波内 scope 不重叠才并行）。
- 不一次性算全图（不做 `scheduleAll`）。
- 不扩展 `writeScope` 到完整 glob 语义，仍保持当前 prefix / `/**` / `/*` 的保守匹配模型。

## 3. 现状 vs 目标

| 维度 | 现状 | 目标 |
|------|------|------|
| 调度决策归属 | 散落在 `AgentOrchestrator`（`getExecutableSteps`、`collapseExecutableGroups`、`mutatingSerialReasons` 等） | 集中到 `TeamScheduler` |
| 主循环 | 自行调用 `getExecutableSteps` + `collapseExecutableGroups` + 手动分区 | `nextWave(steps)` 一次拿到 `ScheduleWave` |
| 共享类型 | `ExecutionStep` / `StepStatus` 嵌套在 `AgentOrchestrator` | 顶层 package-private 类型 |
| 写入型 step 判定 | `AgentOrchestrator.isMutatingStep` 私有方法 | `TeamStepClassifier.isMutating(step)` |
| writeScope 规则 | `AgentOrchestrator` 与 `ToolRegistry` 各有一套 prefix 逻辑 | `WriteScopeRules` 统一提供 |
| 调度规则测试 | 混在 `AgentOrchestratorTest` | 独立 `TeamSchedulerTest` + `WriteScopeRulesTest` |

## 4. 目标架构

```
AgentOrchestrator.runTeam（主循环）
  └── while (wave = teamScheduler.nextWave(steps); wave.hasWork())
        ├── wave.readOnly()   -> runReadOnlyGroupBatch（并行）
        └── wave.mutating()   -> runMutatingGroups（串行 or worktree 并行，由 orchestrator 定）
                                    └── 若 wave.serialReasons() 非空，本波 mutating 全部回退串行

TeamScheduler（纯逻辑、无状态、零外部依赖）
  nextWave(steps)
    ├── getExecutableSteps(steps)             // 依赖就绪
    ├── collapseExecutableGroups(executable)  // 指纹去重
    ├── partitionReadWrite(groups)            // 读写二分
    └── mutatingSerialReasons(mutating)       // scope 重叠/未声明 -> 串行原因

TeamStepClassifier（package-private 策略 helper）
  └── isMutating(step)

WriteScopeRules（共享 scope 规则）
  ├── normalizeScopes(scopes)
  ├── formatScopes(scopes)
  ├── overlaps(left, right)
  └── containsPath(scope, root, path)
```

设计要点：

- `nextWave` 为增量语义：每次调用基于传入 `steps` 的当前状态计算「下一波」，返回空波表示无更多可执行工作。这与现状 `runTeam` 主循环逐轮 `getExecutableSteps` 的行为一致。
- `readOnly` 分组可并行执行；`mutating` 分组是否 worktree 并行由 `AgentOrchestrator` 依据 `serialReasons` 决定。
- `serialReasons` 是 wave-level veto：只要本波任意 mutating group 有串行原因，`AgentOrchestrator` 仍按当前行为把整个 mutating wave 回退为串行执行。
- `TeamScheduler` 不依赖 `AgentPool` / `AgentProfile` / `LlmClient` / `ToolRegistry`，只依赖 step 数据、DAG 就绪计算、`TeamStepClassifier` 和 `WriteScopeRules`。
- `ToolRegistry` 不依赖 `agent` 包；它只依赖 `WriteScopeRules`，用于 `write_file` / `create_project` 的硬约束校验。

## 5. 详细改动

### 5.1 类型提为顶层，但不默认 public

新建以下顶层类型，优先放在 `com.mindcli.agent` 包，默认 package-private：

- `ExecutionStep`（record）
- `StepStatus`（enum）
- `StepExecutionGroup`（record）
- `ScheduleWave`（record）
- `TeamScheduler`（final class）
- `TeamStepClassifier`（final class）

`ExecutionStep` 内容与当前 `AgentOrchestrator` 内的嵌套定义一致，包括 `pending` / `withResult` / `withFailed` / `withSkipped` / `started` 等工厂方法。

`ExecutionStep` 不新增 `isMutating()`。写入型 step 判定属于调度/执行策略，不是纯数据模型的固有属性；放到 `TeamStepClassifier` 能让规则变化集中在一个地方。

可见性说明：

- 当前 `AgentOrchestrator`、`TeamScheduler`、相关测试都可以放在 `com.mindcli.agent` package 下，因此不需要为了跨子包访问把内部 record 全部 public。
- 如果后续确实需要把 scheduler 暴露给其他 package，再单独提升 `TeamScheduler` / `ScheduleWave` 可见性，不提前扩大 `ExecutionStep` 等内部模型接口。

### 5.2 新增 `TeamScheduler`

```java
final class TeamScheduler {
    ScheduleWave nextWave(List<ExecutionStep> steps) { ... }

    // 内部私有方法（由 AgentOrchestrator 迁入）：
    //   getExecutableSteps, collapseExecutableGroups, stepFingerprint,
    //   mutatingSerialReasons
    //
    // 调用共享规则：
    //   TeamStepClassifier.isMutating(step)
    //   WriteScopeRules.normalizeScopes(...)
    //   WriteScopeRules.overlaps(...)
    //   WriteScopeRules.formatScopes(...)
}

record ScheduleWave(
    List<StepExecutionGroup> readOnly,
    List<StepExecutionGroup> mutating,
    Map<String, String> serialReasons
) {
    boolean hasWork() {
        return !readOnly.isEmpty() || !mutating.isEmpty();
    }
}
```

`TeamScheduler` 的外部 interface 只保留 `nextWave`。`getExecutableSteps`、`collapseExecutableGroups`、`mutatingSerialReasons` 等方法全部私有化，避免把调度拼装细节泄漏回 caller。

### 5.3 新增 `WriteScopeRules`

新建 `com.mindcli.platform.security.WriteScopeRules`：

```java
public final class WriteScopeRules {
    public static List<String> normalizeScopes(List<String> scopes) { ... }
    public static String formatScopes(List<String> scopes) { ... }
    public static boolean overlaps(List<String> left, List<String> right) { ... }
    public static boolean containsPath(List<String> scopes, Path root, Path path) { ... }

    // 内部共享：
    //   scopePrefix(scope)
    //   toSlash(path)
}
```

迁移原则：

- `AgentOrchestrator.normalizeScopes` / `formatScopes` 删除，改用 `WriteScopeRules`。
- `AgentOrchestrator.writeScopesOverlap` / `normalizedScopeOverlaps` / `normalizeScopePrefix` 删除，改用 `WriteScopeRules.overlaps`。
- `ToolRegistry.writeScopePrefix` 和 `enforceWriteScope` 的 prefix 判断改用 `WriteScopeRules.containsPath`。
- `execute_command` 在 writeScope 下 fail-closed 的策略不变，只复用 `formatScopes` 输出允许范围。

### 5.4 新增 `TeamStepClassifier`

新建 package-private `TeamStepClassifier`：

```java
final class TeamStepClassifier {
    static boolean isMutating(ExecutionStep step) { ... }
}
```

规则保持当前行为：

- requiredTools 包含 `write_file` 或 `create_project`：写入型。
- requiredTools 包含 `execute_command` 且 `riskLevel != low`：写入型。
- 其他情况：只读型。

`AgentOrchestrator.executionRoleFor`、`appendFileOwnershipContext`、`forbiddenWriteScopes`、`writeScopeFor` 改为调用 `TeamStepClassifier.isMutating(step)`。

### 5.5 `AgentOrchestrator` 主循环瘦身

`runTeam` 中的「取可执行 -> 分组 -> 分区 -> 串行原因」替换为：

```java
private final TeamScheduler teamScheduler = new TeamScheduler();

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

`runReadOnlyGroupBatch` 与 `runMutatingGroups` 签名保持不变（接收 `List<StepExecutionGroup>` 与 `Map<String, String> serialReasons`），只是分组类型来源从 `AgentOrchestrator` 嵌套类型改为顶层类型。

### 5.6 方法迁移清单

**迁入 `TeamScheduler`（调度专属，私有化）**：

- `getExecutableSteps`
- `collapseExecutableGroups`
- `stepFingerprint`
- `mutatingSerialReasons`

**迁入 `WriteScopeRules`（共享 public static helper）**：

- `normalizeScopes`
- `formatScopes`
- `writeScopesOverlap` / `normalizedScopeOverlaps` / `normalizeScopePrefix` 的等价实现
- `ToolRegistry.writeScopePrefix` 的等价实现
- `containsPath`

**迁入 `TeamStepClassifier`（package-private 策略 helper）**：

- `isMutatingStep` -> `TeamStepClassifier.isMutating(step)`

**留在 `AgentOrchestrator`（执行侧，依赖 AgentPool/agentProfiles 或执行上下文）**：

- `roleParallelism` / `batchParallelism` / `executionRoleFor` / `readOnlyExecutionRole`
- `roleHasProfileForTools` / `profileAllowsAll`
- `executionRolesIn` / `batchRoleLabel` / `childRoleName`
- `writeScopeFor` / `forbiddenWriteScopes` / `appendFileOwnershipContext`
- `stepById` / `getStepStatus` / `copyExecutionOutcome` / `propagateDuplicateResult`

注意：`writeScopeFor` / `forbiddenWriteScopes` / `appendFileOwnershipContext` 虽为纯函数，但属于「执行阶段设置 registry writeScope / 构建子代理上下文」的职责，按「只移调度决策」的原则留在 `AgentOrchestrator`。

### 5.7 依赖方向

```
AgentOrchestrator -> TeamScheduler
AgentOrchestrator -> ExecutionStep / StepStatus / StepExecutionGroup / ScheduleWave
AgentOrchestrator -> TeamStepClassifier / WriteScopeRules

TeamScheduler     -> ExecutionStep / StepStatus / DependencyGraph
TeamScheduler     -> TeamStepClassifier / WriteScopeRules

ToolRegistry      -> WriteScopeRules
```

`TeamScheduler` 不反向依赖 `AgentOrchestrator`，不依赖 `AgentPool` / `AgentProfile` / `LlmClient` / `ToolRegistry`。`ToolRegistry` 也不依赖 `agent` 包。

## 6. 测试联动

| 测试文件 | 改动 |
|---------|------|
| `TeamSchedulerTest`（新增） | 覆盖：依赖就绪、空波、指纹去重、读写分区、scope 重叠串行原因、scope 未声明串行、scope 不重叠不串行、`serialReasons` 非空时保持 wave-level veto 语义 |
| `WriteScopeRulesTest`（新增） | 覆盖：路径分隔符归一、大小写归一、`/**` / `/*` prefix 折叠、scope overlap、path containment、空 scope 行为 |
| `ToolRegistryTest` | 保留并补强 `write_file` / `create_project` 越界拒绝，确保改用 `WriteScopeRules.containsPath` 后行为不变 |
| `AgentOrchestratorTest` | 删/改直接测 `getExecutableSteps`、去重分组、scope 串行判定的用例，改为走 `TeamScheduler.nextWave`；parsePlan、parseReviewApproval、事件发射、执行流程、自审 fail-closed、worktree merge、writeScope restore 用例不动 |

建议验证命令：

```bash
mvn test -Dtest=TeamSchedulerTest,WriteScopeRulesTest,ToolRegistryTest,AgentOrchestratorTest -DskipTests=false
mvn test -Pquick
```

## 7. 边界（本次不做）

- 不搬并发度/角色选择。
- 不搬步骤执行、重试、自审、事件发射。
- 不改调度策略（增量、逐波、scope 不重叠才并行）。
- 不做 `scheduleAll`（一次性算全图）。
- 不引入完整 glob / path matcher 规则，避免一次抽取同时改变安全语义。

## 8. 设计决策记录

1. 只抽调度决策：并发度/角色选择依赖 `AgentPool`，搬走会让 `TeamScheduler` 变成有状态、依赖 Agent 池，破坏「纯函数式、零依赖、好测」的目标。
2. 单入口 `nextWave` 而非细粒度方法：`nextWave` 把「取可执行 -> 分组 -> 分区 -> 串行原因」封装成一次调用，主循环最薄；细粒度方法会让主循环继续自己拼装，抽取价值打折。
3. 增量而非一次性全图：现状主循环逐轮基于当前 `steps` 状态重算可执行集（含 SKIPPED/FAILED 传播），`nextWave` 保持增量语义可做到行为零变化，风险最低。
4. `ExecutionStep` / `StepStatus` 提为顶层但不默认 public：`TeamScheduler` 与 `AgentOrchestrator` 都要操作 `ExecutionStep`，嵌套会形成 `TeamScheduler -> AgentOrchestrator.ExecutionStep` 的反向耦合；提为顶层后依赖方向干净。放在同一 package 下可避免把内部模型扩成公共接口。
5. `isMutating` 不放进 `ExecutionStep`：写入型判定取决于 requiredTools 与 riskLevel，是调度策略，不是 step 数据本身。放到 `TeamStepClassifier` 能提高规则局部性。
6. `writeScope` 抽成 `WriteScopeRules`：调度层和工具层都依赖同一套 prefix 语义。统一后可以避免 scheduler 判断可并行、但 `ToolRegistry` 实际拒绝，或者反过来工具放行但调度误判不冲突。
7. `serialReasons` 保持 wave-level veto：当前 `runMutatingGroups` 只要发现任意串行原因，就把整波 mutating group 回退为串行。文档显式保留该行为，避免抽取时误改并发语义。

## 9. 本次评审修订说明

本次从原设计中做了 5 处修订：

1. `TeamScheduler` 从 `com.mindcli.agent.scheduler` 调整为优先放在 `com.mindcli.agent`。原因：Java 子包不是同一个 package，原方案里 package-private helper 不能被 `AgentOrchestrator` 访问；同包也能避免内部 record 被迫 public。
2. 删除「`TeamScheduler.normalizeScopes/formatScopes` 作为共享 static helper」的设计，改为 `WriteScopeRules`。原因：scope 匹配已经被 `ToolRegistry` 用作硬约束，不应该挂在 scheduler 这个调度模块下面。
3. 删除「`ExecutionStep.isMutating()`」的设计，改为 `TeamStepClassifier.isMutating(step)`。原因：mutating 判定是策略规则，放在纯数据模型上会让模型承担过多职责。
4. 明确 `serialReasons` 是本波 mutating 的整体串行 veto。原因：当前代码就是整波串行，抽取文档必须防止实现时误改成局部串行。
5. 增加 `WriteScopeRulesTest` 与 `ToolRegistryTest` 联动要求。原因：这次抽取会碰到安全边界逻辑，不能只测 scheduler 的调度结果，也要证明工具层越界拒绝没有变弱。
