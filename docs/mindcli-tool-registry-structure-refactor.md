# MindCLI ToolRegistry 结构改造技术文档

文档类型: 步骤级技术方案  
编写日期: 2026-08-10  
适用范围: `src/main/java/com/mindcli/tool/`, `src/test/java/com/mindcli/tool/`, `AGENTS.md`, `README.md`

## 1. 背景

当前 `ToolRegistry.java` 已经承担文件工具、命令工具、代码搜索、RAG、联网、浏览器、记忆、Skill、Snapshot、MCP 动态工具注册、审计桥接和上下文配置等职责。它仍然能工作，但已经成为一个浅接口背后的超大实现类，后续继续增加工具族会让修改风险集中在同一个文件。

本次改造目标是把 `ToolRegistry` 从“所有工具实现都放在一个类里”调整为“注册表协调 + 工具族 registrar”的结构。外部调用方仍使用 `ToolRegistry`，工具名称、参数 schema、返回文本、审批和审计行为不变。

## 2. 非目标

- 不修改任何工具名称。
- 不修改任何工具参数 schema。
- 不修改 `ToolRegistry` 对外兼容方法。
- 不修改 ReAct / Plan / Multi-Agent 执行流程。
- 不新增第三方依赖。
- 不做 MCP OAuth / sampling / recovery。

## 3. 当前问题

| 问题 | 当前表现 | 风险 |
| --- | --- | --- |
| 主类过大 | `ToolRegistry.java` 超过 1400 行 | 小改动容易影响不相关工具 |
| 工具族混杂 | file/shell/web/browser/memory/MCP 注册逻辑在同一类 | 难以并行维护和测试 |
| 动态 MCP 与内置工具混合 | MCP 动态注册和内置工具注册共用大量私有逻辑 | 后续 MCP 增强容易扩大主类 |
| 审计、策略、上下文混在执行逻辑里 | browser guard、memory writer、snapshot、audit 交叉出现 | 企业级策略边界不够清晰 |

## 4. 目标结构

```text
tool/
├── ToolRegistry.java
├── ToolOutput.java
├── CodeSearchEngine.java
├── JavaCodeSearchEngine.java
├── RipgrepCodeSearchEngine.java
├── registry/
│   ├── ToolRegistrar.java
│   └── ToolRegistrationContext.java
└── builtin/
    ├── FileToolRegistrar.java
    ├── ShellToolRegistrar.java
    ├── CodeToolRegistrar.java
    ├── RagToolRegistrar.java
    ├── WebToolRegistrar.java
    ├── BrowserToolRegistrar.java
    ├── MemoryToolRegistrar.java
    ├── SkillToolRegistrar.java
    └── SnapshotToolRegistrar.java
```

`ToolRegistry` 保留为对外 seam。各 registrar 只是内部实现拆分，不直接暴露给 Agent、CLI、Plan 或 Multi-Agent。

注册边界:

- `registerTools` 是 `ToolRegistry` 包内入口，只给内置 registrar 和同包测试使用。
- registrar 注册工具时不能覆盖已有工具名，避免后注册项静默替换安全敏感工具。
- `mcp__` 前缀保留给 `McpToolNamespace` 动态工具，普通 registrar 不允许注册该前缀。
- registrar 通过 `ToolRegistrationContext.ToolExecutors` 获取执行桥，不直接暴露 `ToolRegistry` 上的工具执行 helper。

## 5. 改造步骤

### Step 1: 建立 registrar seam

新增:

- `tool/registry/ToolRegistrar.java`
- `tool/registry/ToolRegistrationContext.java`

要求:

- `ToolRegistry` 仍负责创建工具 map。
- registrar 只通过 context 注册工具。
- 当前步骤可以先只迁移最小工具族或新增测试用 registrar，确保 seam 可用。

验证:

```bash
mvn test -Dtest=ToolRegistryTest -DskipTests=false
```

### Step 2: 迁移低风险工具族

迁移:

- `read_file`
- `write_file`
- `list_dir`
- `glob_files`
- `grep_code`
- `execute_command`
- `create_project`

要求:

- 工具名称、描述、参数和输出保持一致。
- `write_file` observer 行为保持一致。
- `grep_code` 的 partial/suggested reads 行为保持一致。

验证:

```bash
mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest -DskipTests=false
```

### Step 3: 迁移中风险工具族

迁移:

- `search_code`
- `web_search`
- `web_fetch`
- `browser_*`
- `save_memory`
- `load_skill`
- `revert_turn`

要求:

- browser guard 仍在工具执行前后生效。
- memory writer 返回 `MemoryWriteResult.message()`。
- StepSearch MCP fallback 行为保持一致。
- Snapshot restore 行为保持一致。

验证:

```bash
mvn test -Dtest=ToolRegistryTest,ApprovalPolicyTest,CodeSearchGoldenSetTest -DskipTests=false
```

### Step 4: 隔离 MCP 动态注册

迁移:

- `registerMcpTool`
- `registerMcpToolOutput`
- `unregisterMcpTool`
- `replaceMcpToolsForServer`
- `replaceMcpToolOutputsForServer`

要求:

- 对外方法签名保持兼容。
- `mcp__{server}__{tool}` 命名不变。
- MCP tool output fallback 不变。

验证:

```bash
mvn test -Dtest=ToolRegistryTest,McpToolRegistrationTest,McpClientTest -DskipTests=false
```

### Step 5: 清理主类与同步文档

要求:

- `ToolRegistry.java` 只保留注册协调、对外兼容方法、上下文配置和执行入口。
- 更新 `AGENTS.md` 中工具结构说明。
- 如 README 描述工具注册实现，保持同步。

最终验证:

```bash
mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest,McpToolRegistrationTest -DskipTests=false
mvn test -Pquick
```

## 6. 验收标准

1. 所有内置工具仍能通过 `ToolRegistry` 查询和执行。
2. 现有工具 schema 与行为保持兼容。
3. MCP 动态工具注册和替换保持兼容。
4. HITL / PathGuard / CommandGuard / BrowserGuard / Memory policy 行为不回退。
5. `ToolRegistry.java` 行数明显下降，工具族逻辑进入对应 registrar。
6. 所有目标测试通过。

## 7. 风险控制

- 每一步只搬一类职责，不做跨模块功能改造。
- 迁移时优先复制现有行为，再删除旧私有方法。
- 测试不直接断言内部类名，只断言外部工具行为。
- 如果某个工具族测试失败，停止进入下一步，先修复该工具族。
