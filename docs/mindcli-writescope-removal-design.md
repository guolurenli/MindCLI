# 删除 writeScope 写前协调：对齐 Codex/Claude Code 的无条件 worktree 隔离

- 日期：2026-08-19
- 范围：`/team`（Multi-Agent）计划 schema、调度、编排、工具写范围强制
- 状态：设计中（未实现）
- 前置：`docs/mindcli-worktree-parallel-write-design.md`（worktree 隔离机制，已实现）

## 1. 背景与动机

MindCLI 的 `/team` 模式当前用「写前声明 writeScope」来协调多个写入子代理的并发写：规划阶段让 LLM 为每个写入 step 声明 `writeScope`（glob 范围），调度层判断 scope 是否重叠来决定「串行 vs worktree 并行」，工具层用 `setWriteScope` 强制写范围越界拒绝。

这套三层机制是**过度设计**：它想解决「多个写者并发写文件」的冲突，但业界主流（Codex、Claude Code）**都不做写前协调**——它们靠「worktree 变更隔离 + merge 事后仲裁」，冲突在 merge 时暴露再人工/主 agent 解决。

本设计删除 writeScope 写前协调，把 worktree 隔离从「按 scope 判断」改为「写型多步骤无条件启用」，对齐 Codex/Claude Code 的标准做法。

## 2. 业界基准（Codex vs Claude Code）

| 维度 | Codex | Claude Code |
|------|-------|-------------|
| 能力隔离 | sandbox（OS 级 Seatbelt/Landlock/AppContainer） | 工具级 allowlist（`tools`/`disallowedTools`） |
| 变更隔离 | git worktree | `isolation: worktree` 参数 |
| 写前协调 | 无（内置 `update_plan`/`spawn_agent` 无文件所有权字段） | 无（仅 prompt 软建议「assign file ownership」） |
| 冲突处理 | merge 时发现，人工仲裁 | merge 时发现 + checkpoint/file history 回滚 |

**共同点**：两者都不做「写前声明 scope」，都靠「worktree 变更隔离 + merge 事后仲裁」。写前声明（如 Codex 社区的 `allow_write_globs`/`forbid_write_globs`）属于第三方扩展，非内置标准。

**关键区别**：Codex 的 sandbox 是「会话级静态配置」，与「谁写哪个文件」正交；而 MindCLI 的 `writeScope` 是「任务级动态声明」，本质是写前协调。两者只是长得像，机制完全不同。

## 3. 现状分析：writeScope 三层消费链

writeScope 贯穿数据模型、调度、编排、工具四层，共 9 个主代码文件：

| 层 | 文件 | 用途 |
|----|------|------|
| 数据模型 | `PlanTaskSpec.java` / `ExecutionStep.java` / `PlanSchemaParser.java` | writeScope 字段与解析 |
| 调度 | `TeamScheduler.java` / `ScheduleWave.java` | `mutatingSerialReasons` 判断 scope 重叠 → `serialReasons` |
| 编排 | `AgentOrchestrator.java` | `forbiddenWriteScopes` / `writeScopeFor` / `appendFileOwnershipContext` / metadata / `setWriteScope` 调用 |
| 工具 | `ToolRegistry.java` + `WriteScopeRules.java` | `setWriteScope` + `enforceWriteScope` + `enforceCommandWriteScope` |
| Prompt | `team-planner.md` | 规则 13/14/15 + JSON 示例字段 |

**并发安全的两层边界（审计关键）**：

- `ToolDispatcher` 的 `ResourceLockManager` 锁（读 SHARED / 写 EXCLUSIVE）只防「两个 write 同时落盘」的物理冲突，**防不住「读-改-写」跨工具调用的丢失更新**：A、B 并发读 V0 → 各自改 → 串行写 V1、V2 → B 覆盖 A。
- 防丢失更新靠 worktree 隔离（每 step 独立工作区 + merge 冲突检测），这正是 Codex/Claude Code 的机制。

## 4. 目标与非目标

**目标**：

1. 删除 writeScope 字段、调度判断、执行强制三层，规划阶段 LLM 不再声明写范围。
2. 写型步骤：单步直接写；多步**无条件** worktree 隔离并行（不再依赖 scope 判断）。
3. merge 冲突报告冲突文件清单 + 标记 step FAILED，不静默覆盖（复用现有 `mergeWorktreeAndDispose`）。
4. 非 git 目录 / git 不可用 / worktree 创建失败时回退串行（复用现有兜底）。

**非目标（本次不做）**：

- 对齐 Codex sandbox 的「静态 writable_roots」能力隔离（记入第 9 节「后续可选增强」）。
- 写前 checkpoint 兜底机制（worktree 已提供隔离，见决策 2）。
- 恢复「主 agent 运行时动态判断是否 worktree」（MindCLI 是静态计划 + 规则执行，无运行时决策点）。

## 5. 目标设计

### 5.1 数据流（改后）

```
规划（TEAM_PLANNER：拆任务 + 派发 + 风险，无 writeScope 概念）
  → PlanTaskSpec / ExecutionStep（无 writeScope 字段）
  → TeamScheduler：依赖就绪 → 指纹去重 → 读写分区（无 serialReasons）
  → 只读组 → 并行（原有）
  → 写型组 → 单步直接写 / 多步无条件 worktree 并行（git 不可用 → 回退串行）
           → merge 冲突 → git 检测 → 报冲突清单 + 标记 FAILED → 主 agent/用户仲裁
```

### 5.2 与原始设计（mindcli-worktree-parallel-write-design.md）的差异

| 维度 | 原设计 | 本设计 |
|------|--------|--------|
| worktree 触发条件 | `mutatingSerialReasons` 命中（scope 未声明/重叠）→ 串行；scope 互不重叠 → 并行 | 多写步骤 → 无条件并行 |
| writeScope 字段 | 有，驱动调度判断 | 无，删除 |
| 文件所有权 | 硬字段 + prompt 注入 | description 里的自然语言软约束（规划时本就描述「改哪个文件」） |
| 工具写范围强制 | `setWriteScope` 动态强制 | 删除 |

## 6. 详细改动清单

### 6.1 数据模型（3 个文件）

- `PlanTaskSpec.java`：删 `writeScope` 字段，12 参构造 → 11 参。
- `PlanSchemaParser.java`：删 `writeScope` 解析（第 101-112 行）。
- `ExecutionStep.java`：删 `writeScope` 字段、各构造与 `withXxx` 的 writeScope 参数。

### 6.2 调度层（2 个文件）

- `TeamScheduler.java`：删 `mutatingSerialReasons`（第 110-144 行）+ `stepFingerprint` 里的 writeScope 项。
- `ScheduleWave.java`：删 `serialReasons` 字段，构造改 2 参。

### 6.3 编排层（1 个文件，改动最多）

`AgentOrchestrator.java`：

- `parsePlan`：删 writeScope 传参。
- 删 `forbiddenWriteScopes`、`writeScopeFor`、`appendFileOwnershipContext` 三个方法。
- `runMutatingGroups`：删 `anySerial` 判断，直接 `runMutatingBatchParallel`（多步）或 `runMutatingGroup`（单步）。
- `runStepOnRegistry` / `runStepInWorktree`：删 `setWriteScope` 调用。
- `childRunContext` / `appendAgentSelected`：删 writeScope/forbiddenWriteScope metadata。
- `runMutatingBatchParallel` / `runStepInWorktree` / `mergeWorktreeAndDispose`：保留，作为无条件 worktree 并行路径。

### 6.4 工具层（2 个文件）

- `ToolRegistry.java`：删 `writeScope` 字段、`setWriteScope`、`enforceWriteScope`、`enforceCommandWriteScope` 及相关调用点。
- `WriteScopeRules.java`：整个删除（`overlaps`/`containsPath` 均失去唯一调用方）。

### 6.5 Prompt（1 个文件）

- `team-planner.md`：删规则 13/14/15 + JSON 示例的 `"writeScope": []`（第 24 行）。保留规则 3「步骤描述要具体」作为软文件所有权。

## 7. 关键设计决策

1. **连删 `ToolRegistry.writeScope` 动态机制 + `WriteScopeRules`**：`setWriteScope` 是「每个 step 动态传写范围」，是写前协调的执行端，与 `ExecutionStep.writeScope` 字段配套；删字段后是孤儿能力，违背 YAGNI。它区别于 Codex sandbox 的「会话级静态 writable_roots」，不应复用这个动态壳。

2. **不加写前 checkpoint 兜底**：worktree 隔离已提供防丢失更新；git 本身是可回滚的静态能力。写前自动 checkpoint 会污染 git 历史（每次写前一个 commit），代价 > 收益。软约束失效是低频事件，冲突在 merge 时暴露，用户 `git` 回滚即可。

3. **worktree 从「按 scope」改为「无条件多步并行」**：单写步骤走单步路径（无 worktree 开销），多写步骤才走 worktree，而多写步骤恰是需要隔离的场景，故「过度隔离」是伪命题。这同时满足「并行 LLM 推理 + 防丢失更新 + 无写前声明」三者。

4. **文件所有权降级为 description 自然语言软约束**：规划时 LLM 在 `description` 里写「修改 X 文件」本就是所有权声明，无需单独字段；对齐 Claude Code 的「assign file ownership」软建议与 Codex 的「分文件域」。

## 8. 测试联动

| 测试文件 | 改动 |
|---------|------|
| `AgentOrchestratorTest.java` | 删/改约 7 处 writeScope、serialReasons、forbiddenWriteScope 断言（:115、:140、:462、:531、:592、:1091、:1149） |
| `TeamSchedulerTest.java` | 删 `serialReasons` 相关断言（:91-126） |
| `PlanSchemaParserTest.java` | 删 writeScope 解析测试（:85-96） |
| `WriteScopeRulesTest.java` | 整个删除 |
| `ToolRegistryTest.java` | 删 `setWriteScope`/`enforceWriteScope` 测试（:526-560） |

新增/保留：`GitWorktreeManagerTest`（worktree 隔离机制不受影响，保留）。

验证命令：`mvn test -DskipTests=false`

## 9. 风险与后续可选增强

**风险**：

1. **软约束失效 → 同文件覆盖**：删 writeScope 后，多写步骤靠 description 软分文件域；LLM 说漏了会在 merge 时暴露冲突（不静默覆盖），但需人工/主 agent 处理。缓解：merge 冲突报告清单已覆盖（第 4 节目标 3）。
2. **worktree checkpoint 固化脏工作区**：`runMutatingBatchParallel` 的 `commitCheckpoint` 会把用户未提交改动 `add -A + commit`（原始设计第 8 节已记录此痛点）。无条件 worktree 后触发面变大。缓解：后续优化为「工作区干净才 worktree 并行，脏则回退串行」或 `git stash create` 临时基线。

**后续可选增强（本次不做）**：

- 对齐 Codex sandbox 的「静态 writable_roots」：给 `AgentProfile` 加静态写根配置，工具层据此限制 `write_file` 越界（会话级，非任务级）。这补上删除 `ToolRegistry.writeScope` 后的「文件系统写边界」空缺。
- 主 agent 运行时动态 worktree 决策（对齐 Codex/Claude Code 的 `isolation: worktree` 参数语义）。

## 10. 一句话总结

把 writeScope 从「JSON 字段 + 调度判断 + 工具强制」三层写前协调，收敛为「description 软文件所有权 + 无条件 worktree 隔离 + merge 事后仲裁」，对齐 Codex/Claude Code 的标准做法——能力隔离靠已有的 `AgentToolPolicy`/`approvalPolicy`，变更隔离靠已有的 `GitWorktreeManager`，唯独删掉两者都没有的「写前协调」。
