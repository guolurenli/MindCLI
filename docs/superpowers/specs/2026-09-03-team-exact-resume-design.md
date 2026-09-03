# Team step 边界精确恢复设计

日期：2026-09-03
状态：已确认设计，待实现

## 1. 背景

MindCLI 当前已经具备两种恢复能力：

- ReAct 按规范消息边界重建历史，并按 `runId + toolCallId + toolName + arguments` 复用成功工具结果。
- Plan 通过 `PLAN_DEFINED` 与 `PLAN_TASK_CHECKPOINT` 恢复原 DAG，跳过已经完成或跳过的 task。

Team 模式已经把规划、执行、自审与修复分别记录到 parent run 和 child run，但恢复时仍会重新规划并重试整个 Team run。对于已经完成的步骤，这会浪费模型调用；对于写文件、执行命令或 MCP 调用，还可能重复产生副作用。

本阶段为 Team 增加 step 边界精确恢复。设计沿用现有 Agent Runtime、ModeAdapter、RunStore 与 JSONL ledger，不建立第二套状态存储，也不恢复 child 内部的 LLM/tool loop。

## 2. 目标

1. Team 恢复时复用原始计划，不重新调用 planner。
2. `COMPLETED`、`SKIPPED` 与确定性 `FAILED` step 不重复执行。
3. 安全的未开始或无副作用中断步骤可回到 `PENDING`。
4. child 已产生成功副作用但 parent 尚未留下安全终态时 fail closed。
5. review 未明确批准时绝不把 step 视为完成。
6. worktree 写入必须在成功合并到主工作区后才能持久化为 `COMPLETED`。
7. leader 与重复步骤的完成状态用一条事件原子确认。
8. 旧 Team 账本、损坏 checkpoint 和无法判断的 child 执行明确拒绝精确恢复。

## 3. 非目标

本阶段不实现：

- child 内部某次 LLM 或 tool call 的断点续跑；
- 自动接管、合并或清理崩溃遗留的 worktree；
- 根据 child 最终输出推断 parent review 已通过；
- 跨机器恢复；
- 新的 Team 重规划语义；
- 新的 RunStore 查询或 child 枚举接口；
- 第二份 `team.state.json` 或其他状态真相源。

本阶段会补齐 child 的恢复审计事件，但这些事件只用于判断是否安全恢复，
不会把 child loop 变成可断点续跑的执行器。

## 4. 设计原则

### 4.1 JSONL ledger 是唯一事实源

所有恢复信息均追加到现有 parent/child JSONL ledger。`run.meta.json` 与 `run.state.json` 仍只是事件投影，不参与恢复裁决。

### 4.2 先记录恢复证据，再启动 child

每次 execute 或 review child 启动前，parent 必须先写包含 `childRunId` 的 checkpoint。checkpoint 写入失败时不得启动 child。

### 4.3 先确认工作区提交，再声明完成

worktree 中的执行和 review 通过只代表候选结果可接受。只有整批 worktree 成功合并到主工作区后，step 才能写入 `COMPLETED` checkpoint。

### 4.4 不从模糊证据推断成功

child execute 成功、child review 成功或 worktree 中存在提交，都不能替代 parent 的安全终态 checkpoint。证据不完整时停止恢复，而不是重新执行或猜测结果。

### 4.5 复用现有调度语义

恢复模块只重建 step 定义、业务状态和结果。恢复后的 `ExecutionStep` 继续交给现有 `TeamScheduler` 计算 ready wave、依赖满足、指纹去重与读写分区。

## 5. 模块与 seam

### 5.1 runtime/run/recovery

新增：

- `TeamCheckpointCodec`：Team 计划与 checkpoint 的规范编解码和结构校验。
- `TeamResumeState`：恢复模块向 Team adapter 暴露的不可变恢复状态。
- `TeamStepResumeState`：单个 step 的定义、最新状态、结果和恢复诊断。

`RunRecoveryService` 增加：

```java
TeamResumeState reconstructTeamState(String runId)
```

该接口隐藏以下实现细节：

- parent 事件折叠；
- childRunId 收集；
- child ledger 检查；
- 工具副作用分类；
- step 状态合法性校验；
- 恢复安全性判定。

`runtime/run/recovery` 使用中性的字符串状态和不可变 record，不依赖 package-private 的 `ExecutionStep`。

### 5.2 runtime/run/mode

`TeamModeAdapter` 与现有 `PlanModeAdapter` 对齐：

- 正常入口仍调用 `AgentOrchestrator.run(context, runStore)`；
- 新增 `executeRecovered(context, runStore, TeamResumeState)`；
- 只有持有真实 `AgentOrchestrator` 的 adapter 支持精确恢复；兼容 runner 构造路径返回明确错误。

### 5.3 runtime/run/AgentRuntime

`AgentRuntime.resume(...)` 在 Team 模式下：

1. 调用 `RunRecoveryService.inspect(runId)`；
2. 调用 `reconstructTeamState(runId)`；
3. 仅当状态可用时追加 `RUN_RESUMED`；
4. 调用 `TeamModeAdapter.executeRecovered(...)`；
5. 沿用现有 per-run 恢复锁和终态事件逻辑。

### 5.4 agent/team

`AgentOrchestrator` 负责 Team 语义：

- 初次规划解析成功后写 `TEAM_PLAN_DEFINED`；
- 在 execute/review child 启动前写 parent checkpoint；
- 在 review、worktree merge 和最终 step 状态转换处写 checkpoint；
- 新增 `runRecovered(...)`，把 `TeamResumeState` 转换为 package-private `ExecutionStep`；
- 恢复路径直接进入现有调度循环，不调用 planner。

编解码、事件投影和副作用安全判断不放入 `AgentOrchestrator`，避免继续扩大这个编排类的职责。

`SubAgent` 只补充账本观察，不承担恢复决策：每轮 LLM 返回 tool calls 时，
在工具调度前写入包含调用 ID、名称和参数的 `LLM_RESPONSE` 与
`TOOL_CALL_REQUESTED` 证据。现有 `AgentTurnKernel` 执行语义和 child loop
退出条件不变。

新增的逐轮 `LLM_RESPONSE` 使用 `recordKind=turn`；当前 orchestrator 在 child
返回后写入的汇总 `LLM_RESPONSE` 使用 `recordKind=child_summary`。恢复模块只把
`recordKind=turn` 的事件用于工具请求匹配，避免将汇总事件误判为损坏的逐轮事件。

## 6. 事件模型

### 6.1 TEAM_PLAN_DEFINED

新增 `AgentRunEventType.TEAM_PLAN_DEFINED`。

attributes：

| 字段 | 必填 | 含义 |
|---|---:|---|
| `schemaVersion` | 是 | checkpoint schema 版本，本阶段固定为 `1` |
| `planVersion` | 是 | Team 计划版本，本阶段固定为 `1` |
| `planJson` | 是 | 完整 step 定义及初始状态 |

`planJson` 对每个 step 保存：

- `id`
- `description`
- `type`
- `dependencies`
- `requiredTools`
- `preferredAgent`
- `riskLevel`
- 初始 `status=PENDING`
- 空 `result`

在 `parsePlan(...)` 成功且所有结构校验通过后、任何 child 启动前追加该事件。编码或 append 失败会终止本次运行，不进入执行阶段。

Team 本阶段没有运行期重规划，因此一个可恢复 run 只能有一个有效的 version 1 计划定义。出现未知版本、重复定义或定义后的未知 step 均视为 checkpoint 损坏。

### 6.2 TEAM_STEP_CHECKPOINT

新增 `AgentRunEventType.TEAM_STEP_CHECKPOINT`。

attributes：

| 字段 | 必填 | 含义 |
|---|---:|---|
| `schemaVersion` | 是 | 固定为 `1` |
| `planVersion` | 是 | 必须与计划定义一致 |
| `stepIdsJson` | 是 | 非空 JSON 数组；通常一个 step，结果传播时包含 leader 与 duplicates |
| `stepStatus` | 是 | `RUNNING/COMPLETED/FAILED/SKIPPED` |
| `phase` | 是 | `EXECUTING/REVIEWING/AWAITING_MERGE`，终态时为空字符串 |
| `attempt` | 是 | 非负整数；初次执行为 `0` |
| `childRunId` | 是 | execute/review 阶段对应 child；无对应 child 时为空字符串 |
| `result` | 是 | 完成结果或待合并候选结果；其他阶段为空字符串 |
| `error` | 是 | 失败原因；其他阶段为空字符串 |

所有字段始终存在，避免把“字段缺失”和“字段为空”混为一谈。

`stepIdsJson` 中多个 step 必须：

- 都存在于当前计划；
- 不重复；
- 具有相同执行指纹；
- 接受同一个业务状态和结果。

这样 leader 和 duplicates 可由一次 JSONL append 原子确认，避免 leader 已完成而 duplicate 被恢复后重复执行。

## 7. step 状态与 phase

现有业务状态保持不变：

```text
PENDING / RUNNING / COMPLETED / FAILED / SKIPPED
```

恢复 checkpoint 只增加三个必要 phase：

```text
EXECUTING / REVIEWING / AWAITING_MERGE
```

定义如下：

| stepStatus | phase | 含义 |
|---|---|---|
| `RUNNING` | `EXECUTING` | execute child 已关联，可能尚未启动或正在运行 |
| `RUNNING` | `REVIEWING` | execute 已返回候选结果，review child 已关联 |
| `RUNNING` | `AWAITING_MERGE` | worktree 内 review 已通过，尚未确认主工作区 merge |
| `COMPLETED` | 空 | 结果已被接受；写入型 worktree 已合并到主工作区 |
| `FAILED` | 空 | step 已确定失败，不在恢复时自动重试 |
| `SKIPPED` | 空 | step 已确定跳过 |

初始 `PENDING` 由 `TEAM_PLAN_DEFINED` 表达，不单独写 checkpoint。`EXECUTED` 不作为独立 phase，因为 execute 返回后会立即为 review child 建立 `REVIEWING` checkpoint。

## 8. 正常执行数据流

### 8.1 只读或主工作区串行步骤

```text
TEAM_PLAN_DEFINED
  -> parent TEAM_STEP_CHECKPOINT(RUNNING, EXECUTING, executeChildRunId)
  -> execute child RUN_STARTED ... terminal
  -> parent TEAM_STEP_CHECKPOINT(RUNNING, REVIEWING, reviewChildRunId)
  -> review child RUN_STARTED ... review decision terminal
  -> parent TEAM_STEP_CHECKPOINT(COMPLETED, phase="", acceptedResult)
```

若 review 拒绝并允许修复，下一次 attempt 重复 `EXECUTING -> REVIEWING`。最终批准后写 `COMPLETED`，达到重试上限或确定失败时写 `FAILED`。

主工作区串行步骤可能在 review 前已经产生副作用。因此在其非终态阶段崩溃时，必须检查全部 execute child ledger；发现成功副作用即 fail closed。

### 8.2 worktree 并行写入步骤

```text
parent EXECUTING
  -> worktree execute child
  -> parent REVIEWING
  -> review child approved
  -> parent AWAITING_MERGE
  -> mergeBatchAndDispose succeeds
  -> parent COMPLETED
```

`runStepWithWorker(...)` 当前会在 review 通过时把内存中的 step 标记为 `COMPLETED`。实现时可以保留该批次内部候选状态，但不能在此处持久化 `COMPLETED`。持久化终态必须由批量 merge 成功分支统一追加。

如果任一步未完成、merge 冲突或 merge 抛错：

- 不写 `COMPLETED`；
- 按现有行为丢弃或保留需人工检查的 worktree；
- 写明确的 `FAILED` checkpoint，前提是实现能够确认主工作区未被部分更新；
- 若无法确认 merge 是否部分生效，则保持非终态并让恢复 fail closed。

### 8.3 重复步骤

指纹相同的 leader 与 duplicates 仍只执行 leader。传播完成、失败或跳过结果时，使用同一条 `TEAM_STEP_CHECKPOINT` 的 `stepIdsJson` 同时更新整个组。

对只读组，如果进程在结果传播前退出，重复执行仍无副作用；但正常成功路径仍应使用组 checkpoint。对写入组，组 checkpoint 是防止 duplicate 在恢复后重复写入的必要条件。

## 9. parent-child 关联

当前 child run 存储在 `parentRun/children/childRun/`，但 parent ledger 不包含可通过 `RunStore` 使用的 child ID 列表。本设计不扩展 `RunStore`，而是在 child 启动前把 `childRunId` 写入 parent checkpoint。

顺序必须为：

1. 创建 child `AgentRunContext`，得到 childRunId；
2. parent append `TEAM_STEP_CHECKPOINT`；
3. child append `RUN_STARTED`；
4. child LLM 返回 tool calls 后 append `LLM_RESPONSE` 与 `TOOL_CALL_REQUESTED`；
5. child 执行工具并 append `TOOL_OUTCOME`。

如果步骤 2 失败，步骤 3 和 4 不得发生。如果步骤 2 成功而进程在步骤 3 前退出，`runStore.events(childRunId)` 为空，恢复模块可确认 child 尚未留下任何执行事件。

每次 retry 都使用新 childRunId。恢复模块收集该 step 在当前计划定义之后引用过的全部 childRunId，并检查全部 child ledger，不能只检查最后一次 attempt。

不能只依赖 `TOOL_OUTCOME` 判断“没有副作用”。Team child 可能在工具真正执行后、
结果落账前退出；如果没有预先记录请求，恢复会把这种情况误判为未执行。因此 child
必须在调度工具之前记录与 ReAct 兼容的请求证据。恢复检查按 toolCallId 匹配请求和结果：

- 已请求但没有结果：不完整，fail closed；
- 结果 ID、工具名或参数与请求不一致：非法，fail closed；
- 非 `COMPLETED` 结果：不作为成功结果复用，并按不完整执行 fail closed；
- 每个请求都有匹配结果后，才继续判断这些工具是否只读。

child 的 `TOOL_OUTCOME` 还必须像 ReAct ledger 一样保存 `argumentsJson`；否则只能
核对调用 ID 和工具名，无法证明结果属于同一组参数。工具正文是否保存沿用现有
账本预算策略，不作为 Team step 边界恢复的必要条件。

## 10. 恢复投影算法

`RunRecoveryService.reconstructTeamState(runId)` 按以下顺序执行：

1. 加载 parent events。
2. 验证原始输入、workspace 和 mode。
3. 定位唯一有效的 `TEAM_PLAN_DEFINED`。
4. 解码并验证完整计划、step ID、依赖和字段。
5. 按 parent `seq` 顺序应用后续 `TEAM_STEP_CHECKPOINT`。
6. 校验 checkpoint 的 schemaVersion、planVersion、状态转换和 stepIds。
7. 收集每个非终态 step 引用过的 childRunId。
8. 通过 `RunStore.events(childRunId)` 加载 child ledger。
9. 按 child 的 `LLM_RESPONSE` / `TOOL_CALL_REQUESTED` / `TOOL_OUTCOME`
   匹配工具请求与结果，检查不完整调用、损坏顺序或成功副作用。
10. 根据恢复判定表生成不可变 `TeamResumeState`。

跨 step 的 parent checkpoint 顺序不表达调度依赖，只表达落账顺序；并行线程之间的事件先后不影响各 step 的独立投影。同一 step 内的 checkpoint 必须按 attempt 和 phase 合法递进。

## 11. 恢复判定表

| 最新状态 | child 证据 | 恢复结果 |
|---|---|---|
| `COMPLETED` | 任意已归属历史 | 保留结果，不重跑 |
| `SKIPPED` | 任意已归属历史 | 保留跳过状态 |
| `FAILED` | 任意已归属历史 | 保留失败状态，不自动重试 |
| 计划初始 `PENDING` | 无 | 保持 `PENDING` |
| `EXECUTING` | 引用 child events 为空 | child 尚未启动，回到 `PENDING` |
| `EXECUTING` | child 已完整结束，且只有已完成只读工具或无工具 | 回到 `PENDING`，允许重新执行 |
| `EXECUTING` | 任一 child 有成功副作用 | 不可恢复，风险 `UNKNOWN` |
| `EXECUTING` | child 有未完成、非成功或顺序非法的工具调用 | 不可恢复，风险 `UNKNOWN` |
| `REVIEWING` | 任意 | 本阶段不推断 review 结果，不可恢复 |
| `AWAITING_MERGE` | 任意 | 无法确认主工作区 merge 状态，不可恢复 |

“成功副作用”采用保守分类。以下工具视为只读：

```text
read_file, list_dir, glob_files, grep_code,
web_search, web_fetch, search_memory, read_memory
```

其他未知工具、`write_file`、`create_project`、`execute_command`、`save_memory`、`revert_turn` 和所有 `mcp__*` 均视为可能产生副作用。恢复分类不复用 Team 调度阶段对低风险 command 的乐观判断。

只有 parent 已记录 childRunId、而 `runStore.events(childRunId)` 返回空列表时，才把它解释为
“checkpoint 已落账但 child 尚未启动”。只要 child 已出现 `RUN_STARTED`，却缺少完整的工具
请求/结果或 child 终态，就不能按“无副作用”处理。

## 12. 风险与确认策略

Team checkpoint 完整且所有非终态 step 可安全回到 `PENDING` 时，`resumeAvailable=true`。

- 历史终态 step 只包含只读工具：风险 `LOW`，无需 `--confirm`。
- 历史已完成 step 包含副作用工具，但其终态 checkpoint 完整：恢复可用，风险 `HIGH`，沿用 `/run resume <runId> --confirm`。
- 任一非终态 step 存在成功副作用、不完整 child、`REVIEWING` 或 `AWAITING_MERGE`：风险 `UNKNOWN`，`resumeAvailable=false`；即使 `--confirm` 也不能继续。

旧 Team run 没有 `TEAM_PLAN_DEFINED` 时不得回退为整 run 自动重试，inspect 明确返回“旧 Team run 缺少精确恢复 checkpoint”。终态旧 run 的查看不受影响。

## 13. RunState 与 CLI

`RunStateProjector` 将 `TEAM_PLAN_DEFINED` 和 `TEAM_STEP_CHECKPOINT` 视为可恢复进度事件，使 parent 状态不依赖之前是否恰好记录过 `LLM_RESPONSE`。

`RunRecoveryService.inspect(...)` 在 mode 为 TEAM 时执行 Team checkpoint 校验，并用 Team 专用分类覆盖当前只扫描 parent events 的通用判断。

`/run inspect` 增加恢复风险和 `RunResumePlan.reason` 输出。它不把 child events 混入 parent 的 `RunRecoveryPlan.events`，避免 parent/child seq 和事件语义混杂；具体阻塞 step、phase、childRunId 与工具名通过恢复原因呈现。

`CliRecoverableRunDiscovery` 无需新增分支：它继续依赖 `inspect(...).resumeAvailable()`，会自然排除旧账本与 fail-closed Team run。

`CliRunResumer` 已能创建 Team adapter，不改变命令语法；恢复能力由 `AgentRuntime` 和 `TeamModeAdapter` 接入。

## 14. 异常处理

### 14.1 checkpoint 写入失败

- child 启动前写入失败：终止当前 run，child 不执行。
- 中间 phase 写入失败：终止当前 run，不继续进入下一 phase。
- merge 后 `COMPLETED` 写入失败：本次 run 失败；下次恢复看到 `AWAITING_MERGE` 并 fail closed。

checkpoint 编码和 append 异常不得仅记录 warning 后继续执行。

### 14.2 取消

- child 启动前取消：step 保持初始 `PENDING`。
- child 执行中取消：保留最后的 `EXECUTING` checkpoint，由 child ledger 决定是否可安全回到 `PENDING`。
- review 中取消：保留 `REVIEWING`，本阶段 fail closed。
- worktree 等待合并时取消：保留 `AWAITING_MERGE`，本阶段 fail closed。

现有内存模型可以为了本次输出临时显示失败或取消，但不得把“用户取消”误写成确定性 `FAILED` checkpoint。

### 14.3 checkpoint 损坏

以下情况直接返回不可用状态：

- 缺少计划定义；
- schemaVersion 或 planVersion 不一致；
- plan JSON 无法解析；
- step ID 重复或依赖未知 step；
- checkpoint 引用未知 step；
- stepIdsJson 为空、重复或包含不同指纹步骤；
- 状态与 phase 组合非法；
- attempt 倒退；
- childRunId 缺失或不安全；
- child 工具事件不完整或顺序非法。

## 15. 并发与原子性

`JsonlRunStore.append(...)` 已序列化事件写入并为每个 ledger 分配递增 seq；`InMemoryRunStore` 也按 runId 同步事件列表。Team 并行 step 可以并发提交 parent checkpoint，最终由 parent seq 给出确定顺序。

同一 step 的 phase 由执行该 step 的单个工作线程推进，不允许不同线程并发推进同一 step。`AgentOrchestrator.updateStep(...)` 现有同步保护继续保留。

结果组的 `stepIdsJson` 在一条事件内提交，解决 leader/duplicate 的原子传播问题。不同 worktree step 在 merge 后可以分别追加 `COMPLETED`：若进程在这些事件之间退出，缺少终态的 step 因 child 副作用证据而 fail closed，不会被自动重跑。第一阶段不为整个 wave 新增事务事件。

`AgentRuntime` 现有 per-run 锁继续防止同一个 run 被同时恢复。本阶段不解决 `JsonlRunStore` 的跨 run 全局同步性能问题。

## 16. 测试设计

### 16.1 TeamCheckpointCodecTest

覆盖：

- 完整计划往返编码；
- 非法 schemaVersion/planVersion；
- 重复 step ID、未知依赖；
- 非法 status/phase 组合；
- 缺失必填字段；
- 多 step 原子 checkpoint；
- 不同指纹 step 被错误放入同一 checkpoint。

### 16.2 RunRecoveryServiceTest

覆盖：

- `COMPLETED/SKIPPED/FAILED` 投影；
- 初始 `PENDING`；
- EXECUTING 且 child events 为空；
- EXECUTING 且 child 只有完整只读调用；
- EXECUTING 且 child 有成功副作用；
- child 工具调用不完整或顺序非法；
- child 缺少工具调度前的请求证据；
- REVIEWING 与 AWAITING_MERGE fail closed；
- 多 attempt 的全部 child 均被检查；
- 旧 Team run 缺少计划定义；
- checkpoint 版本、step 和状态损坏；
- 已完成副作用 step 只要求确认而不重复执行。

### 16.3 AgentOrchestratorTest

覆盖：

- 恢复路径不调用 planner；
- 已完成 step 不分配 Agent；
- 下游 step 获得已完成依赖的结果；
- parent checkpoint 早于 child RUN_STARTED；
- child 的 LLM/tool request 证据早于工具执行和 TOOL_OUTCOME；
- review 未批准不写 COMPLETED；
- worktree merge 前只写 AWAITING_MERGE；
- merge 成功后才写 COMPLETED；
- merge 冲突不写 COMPLETED；
- leader 与 duplicates 通过同一 checkpoint 完成；
- 取消不会写错误的确定性 FAILED checkpoint。

### 16.4 Runtime 与 CLI 测试

覆盖：

- `TeamModeAdapter.executeRecovered(...)`；
- `AgentRuntime` 只在 Team 状态重建成功后追加 RUN_RESUMED；
- 同一 run 的并发恢复仍串行；
- `/run inspect` 展示风险与阻塞原因；
- HIGH 风险要求 `--confirm`；
- UNKNOWN 风险即使有 `--confirm` 也拒绝。

### 16.5 TeamExactResumeEvalTest

新增离线确定性端到端评测：

1. 第一个只读 step 完成后取消，恢复只执行剩余 step。
2. 写入工具成功、终态 checkpoint 前崩溃，恢复被阻止。
3. review 开始后崩溃，候选结果不被视为完成。
4. worktree review 通过、merge 前崩溃，恢复被阻止。
5. merge 成功且 COMPLETED 已落账后崩溃，恢复不重复写入。
6. duplicate step 恢复后不重复执行。
7. `JsonlRunStore` 重新实例化后仍能通过 childRunId 重建。

评测使用 scripted LLM，不联网、不依赖 API Key，并同时验证最终工作区 Outcome 和 parent/child ledger。

## 17. 验证命令

针对性验证：

```bash
mvn test -Dtest=TeamCheckpointCodecTest,RunRecoveryServiceTest,AgentOrchestratorTest,AgentRuntimeTest,CliRunResumerTest,TeamExactResumeEvalTest
```

完整回归：

```bash
mvn test -Pquick -DskipTests=false
git diff --check
```

## 18. 文档同步

实现完成后同步：

- `AGENTS.md`：Team step 恢复语义、worktree merge 完成边界和 fail-closed 条件；
- `README.md`：三种模式的恢复粒度与 `/run resume` 行为；
- `ROADMAP.md`：Team 精确恢复从未交付更新为第一阶段已交付，并保留 child 内部断点恢复为后续能力。

## 19. 完成标准

只有同时满足以下条件才算本阶段完成：

1. Team 恢复不重新规划。
2. 已完成 step 和 duplicate 不重复执行。
3. worktree merge 前不会落 `COMPLETED`。
4. 非终态成功副作用、REVIEWING 和 AWAITING_MERGE 均 fail closed。
5. 恢复后的下游步骤能使用已完成依赖结果。
6. 旧 Team ledger 明确拒绝精确恢复。
7. 针对性测试、离线评测、quick profile 与 `git diff --check` 全部通过。
