# 简化 /team 写入边界设计

- 日期：2026-08-20
- 范围：`/team` Multi-Agent 写入调度、worktree 隔离、工具层保护
- 状态：设计中

## 1. 核心结论

当前设计复杂，不是因为“有写入保护”，而是因为 `writeScope` 同时承担了三件事：

- 让 LLM 在规划阶段声明写入范围
- 让 Scheduler 判断写入 step 能否并行
- 让 ToolRegistry 按 step 动态拒绝越界写入

建议改成更简单稳定的两层：

- **并发安全交给 worktree + git merge**：多个写入 step 并行时隔离到不同 worktree，merge 时发现冲突。
- **基础安全交给工具策略层**：继续保留 `PathGuard`、`CommandGuard`、HITL、`ToolDispatcher` / `ResourceLockManager`。

一句话：**删除 LLM 生成的 step-level 动态 `writeScope`，不要删除工具层硬保护。**

## 2. 关键区分

`scope` 和 `writeScope` 不是同一个东西。

| 概念 | 作用 | 是否保留 |
|------|------|----------|
| `ToolResourceClassifier` 的资源 scope | 判断一次工具调用需要锁住哪些资源，例如 file / workspace / MCP server | 保留 |
| `writeScope` | 让 LLM 给每个 step 生成允许写入路径，并参与调度和工具拒绝 | 删除 |

资源 scope 解决“工具调用之间会不会并发冲突”。

`writeScope` 解决“这个 step 被允许写哪里”。这个判断不应该依赖 LLM 每次动态生成。

## 3. 目标流程

```text
TEAM_PLANNER
  -> 只拆任务、声明依赖、类型、工具、preferredAgent、riskLevel
  -> 不再生成 writeScope

TeamScheduler
  -> 依赖就绪
  -> step 指纹去重
  -> 只读 step 并行
  -> 同一 ready wave 内的写入 step 分组

写入执行
  -> 单个写入 step：主工作区串行
  -> 同一 ready wave 内多个无依赖写入 step：优先 worktree 并行
  -> 有依赖链的写入 step：按依赖顺序串行，后序 step 基于前序 merge 后的新基线执行
  -> worktree 不可用 / git 不可用 / 工作区不适合并行：回退串行

合并
  -> merge 成功：step 完成
  -> merge 冲突：step 失败，报告冲突文件，不静默覆盖
```

## 4. 要删除

- `PlanTaskSpec.writeScope`
- `ExecutionStep.writeScope`
- `PlanSchemaParser` 中的 `writeScope` 解析
- `TeamScheduler.mutatingSerialReasons`
- `ScheduleWave.serialReasons`
- step fingerprint 中的 `writeScope`
- `AgentOrchestrator` 中的 `forbiddenWriteScope` / `writeScopeFor` / `appendFileOwnershipContext`
- child run metadata 中的 `writeScope` / `forbiddenWriteScope`
- `ToolRegistry.setWriteScope`
- `ToolRegistry.enforceWriteScope`
- `ToolRegistry.enforceCommandWriteScope`
- `WriteScopeRules`
- `team-planner.md` 中的 `writeScope` 字段要求

## 5. 要保留

- `PathGuard`：路径必须在 workspace 内
- `CommandGuard`：危险命令拒绝
- HITL / ApprovalPolicy：高风险工具仍按策略审批
- `ToolDispatcher` / `ResourceLockManager`：工具调用级资源锁
- `GitWorktreeManager`：写入 step 的物理隔离
- Worker 自审 / repair 循环
- merge 冲突 fail closed

## 6. 推荐落地顺序

1. **先改 Scheduler**
   删除 scope 重叠判断，只保留依赖、去重、读写分区。

2. **再改 Orchestrator**
   同一 ready wave 内的多写入 step 优先走 worktree；依赖链上的写入 step 必须等前序 step merge 后再执行。删除 `writeScope` prompt 注入和 metadata。

3. **再改 schema / prompt**
   删除 `writeScope` 字段，要求 step description 写清修改对象即可，但 description 不作为硬权限。

4. **最后改 ToolRegistry**
   删除动态 `setWriteScope` guard，保留 workspace 级工具保护。

5. **补测试**
   覆盖：无 `writeScope` 计划可解析、多写 step 进入 worktree、worktree 不可用回退串行、merge 冲突失败、资源锁仍生效。

6. **删旧测试**
   共 5 个测试文件引用 `writeScope`，按性质分类处理：

   | 测试文件 | 处理方式 |
   |---------|---------|
   | `WriteScopeRulesTest` | 整个文件删除 |
   | `TeamSchedulerTest` | 删 scope 重叠 / `serialReasons` 用例 |
   | `ToolRegistryTest` | 删 `setWriteScope` / 越界拒绝用例 |
   | `PlanSchemaParserTest` | 删 `shouldParseWriteScopeFromPlanTasks`（第 85-96 行） |
   | `AgentOrchestratorTest` | 见下，改动量最大 |

   `AgentOrchestratorTest` 中至少 7 个测试方法绑定旧语义，需分类：
   - **直接删除**：`shouldParseWriteScopeFromPlanTasks`、`teamWriteScopeDoesNotLeakToSharedRegistryAfterRun`、`mixedDeclaredAndUndeclaredWriteScopesSerializeWholeBatch`、`mutatingReadyBatchReportsOverlappingWriteScopeAsSerialReason`
   - **重写**（去掉 `writeScope` 前提，保留核心语义）：
     - `disjointWriteScopesFallBackToSerialWhenNotGitRepo` → 多写 step 非 git 仓库回退串行
     - `disjointWriteScopesRunInParallelViaWorktreeIsolation` → 多写 step worktree 并行
     - 第 1091-1168 行 worker attributes 断言（`writeScope` / `forbiddenWriteScope`）

## 7. 风险与兜底

| 风险 | 兜底 |
|------|------|
| worker 改到 description 没写清的文件 | PathGuard 限制 workspace，worktree merge 暴露冲突 |
| git 自动 merge 但存在语义冲突 | merge 后跑编译 / 测试，至少执行 quick verification；无法运行时做 merge diff 二次审查并显式上报 |
| 脏工作区下 worktree 基线复杂 | 第一版保守处理：工作区不干净时回退串行 |
| 以后确实需要细粒度写权限 | 新增静态 `TeamRunPolicy.writableRoots`，不要恢复 LLM 动态 `writeScope` |

## 8. 最终原则

- 并发冲突不要靠 LLM 预测，靠 worktree 隔离和 git merge 事实判断。
- 基础安全不要靠 prompt，靠工具策略层硬拒绝。
- 文件所有权可以写在 description 里帮助 worker 理解，但不能当权限系统。
- 后续如需写权限收窄，做静态 policy，不做 step-level 动态字段。
