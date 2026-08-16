# MindCLI ToolRegistry 批次 3 MCP 动态注册隔离技术文档

文档类型: 合并批次实施说明  
编写日期: 2026-08-10  
对应总方案: `docs/mindcli-tool-registry-structure-refactor.md` Step 4

## 1. 本批次目标

把 MCP 动态工具的状态和注册/替换逻辑从 `ToolRegistry` 主类隔离到专门模块，保持 `ToolRegistry` 现有公开方法签名不变。

涉及公开方法:

- `registerMcpTool`
- `registerMcpToolOutput`
- `unregisterMcpTool`
- `replaceMcpToolsForServer`
- `replaceMcpToolOutputsForServer`

## 2. 目标结构

新增:

- `src/main/java/com/mindcli/capability/tool/namespace/McpToolNamespace.java`

保留:

- `ToolRegistry` 继续作为 Agent / CLI / Plan / Multi-Agent 的统一工具注册入口。
- `ToolRegistry` 继续暴露原有 MCP 注册方法，调用方不感知新类。

## 3. 模块职责

`McpToolNamespace` 负责:

- 持有 MCP 动态 invoker 表。
- 将 MCP tool descriptor materialize 为 `ToolRegistry.Tool`，写入 LLM 可见工具表。
- 按 tool name 注销 MCP 工具。
- 按 server name 原子替换该 server 下的 MCP 工具。
- 为 StepSearch fallback 提供已注册 MCP 工具的 input schema 查询。

`ToolRegistry` 负责:

- 对外保留兼容方法。
- 在执行工具时判断当前 tool 是否为 MCP 动态工具。
- 执行 browser guard、audit log、cancel check 等运行时策略。

## 4. 行为保持要求

- `mcp__{server}__{tool}` 命名保持不变。
- MCP tool definition 的 description 仍包含 server 和原 tool name。
- `registerMcpTool` 继续把字符串 invoker 包装为 `ToolOutput.text(...)`。
- `registerMcpToolOutput` 继续支持图片等结构化输出。
- `unregisterMcpTool(null / blank)` 继续 no-op。
- `replaceMcpToolOutputsForServer` 只替换指定 server 前缀下的工具，不影响其他 server。
- MCP 工具执行仍走 `ToolOutput`，不经过 `Map<String,String>` executor。
- StepSearch fallback 仍能读取 MCP input schema 来决定参数名。

## 5. 验证策略

新增 namespace 结构测试:

```java
Map<String, ToolRegistry.Tool> tools = new ConcurrentHashMap<>();
McpToolNamespace namespace = new McpToolNamespace(tools);
namespace.registerToolOutput(sampleDescriptor(), args -> ToolOutput.text("ok"));

assertTrue(namespace.contains("mcp__demo__echo"));
assertTrue(tools.containsKey("mcp__demo__echo"));
```

批次验证:

```bash
mvn test "-Dtest=ToolRegistryTest,McpToolRegistrationTest,McpClientTest" -DskipTests=false
```

交叉验证:

```bash
mvn test "-Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest,McpToolRegistrationTest" -DskipTests=false
```
