# JSON 基础设施与 MCP 管理器瘦身设计

## 背景与目标

MindCLI 已经使用 Jackson、官方 MCP Java SDK、JLine、JGit、JavaParser、Jsoup 等成熟库。本轮不再引入新的大型框架，也不替换已经正确工作的官方协议实现，而是做两项低风险内部整理：

1. 统一业务代码中的 Jackson `ObjectMapper` 创建，减少重复配置和实例化。
2. 保留 `McpServerManager` 的现有公开接口，把启动协调、官方 transport 创建、状态/资源适配等职责移到包内实现模块，降低单文件认知负担。

成功标准是：现有 CLI、MCP 工具注册、资源 mention、浏览器 MCP、启动超时、重启/启停、审计行为不变；生产代码和测试继续通过现有回归命令。

## 非目标

- 不更换 MCP Java SDK，不自行实现 JSON-RPC、stdio 或 HTTP 协议。
- 不改变 `McpServerManager`、`McpClient`、`ToolRegistry` 的公开方法和返回格式。
- 不改变 MCP 配置文件格式、配置优先级、工具命名空间或资源缓存语义。
- 不在本轮拆分 `Agent`、`AgentOrchestrator`、`Main` 或重新命名顶层目录。
- 不引入 Spring、Picocli、LSP4J、dotenv 等新框架/依赖。

## 设计一：统一 JSON 基础设施

### 模块与接口

新增 `com.mindcli.platform.serialization.JsonSupport`，提供一个小而稳定的接口：

- `JsonSupport.mapper()`：返回进程内共享、线程安全的默认 `ObjectMapper`。
- `JsonSupport.prettyMapper()`：返回启用缩进输出的 mapper，供配置/导出等明确需要人读的场景使用。
- 可选的 `JsonSupport.newMapper()`：仅在测试或确实需要隔离配置时使用，不作为生产默认入口。

`ObjectMapper` 在完成配置后可安全并发读写。默认 mapper 不在调用方修改；需要自定义行为的模块继续通过构造器注入 mapper（如 Plan parser/repairer），但默认值改为 `JsonSupport.mapper()`。

### 迁移范围

优先迁移生产代码中直接 `new ObjectMapper()` 的位置，包括 Agent、MCP、Memory、Web、Runtime、HITL、Browser、Skill、渲染和工具模块。测试代码可暂时保留独立 mapper，避免测试之间共享可变状态。

MCP 的 `JacksonMcpJsonMapper` 仍使用官方 SDK 适配器，但底层 mapper 改为 `JsonSupport.mapper()`；不改变 schema 序列化结果。

### 兼容与错误处理

`JsonSupport` 不吞异常、不改变 Jackson 的异常类型。现有调用点继续自行决定是抛出、记录还是返回 fail-soft 结果。统一只负责实例和基础配置，避免把业务容错塞进基础模块。

### 验证

- 增加 `JsonSupportTest`，验证默认 mapper、pretty mapper 的基本行为和并发读取安全性。
- 运行现有 JSON/MCP/Runtime/Memory/渲染相关测试。
- 对比迁移前后关键 JSON 输出（MCP schema、run ledger、配置导出、工具定义）。

## 设计二：MCP Server Manager 内部拆分

### 对外 facade

`McpServerManager` 继续作为唯一对外 facade，保留现有构造器和公开方法：

- 配置加载：`loadConfiguredServers`
- 生命周期：`startAll`、`restart`、`restartWithArgs`、`enable`、`disable`、`close`
- 查询/展示：`server`、`servers`、`logs`、`formatStatus`、`startupSummary`、`startupNotice`
- 工具/资源：工具注册相关入口、`resources`、`prompts`、`resourceCandidates`、`readResourceForMention`

Facade 只编排依赖，不复制协议逻辑；现有调用方无需修改。

### 包内模块

在 `com.mindcli.capability.mcp.lifecycle` 下增加三个包内模块：

1. `McpStartupCoordinator`
   - 负责并行启动、最大等待时间、超时后的后台继续启动、进度摘要。
   - 输入/输出使用现有 `McpServer` 和状态，不拥有 server 配置。

2. `McpTransportFactory`
   - 负责根据 `McpServerConfig` 创建官方 stdio 或 Streamable HTTP transport/client。
   - 继续使用 `MindCliStdioClientTransport` 处理工作目录和 stderr 环形缓冲。
   - 变量展开、transport 校验仍由 `McpConfigLoader.prepare` 完成。

3. `McpServerContentAdapter`
   - 负责将 MCP tool/resource/prompt 能力转换为 MindCLI 的 descriptor、`ToolExecution`、resource mention 结果。
   - 负责刷新工具/资源和重复工具校验，但不负责启动线程或 server 状态展示。

`McpServerManager` 保留资源缓存、server 集合和 facade 级策略协调；如果拆分后某模块只有一两个转发方法，则不新增该模块，遵守删除测试和避免过度抽象原则。

### 数据流

```text
McpServerManager facade
  ├─ McpConfigLoader.load/prepare
  ├─ McpStartupCoordinator.start(server, callback)
  │    └─ McpTransportFactory.create(server)
  └─ McpServerContentAdapter
       ├─ list tools -> ToolRegistry
       ├─ list resources -> McpResourceCache
       └─ call/read -> ToolExecution / mention content
```

启动失败仍只影响对应 server，并写入该 server 的 ERROR 状态；单个 server 的失败不得阻塞其他 server。transport 初始化异常继续转为现有错误消息，关闭时按当前顺序释放 client/transport/executor。

### 并发与生命周期不变量

- `McpServerManager.servers` 仍使用现有并发集合。
- 启动协调器使用 daemon executor，等待超时后不阻塞 CLI 首屏。
- 同一 server 的 restart/enable/disable 仍由 manager 串行化。
- 工具注册和注销必须成对执行，不能留下旧 server 的动态工具。
- `McpResourceCache` 仍由 manager 持有，避免改变缓存生命周期。

### 验证

- 现有 `McpServerManagerTest`、`McpClientTest`、MCP config/resource/transport 测试全部通过。
- 增加针对启动超时、单 server 失败隔离、重启后工具替换的聚焦测试（优先复用现有测试夹具）。
- 运行 `mvn test -Pquick`、`mvn -DskipTests compile`、`git diff --check`。

## 分阶段实施

1. 先新增 `JsonSupport`，迁移默认 mapper，保持每次提交可编译可测试。
2. 再提取 `McpTransportFactory` 和 `McpStartupCoordinator`，逐步缩短 manager 方法；每步运行 MCP 定向测试。
3. 最后评估 `McpServerContentAdapter` 是否真的减少 manager 复杂度；若只产生薄转发，则不提取。

## 风险与回滚

主要风险是共享 mapper 被某处意外修改、MCP 启动异常的异常边界变化、工具注销顺序变化。通过 mapper 不可变约定、构造器注入保留、定向回归和 facade 保持不变来控制。任何阶段出现行为差异，都可以只回滚对应内部模块，调用方和配置格式无需回滚。

