# Plan 精确断点恢复设计

## 目标

让 `/run resume <runId>` 对 Plan 模式实现 task 边界的精确恢复：从原 JSONL ledger 重建已批准的 DAG，保留已经完成或显式跳过的 task，只继续尚未完成且可安全重试的 task。

本阶段只实现 Plan。Team 的 parent step / child execute / review 恢复留在后续阶段。

## 成功标准

1. 两个有依赖关系的 task 中，第一个完成后 run 被取消，恢复时第一个 task 不再次执行，第二个继续执行。
2. 恢复前后使用同一个 `runId` 和同一个 workspace。
3. 进程退出并重新创建 `JsonlRunStore` 后仍能恢复。
4. 已完成的副作用 task 不重复产生工具调用或文件写入。
5. 未完成 task 存在无法确认的副作用时 fail closed，不追加 `RUN_RESUMED`。
6. 没有 Plan checkpoint 的旧账本不重新规划、不猜测 task 状态，返回明确的人工处理提示。
7. 原有 ReAct 恢复语义保持不变，Team 仍沿用现有整 run 安全重试语义。

## 非目标

- 不恢复 LLM token 流、reasoning 或 task 内部执行到一半的位置。
- 不恢复正在运行的线程、进程或 MCP session。
- 不用 LLM 重新规划并按描述猜测旧 task。
- 不增加第二份 Plan 状态文件、数据库或工作流引擎。
- 不保证旧 Plan ledger 自动升级为可精确恢复格式。
- 不实现 Team child run 精确恢复。

## 方案选择

### 采用：Plan 定义事件 + Task checkpoint 事件

JSONL ledger 保持 source of truth。Plan 创建或局部重规划完成后写入完整、规范化的 Plan 定义；task 状态变化时写入紧凑 checkpoint。恢复端读取最近一版 Plan 定义，并按 `seq` 应用后续 task checkpoint。

这个方案的 interface 小：生产执行方只需要“记录 Plan”和“记录 task 状态”，恢复方只需要“从事件重建 `PlanResumeState`”。复杂度集中在一个 checkpoint codec 和一个恢复投影中。

### 不采用：每次状态变化写完整 Plan 快照

实现直观，但每个 task 状态变化都会复制整张 DAG，账本膨胀，事件差异也不清晰。

### 不采用：恢复时重新调用 Planner

代码量更少，但 task ID、依赖、降级配置和执行顺序可能变化，无法保证已完成副作用不重跑，因此不能称为精确恢复。

## 账本事件

新增两个 `AgentRunEventType`：

### `PLAN_DEFINED`

在计划通过审阅、即将首次执行时写入；局部重规划合并成功后再写入一条新版本。

attributes：

- `planVersion`：从 1 开始递增。
- `reason`：`INITIAL` 或 `REPLAN`。
- `planJson`：规范化 Plan JSON。

`planJson` 包含：

- `planId`、`goal`、`summary`；
- task 的 `id`、`description`、`type`、`dependencies`；
- `critical`、`maxRetries`、`degradation`；
- `expectedEvidence`、`requiredTools`、`preferredAgent`、`riskLevel`；
- 定义写入时已有 task 的 `status`、`result`、`error`、`retryCount`。

局部重规划后的定义是新的完整基线。恢复只使用最后一条合法 `PLAN_DEFINED`，不会混合两版 DAG。

### `PLAN_TASK_CHECKPOINT`

attributes：

- `planVersion`；
- `taskId`；
- `taskStatus`：`RUNNING`、`PENDING`、`COMPLETED`、`SKIPPED` 或 `FAILED`；
- `result`、`error`；
- `retryCount`。

写入时机：

1. task 被调度且标为 `RUNNING` 后、调用 LLM 前；
2. task 完成并标为 `COMPLETED` 后；
3. 降级跳过并标为 `SKIPPED` 后；
4. 重试回到 `PENDING` 或最终标为 `FAILED` 后。

checkpoint 必须在内存状态改变后立即 append。工具 outcome 仍由现有 `ToolDispatcher` / Plan 外层写入，不复制到 checkpoint。

## 新模块与 interface

### `runtime/run/recovery/PlanCheckpointCodec`

这是恢复状态与 ledger JSON 之间的唯一 codec seam，不直接依赖可变的 `ExecutionPlan`。

```java
public final class PlanCheckpointCodec {
    public String encode(PlanResumeState state);
    public PlanResumeState decode(String planJson);
}
```

它负责完整字段映射和结构校验，不负责读写 `RunStore`。`PlanExecuteAgent` 在写事件前把当前 `ExecutionPlan` 映射为 `PlanResumeState`，恢复时再执行反向映射；runtime recovery 包不依赖 `agent.plan` 的可变类型。

### `runtime/run/recovery/PlanResumeState`

恢复侧的不可变数据，不暴露可变的 `ExecutionPlan`：

```java
public record PlanResumeState(
        boolean available,
        int planVersion,
        String planId,
        String goal,
        String summary,
        List<PlanTaskResumeState> tasks,
        String reason
) {}
```

`PlanTaskResumeState` 保存上述 task 定义与最后状态。`PlanExecuteAgent` 负责将它映射回新的 `ExecutionPlan`，避免 runtime recovery 模块持有可变 Plan 对象。

### `RunRecoveryService`

新增：

```java
public PlanResumeState reconstructPlanState(String runId);
```

规则：

1. 找到最后一条合法 `PLAN_DEFINED`；
2. 解码其 `planJson`；
3. 只应用同一 `planVersion`、且位于该定义事件之后的 `PLAN_TASK_CHECKPOINT`；
4. task ID、状态或版本不合法时返回 `available=false`；
5. `RUNNING` task 在安全检查通过后映射为待重新执行；
6. `COMPLETED`、`SKIPPED` 保持终态；
7. `FAILED` 不自动伪装成成功，由原 Plan 降级语义决定后续处理。

### `PlanModeAdapter`

保留现有 `execute`，新增：

```java
public AgentRunResult executeRecovered(
        AgentRunContext context,
        RunStore runStore,
        PlanResumeState state
);
```

它只负责把恢复状态交给 `PlanExecuteAgent` 并将返回文本映射为 `AgentRunResult`。

### `PlanExecuteAgent`

新增：

```java
public String runRecovered(
        AgentRunContext context,
        RunStore runStore,
        PlanResumeState state
);
```

恢复路径：

- 不调用 `Planner.createPlan`；
- 不再次调用 `PlanReviewHandler`；
- 用 codec 重建新的 `ExecutionPlan`；
- 将历史 `RUNNING` task 安全地重置为 `PENDING`；
- 复用现有 `executePlan`、DAG 调度、降级和 ToolDispatcher；
- 新 checkpoint 继续写入相同 `runId` 和相同 `planVersion`。

## 恢复安全裁决

精确恢复只信任 task 终态 checkpoint，不根据最终文件内容猜测 task 是否完成。

### 可以继续

- `COMPLETED` / `SKIPPED` task：直接跳过。
- `PENDING` task：按 DAG 正常执行。
- `RUNNING` task 没有完成工具 outcome，或只出现明确只读工具 outcome：重置为 `PENDING`。

### 必须人工处理

如果最后状态不是 `COMPLETED/SKIPPED` 的 task 已产生以下任一成功 outcome，则 `resumeAvailable=false`：

- `write_file`、`create_project`、`save_memory`、`revert_turn`；
- `execute_command`；
- `mcp__*` 或未知副作用工具。

这是工具执行成功、task checkpoint 尚未落盘的模糊窗口。即使用户传入 `--confirm` 也不自动重跑，因为确认不能证明副作用是否可重复。

已完成 task 中的副作用不构成模糊状态，但 run 仍按现有规则视为高风险恢复，CLI 继续要求 `--confirm`。确认后也只执行剩余 task。

## `AgentRuntime.resume` 数据流

```text
/run resume
  → RunRecoveryService.inspect
  → reconstructPlanState
  → 检查 workspace / checkpoint / 模糊副作用
  → AgentRuntime.resume
  → 仅在状态完整可恢复时 append RUN_RESUMED
  → PlanModeAdapter.executeRecovered
  → PlanExecuteAgent.runRecovered
  → 跳过完成节点，执行 ready pending 节点
  → 原 ToolDispatcher / policy / HITL / resource lock
```

`AgentRuntime` 沿用 ReAct 的具体 adapter 分支，不在本阶段增加通用恢复 adapter 层。等 Team 也有第二种恢复实现后，再评估是否提取统一 seam。

## 错误处理

- `PLAN_DEFINED` 缺失：`PlanResumeState.available=false`，提示旧账本不支持精确恢复。
- `planJson` 损坏或字段非法：fail closed，提示 checkpoint 损坏。
- task checkpoint 引用未知 task 或版本不匹配：fail closed。
- DAG 无效或依赖丢失：fail closed，不调用 Planner 修复。
- workspace 与当前项目不一致：沿用 CLI 现有拒绝逻辑。
- 恢复执行再次取消：保留新的 task checkpoints，之后仍可从同一 run 再恢复。
- 恢复成功：正常追加 `RUN_FINISHED`；之后再次 resume 被拒绝。
- CLI 的“无法恢复”提示优先展示 `RunResumePlan.reason`，不能把 checkpoint 缺失误报为原始输入缺失。

## 测试设计

### codec 单元测试

- 所有 task 定义、治理字段、状态和依赖 round-trip。
- 缺字段、未知状态、重复 task ID、无效依赖拒绝。

### recovery 投影测试

- 最近 Plan 定义胜出，旧版本 checkpoint 不污染新版本。
- 按事件 seq 应用最后 task 状态。
- 缺 Plan 定义、未知 task、损坏 JSON fail closed。
- 未完成 task 有副作用 outcome 时要求人工处理。

### AgentRuntime / adapter 测试

- Plan 恢复状态不可用时不追加 `RUN_RESUMED`。
- 可用时调用 `PlanModeAdapter.executeRecovered`。
- ReAct 现有恢复测试保持通过。

### 端到端结果评测

在 `com.mindcli.eval` 新增一个 Plan 精确恢复场景：

1. scripted LLM 生成两个有依赖的 task；
2. task 1 写文件并完成；
3. 测试侧在 task 1 完成后触发 `CancellationContext`，run 进入 `CANCELLED`；
4. 重新打开同一目录的 `JsonlRunStore`；
5. 用新的 scripted LLM 恢复；
6. 断言 task 1 的 `write_file` outcome 和真实写入都只有一次；
7. 断言 task 2 完成，runId 不变，账本包含单个有效 `RUN_RESUMED` 和最终 `RUN_FINISHED`。

另加一个模糊副作用场景：预置 `RUNNING task → successful write_file outcome → RUN_CANCELLED`，断言恢复被拒绝且没有新的工具执行。

## 文档联动

- `README.md`：说明 Plan task 边界恢复、旧账本限制和模糊副作用拒绝。
- `AGENTS.md`：更新 `/run resume` 实际行为与验证命令。
- `ROADMAP.md`：把 Plan 精确恢复标为完成，Team 精确恢复保持未交付。

## 风险控制

- 只从 task 边界恢复，避免伪造 task 内状态。
- JSONL ledger 保持唯一事实源，不引入双写一致性问题。
- 恢复复用原 DAG，不依赖 LLM 重新规划的稳定性。
- 模糊副作用一律人工处理，正确性优先于自动恢复率。
- 不抽象通用 `RecoverableModeAdapter`，避免在只有一个新增实现时过度设计。
