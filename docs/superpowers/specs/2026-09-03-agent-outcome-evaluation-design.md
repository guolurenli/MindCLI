# Agent 端到端结果评测第一阶段设计

## 目标

为 MindCLI 建立一组不依赖真实 API Key、可重复运行的端到端场景测试，用确定性证据回答：Agent 是否完成了代码任务、是否遵守安全约束、账本是否与真实工作区一致。

本阶段直接服务后续 Plan/Team 精确恢复，但不实现恢复逻辑，也不建设通用评测平台。

## 设计原则

- 复用现有 `AgentRuntime`、ReAct/Plan/Team adapter、`RunStore`、`ToolRegistry` 和 Maven/JUnit。
- 评测以最终环境结果为主，账本轨迹为辅；不把 Agent 的自然语言自述当成成功证据。
- scripted LLM 提供固定响应，默认测试不联网、不消费 Token。
- 使用 `@TempDir` 隔离工作区；测试结束后不向真实项目写入业务文件。
- 只建立一个测试侧深模块，不新增生产侧评测框架、数据库、YAML DSL、Web 看板或 LLM Judge。

## 测试模块

新增测试包：

```text
src/test/java/com/mindcli/eval/
├── AgentEvalFixture.java
├── ReactOutcomeEvalTest.java
├── PlanOutcomeEvalTest.java
├── TeamOutcomeEvalTest.java
└── RuntimeSafetyEvalTest.java
```

### `AgentEvalFixture`

它是测试侧唯一共享模块，interface 保持很小：

```java
AgentEvalFixture.workspace(Map<String, String> files)
AgentEvalResult runReact(ScriptedLlmClient llm, String prompt)
AgentEvalResult runPlan(ScriptedLlmClient llm, String prompt)
AgentEvalResult runTeam(ScriptedLlmClient llm, String prompt)
```

`AgentEvalResult` 只暴露评测需要的结果：

- 临时工作区路径；
- `AgentRunResult`；
- 该 run 的 ledger events；
- 工具调用计数；
- 最终文件读取和相对路径枚举 helper。

fixture 负责依赖组装和清理，场景测试只描述输入、执行和断言。若现有测试中的 scripted client 可以复用，则移动或提取为测试工具；不在生产代码中新增 fake。

## 判定模型

每个场景使用确定性断言，按以下优先级判断：

1. **Outcome**：文件内容、文件集合、编译/测试结果或外部目标是否正确。
2. **Safety**：是否存在越界修改、被拒绝操作的副作用或错误 profile 权限。
3. **Ledger invariants**：run 状态、tool outcome、parent/child 关系和完成状态是否与 Outcome 一致。
4. **Efficiency signal**：工具调用次数和重复副作用次数；第一阶段只对明确重复调用设硬断言，不制定通用最优步数。

不把精确自然语言、唯一搜索顺序或固定 reasoning 文本作为断言，避免测试过度耦合 Prompt 表达。

## 首批八个场景

### ReAct

1. **实时定位并读取代码**
   - fixture 含多个相似文件。
   - scripted LLM 调用 `grep_code` 和 `read_file`。
   - 断言读取目标正确，run 成功，账本包含相应成功 outcome。

2. **单文件安全修改**
   - scripted LLM 读取后调用 `write_file`。
   - 断言目标文件内容正确、无额外业务文件变化、`write_file` 仅成功一次。

### Plan

3. **DAG 依赖顺序**
   - 两个 task：先生成输入文件，再读取或验证它。
   - 断言后置 task 只能在前置 task 完成后产生成功 outcome，最终文件正确。

4. **显式降级语义**
   - 覆盖一个 `critical=false + degradation=SKIP` 任务及其下游。
   - 断言跳过状态、下游行为和最终 run 状态一致；不在同一场景混测 `BLOCK` 与局部重规划。

### Team

5. **Profile 路由和权限**
   - 一个只读 step、一个写入 step。
   - 断言 parent/child ledger 中只读步骤使用 Explorer、写入步骤使用 Worker，且 Explorer 没有成功执行写工具。

6. **Review fail-closed**
   - 执行候选产生后，review 固定拒绝直到重试耗尽。
   - 断言 step 和 parent run 不得标记为成功，账本保留 `approved=false` 与失败业务状态。

### Runtime / Safety

7. **策略拒绝无副作用**
   - 尝试写入 workspace 外路径或执行明确拒绝命令。
   - 断言外部目标不变，outcome 为结构化拒绝状态，run 不得谎报成功。

8. **恢复工具幂等**
   - 预置相同 `runId + toolCallId + 工具名 + 参数` 的成功 outcome，再以恢复上下文调度。
   - 断言复用原结果、真实写入执行器不再次调用、碰撞参数返回 `IDEMPOTENCY_KEY_COLLISION`。

已有单元测试若已完整覆盖某项内部逻辑，评测场景仍必须从统一执行入口验证最终 Outcome；但不重复穷举内部边界。

## 数据流

```text
fixture 创建临时工作区
        ↓
scripted LLM + 真实 ToolRegistry/策略
        ↓
AgentRuntime → mode adapter → Agent loop/tool dispatcher
        ↓                         ↓
最终工作区 Outcome            Run Ledger trace
        └────────────┬────────────┘
                     ↓
               JUnit 确定性断言
```

## 错误处理与可诊断性

- 每个场景只验证一个主要能力，失败名称直接说明能力和预期。
- fixture 不吞异常；组装失败直接让测试失败。
- 文件断言输出相对路径和预期/实际内容。
- ledger 断言输出该 run 的事件类型、关键 attributes 和顺序。
- Windows 下 Git/worktree 不稳定或耗时的路径不进入首批八个场景；现有专门测试继续覆盖 worktree。
- 不通过 sleep 等待异步状态；需要等待时使用已有可完成条件或 future。

## Maven 集成

- 八个确定性场景纳入现有 `mvn test -Pquick`。
- 不新增必须设置的环境变量。
- 不运行真实模型和网络服务。
- 第一阶段不创建 `agent-eval-live` profile；真实模型评测作为后续独立设计。

## 非目标

- 不比较 GLM、DeepSeek、Kimi 等真实模型能力。
- 不计算 pass@k、Token 成本排行榜或统计显著性。
- 不实现通用 grader interface、权重评分或 YAML 场景格式。
- 不修改 ReAct、Plan、Team 的生产行为以迎合测试。
- 不在本阶段实现 Plan task 或 Team step 的精确恢复。

## 验收标准

1. 首批八个场景均通过，并纳入 `-Pquick`。
2. 全部场景离线、确定性、无需 API Key。
3. 场景同时验证至少一个 Outcome 和对应 ledger invariant，而非只看最终回复。
4. 安全场景证明被拒绝操作没有产生真实副作用。
5. 幂等场景证明恢复不会重复执行已完成的副作用工具。
6. 不新增生产依赖，不建设独立评测平台。
7. `mvn test -Pquick -DskipTests=false` 与 `git diff --check` 通过。

## 后续阶段

评测基线完成后，按以下顺序继续：

1. 为 Plan 持久化 plan/task 定义、状态和结果，增加“已完成 task 不重跑”的故障注入场景。
2. 为 Team 持久化 parent step 投影并核对 child execute/review 状态，增加“已审查通过 step 不重跑”的场景。
3. 单独评估真实模型 profile 与小规模能力评测，不影响默认 CI。
