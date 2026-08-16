# MindCLI ToolRegistry 批次 2 剩余内置工具迁移技术文档

文档类型: 合并批次实施说明  
编写日期: 2026-08-10  
对应总方案: `docs/mindcli-tool-registry-structure-refactor.md` Step 3

## 1. 本批次目标

将剩余内置工具的注册声明从 `ToolRegistry` 迁移到 `tool/builtin/*Registrar`，降低 `ToolRegistry` 对工具 schema 和工具族归属的直接承担。

迁移工具:

- `search_code`
- `web_search`
- `web_fetch`
- `browser_connect`
- `browser_disconnect`
- `browser_status`
- `load_skill`
- `save_memory`
- `revert_turn`

## 2. 目标文件

新增:

- `src/main/java/com/mindcli/tool/builtin/RagToolRegistrar.java`
- `src/main/java/com/mindcli/tool/builtin/WebToolRegistrar.java`
- `src/main/java/com/mindcli/tool/builtin/BrowserToolRegistrar.java`
- `src/main/java/com/mindcli/tool/builtin/SkillToolRegistrar.java`
- `src/main/java/com/mindcli/tool/builtin/MemoryToolRegistrar.java`
- `src/main/java/com/mindcli/tool/builtin/SnapshotToolRegistrar.java`

修改:

- `src/main/java/com/mindcli/tool/ToolRegistry.java`
- `src/test/java/com/mindcli/tool/ToolRegistryTest.java`

## 3. 极简实现策略

本批次继续只拆注册声明，不重写执行算法:

- `ToolRegistry` 构造函数改为调用对应 registrar。
- registrar 负责工具名称、描述、参数 schema 与 executor 绑定。
- `ToolRegistry` 暴露薄包装 executor 方法，复用原有字段和私有 helper。
- StepSearch MCP fallback、browser connector、skill registry、memory saver、snapshot service 行为保持在原执行方法中。

这样可以把结构归属改对，同时不引入跨模块行为差异。

## 4. 行为保持要求

- `search_code` 未索引提示、top_k 默认值和上限保持不变。
- `web_search` / `web_fetch` 的 StepSearch 优先逻辑、provider fallback、网络策略和输出格式保持不变。
- `browser_*` 在 `browserConnector == null` 时的提示保持不变。
- `load_skill` 的未初始化、未找到、禁用、正文 5KB 截断保持不变。
- `save_memory` 只通过 `memorySaver` 写入，空 fact / 未初始化提示保持不变。
- `revert_turn` 的 offset 默认值、`restorePreTurn` 调用和错误格式保持不变。

## 5. 验证策略

先补结构测试:

```java
assertInstanceOf(ToolRegistrar.class, new RagToolRegistrar());
assertInstanceOf(ToolRegistrar.class, new WebToolRegistrar());
assertInstanceOf(ToolRegistrar.class, new BrowserToolRegistrar());
assertInstanceOf(ToolRegistrar.class, new SkillToolRegistrar());
assertInstanceOf(ToolRegistrar.class, new MemoryToolRegistrar());
assertInstanceOf(ToolRegistrar.class, new SnapshotToolRegistrar());
```

并验证 `ToolRegistry` 构造后仍包含本批次工具名。

批次验证:

```bash
mvn test "-Dtest=ToolRegistryTest,ApprovalPolicyTest,CodeSearchGoldenSetTest" -DskipTests=false
```

## 6. MCP 暂不纳入本批次

MCP 动态注册涉及:

- `mcpTools` 动态 map
- `registerMcpToolOutput`
- `replaceMcpToolOutputsForServer`
- StepSearch fallback 对 MCP schema 的读取

该部分保留到下一个批次独立处理，避免把静态内置工具注册迁移和动态工具命名空间迁移混在一起。
