# MindCLI

MindCLI 是一个 Java 17 + Maven 实现的 Agent CLI，目标是做面向商业使用的本地开发助手，对标 Claude Code。当前代码已经形成三条主执行路径：默认 ReAct、`/plan` 的 Plan-and-Execute，以及 `/team` 的 Multi-Agent 协作；三条路径共享工具注册、记忆、RAG、Side-Git 快照和 Agent Runtime 账本。

`mvn clean package` 默认跳过测试，优先产出可手工验收的 `target/mindcli-1.0-SNAPSHOT.jar`；回归测试请显式运行文末的测试命令。

## 快速开始

```bash
cp .env.example .env          # 至少填写一个模型 API Key
mvn clean package             # 打包，默认 skipTests=true
java -jar target/mindcli-1.0-SNAPSHOT.jar
```

可选入口：

```bash
java -jar target/mindcli-1.0-SNAPSHOT.jar wechat setup
java -jar target/mindcli-1.0-SNAPSHOT.jar wechat start

MINDCLI_RUNTIME_API_KEY=local-dev-key \
java -jar target/mindcli-1.0-SNAPSHOT.jar serve --http --port 8080
```

运行前至少配置一个可用 Key：`GLM_API_KEY`、`DEEPSEEK_API_KEY`、`STEP_API_KEY`、`KIMI_API_KEY`、`FREELLMAPI_API_KEY` 或 `XFYUN_MAAS_API_KEY`。

## 架构概览

当前 CLI 生产入口中，默认 ReAct、`/plan` 和 `/team` 都会经 `AgentModeRouter` 创建 `AgentRunContext` 并进入 `AgentRuntime`；三种模式通过 `ReActModeAdapter` / `PlanModeAdapter` / `TeamModeAdapter` 复用统一生命周期、RunStore 和 runtime snapshot 关联，不再额外套旧的 turn snapshot。

```mermaid
flowchart LR
    CLI["JLine CLI / Inline Renderer"] --> Parser["CliCommandParser"]
    Parser --> React["ReAct Agent"]
    Parser --> Plan["PlanExecuteAgent"]
    Parser --> Team["AgentOrchestrator"]
    React --> Runtime["Agent Runtime"]
    Plan --> Runtime
    Team --> Runtime
    Runtime --> Dispatcher["ToolDispatcher"]
    Dispatcher --> Tools["ToolRegistry + MCP Tools"]
    Tools --> Memory["MemoryManager"]
    Tools --> RAG["CodeIndex / CodeRetriever"]
    Tools --> Snapshot["Side-Git SnapshotService"]
    Runtime --> RunStore["JSONL RunStore"]
```

```mermaid
sequenceDiagram
    participant User as User
    participant CLI as Main / Renderer
    participant Agent as Agent Mode
    participant Runtime as AgentRuntime
    participant Dispatch as ToolDispatcher
    participant Tool as ToolRegistry / MCP
    participant Store as RunStore

    User->>CLI: 输入任务或 slash 命令
    CLI->>Agent: 选择 ReAct / Plan / Team
    Agent->>Runtime: 创建 run context
    Runtime->>Store: 记录 RUN_STARTED
    Agent->>Dispatch: 发起 tool calls
    Dispatch->>Dispatch: 资源分类与锁调度
    Dispatch->>Tool: 执行内置工具或 MCP 工具
    Tool-->>Dispatch: ToolOutput
    Dispatch->>Store: 记录 TOOL_OUTCOME
    Runtime->>Store: 记录完成状态与 snapshot 关联
    Agent-->>CLI: 流式输出结果
```

源码包结构：

```text
src/main/java/com/mindcli/
├── agent/       ReAct / Plan / Multi-Agent 编排，profile/ 是 Agent Profile 与 worker lease
├── app/         cli / tui / wechat 用户入口与命令 handler
├── capability/  browser / image / lsp / mcp / memory / rag / skill / tool / web
├── platform/    config / hitl / llm / prompt / render / security / snapshot / text
└── runtime/     run ledger、ToolDispatcher、Runtime API、DurableTaskManager
```

## 核心能力

| 能力 | 当前实现 |
|---|---|
| 执行模式 | 默认 ReAct；`/plan` 进入计划审阅与执行；`/team` 使用 planner / worker / reviewer 协作 |
| Runtime 账本 | `JsonlRunStore` 按 run 写 JSONL 事件，投影 `run.meta.json` / `run.state.json`，支持 child run 摘要 |
| 工具调度 | `ToolDispatcher` 统一进入 Hook、资源分类、资源锁与结构化 `ToolOutcome` |
| 代码理解 | `glob_files` / `grep_code` / `read_file` 实时探索，`/index` + `/search` + `/graph` 提供 RAG 语义辅助 |
| 记忆治理 | `/save` 手动长期记忆；自动提取只生成候选；`/memory approve/reject/export --audit` 管理审计链路 |
| MCP | 合并用户级 `~/.mindcli/mcp.json` 和项目级 `.mindcli/mcp.json`，支持 stdio 与 Streamable HTTP |
| 浏览器 | 默认 `chrome-devtools` MCP isolated 模式，`/browser connect` 可复用本机 Chrome 登录态 |
| Web | `web_search` 支持 zhipu / serpapi / searxng，`web_fetch` 通过 HTTP + Jsoup 提取 Markdown |
| 安全 | HITL、PathGuard、CommandGuard、BrowserGuard、危险工具 JSONL 审计 |
| 交互体验 | JLine 4 inline renderer、底部状态栏、slash 补全、输入高亮、`@path` 与 MCP resource 展开 |
| 其他入口 | 微信 iLink 通道、后台任务 `/task`、本地 Runtime HTTP API |

## 内置工具

| 工具 | 说明 |
|---|---|
| `read_file` | 读取项目根内文件，支持 `offset` / `limit` 分段读取 |
| `write_file` | 写入项目根内文件，单文件 5MB 上限，写后触发 diff / LSP 观察链路 |
| `list_dir` | 列出项目根内目录 |
| `glob_files` | 按 glob 模式查找文件，默认忽略 `.git`、`target`、`node_modules` 等目录 |
| `grep_code` | 按关键字或正则搜索代码，优先 ripgrep，失败时回退 Java 扫描 |
| `execute_command` | 在项目目录执行短时 shell 命令，默认 60 秒超时 |
| `create_project` | 创建 `java` / `python` / `node` 项目骨架 |
| `search_code` | RAG 语义辅助检索代码块，精确符号仍优先使用 `grep_code` |
| `web_search` / `web_fetch` | 联网搜索与 URL 正文抓取 |
| `browser_connect` / `browser_disconnect` / `browser_status` | 管理 Chrome DevTools MCP 登录态复用 |
| `save_memory` | 用户明确要求记住时写入长期记忆 |
| `load_skill` | 加载匹配任务的 `SKILL.md` 全文 |
| `revert_turn` | 恢复到 Side-Git 最近第 N 个 pre-turn 快照 |
| `mcp__{server}__{tool}` | MCP server 动态注册工具 |

## 常用命令

### 工作模式与会话

| 命令 | 说明 |
|---|---|
| `/plan <任务>` | 直接以 Plan-and-Execute 执行任务；无参数时让下一条任务使用 Plan 模式 |
| `/team <任务>` | 直接以 Multi-Agent 执行任务；无参数时让下一条任务使用 Team 模式 |
| `/cancel` | 取消运行中的任务；空闲时提示当前无任务 |
| `/clear` | 清空当前对话历史与短期记忆，长期记忆保留 |
| `/compact` | 手动压缩当前 ReAct conversation history |
| `/context` / `/ctx` | 查看上下文、记忆与 token 状态 |
| `/init` / `/init --force` | 生成或强制重写项目级 `PAI.md` |
| `/export` | 导出当前 ReAct 会话为 Markdown |
| `/history clear` | 清空本机输入历史 |
| `/exit` / `/quit` | 退出程序 |

### 模型与配置

| 命令 | 说明 |
|---|---|
| `/model` | 查看当前模型与可切换 provider |
| `/model glm-5.1` | 切换到 GLM-5.1 |
| `/model glm-5v-turbo` | 切换到 GLM 多模态模型，用于图片输入 |
| `/model deepseek` / `step` / `kimi` / `freellmapi` / `xfyun` | 切换到配置中的 provider |
| `/config` | 打开只读配置 palette，并提示对应 CLI 命令 |
| `/config provider <name> --api-key <key> --model <m> --base-url <url> --default` | 写入 `~/.mindcli/config.json` 的 provider 配置 |
| `/config provider xfyun --lora-id <resourceId>` | 为讯飞星辰 MaaS 微调模型配置 `lora_id` header |

### 代码检索

| 命令 | 说明 |
|---|---|
| `/index` | 索引当前代码库 |
| `/index <路径>` | 索引指定路径，并同步 ToolRegistry / MemoryManager 的项目路径 |
| `/search <查询>` | 对已索引代码执行 hybrid search |
| `/graph <类名>` | 查看已索引代码关系图谱 |

### 记忆系统

| 命令 | 说明 |
|---|---|
| `/save <事实>` | 保存项目级长期记忆 |
| `/save --global <事实>` | 保存跨项目长期偏好 |
| `/memory` / `/mem` | 查看记忆系统状态 |
| `/memory policy` | 查看自动提取、候选、过滤与审计策略 |
| `/memory proposals` | 查看待确认候选记忆 |
| `/memory approve <id>` / `/memory reject <id>` | 批准或拒绝候选 |
| `/memory list` / `/memory search <关键词>` | 查看或搜索长期记忆 |
| `/memory delete <id>` / `/memory clear` | 删除单条或清空长期记忆 |
| `/memory export --audit` | 导出记忆审计证据到 `~/.mindcli/exports/` |

长期记忆默认只通过 `/save` 或用户明确要求保存。即使开启自动提取，系统也只会生成 `MemoryProposal`，必须经过 `/memory approve <id>` 才会写入长期记忆；删除采用 tombstone 语义，审计源是长期记忆目录下的 `audit.jsonl`。

### MCP、浏览器与 Skill

| 命令 | 说明 |
|---|---|
| `/mcp` | 查看所有 MCP server 状态 |
| `/mcp restart <name>` | 重启 server |
| `/mcp logs <name>` | 查看 server 最近 stderr 日志 |
| `/mcp disable <name>` / `/mcp enable <name>` | 运行时禁用或启用 server |
| `/mcp resources <name>` | 查看 server 暴露的 resources |
| `/mcp prompts <name>` | 查看 server 暴露的 prompts |
| `/browser status` | 查看浏览器 MCP 模式和 CDP 探活 |
| `/browser connect` | 使用 `chrome-devtools-mcp --autoConnect` 复用已授权 Chrome |
| `/browser connect <port>` | 旧式 CDP 端口连接，如 `9222` |
| `/browser tabs` | shared 模式下列出真实 Chrome tabs |
| `/browser disconnect` | 切回 isolated 浏览器模式 |
| `/skill list` / `/skill show <name>` | 查看 Skill 列表或完整 `SKILL.md` |
| `/skill on <name>` / `/skill off <name>` / `/skill reload` | 切换启用状态或重新扫描 |

MCP 配置读取顺序为用户级 `~/.mindcli/mcp.json` 后叠加项目级 `.mindcli/mcp.json`，项目同名 server 覆盖用户配置。`${PROJECT_DIR}`、`${HOME}` 与 `${VAR}` 会在单个 server 启动前展开；某个 server 配置错误不会阻塞其他 server。检测到 `STEP_API_KEY` 且未显式配置同名 server 时，会自动加入 `step_search` 远程 MCP。

示例：

```json
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
    },
    "remote-docs": {
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer ${REMOTE_TOKEN}"
      }
    }
  }
}
```

CLI 启动默认最多等待 MCP server 初始化 8 秒；超时后会先进入输入界面，未完成的 server 在后台继续启动。可用 `MINDCLI_MCP_STARTUP_WAIT_SECONDS` 或 `-Dmindcli.mcp.startup.wait.seconds=30` 调整。

### 安全、快照与后台任务

| 命令 | 说明 |
|---|---|
| `/hitl` / `/hitl on` / `/hitl off` | 查看或切换危险操作人工审批 |
| `/policy` | 查看 PathGuard、CommandGuard 与审计状态 |
| `/audit [N]` | 查看今日最近 N 条危险工具审计 |
| `/snapshot` / `/snapshot status` / `/snapshot clean` | 查看、检查或清理 Side-Git 快照 |
| `/restore <N>` | 回滚到最近第 N 个 pre-turn 快照 |
| `/run inspect <runId>` | 检查 run ledger、snapshot checkpoint 与恢复提示 |
| `/task` / `/task list [N]` | 查看后台任务 |
| `/task add <任务>` | 提交后台任务 |
| `/task cancel <id>` / `/task log <id>` | 取消任务或查看任务日志 |

### 微信通道

| 命令 | 说明 |
|---|---|
| `/wechat` | 已绑定时启动通道；未绑定时进入扫码绑定 |
| `/wechat setup` | 重新扫码绑定并启动 |
| `/wechat status` | 查看当前进程内微信通道状态 |
| `/wechat stop` | 停止当前进程内微信通道 |
| `/wechat restart` | 重启当前进程内微信通道 |

微信 iLink 通道默认使用非交互审批策略：只读工具默认允许，命令和 MCP 工具必须命中 allowlist，浏览器会话切换与 `revert_turn` 默认拒绝，文件写入仍受工作区 PathGuard 限制。

## 配置与环境变量

模型配置可写入 `.env`、系统环境变量或 `~/.mindcli/config.json`。`/config provider ...` 会写 `~/.mindcli/config.json`；`.env` 适合本地开发快速启动。

```bash
# 模型 API Key
GLM_API_KEY=your_key
DEEPSEEK_API_KEY=your_key
STEP_API_KEY=your_key
KIMI_API_KEY=your_key
MOONSHOT_API_KEY=your_key
FREELLMAPI_API_KEY=your_key
XFYUN_MAAS_API_KEY=your_key

# 模型与网关
GLM_MODEL=glm-5.1
DEEPSEEK_MODEL=deepseek-v4-flash
STEP_MODEL=step-3.5-flash
KIMI_MODEL=kimi-k2.6
FREELLMAPI_BASE_URL=http://localhost:5173/v1
XFYUN_MAAS_BASE_URL=https://maas-api.cn-huabei-1.xf-yun.com/v2
XFYUN_MAAS_MODEL=Qwen3.6-35B-A3B
XFYUN_MAAS_LORA_ID=your_resource_id

# Embedding / RAG
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434

# Web 搜索
SEARCH_PROVIDER=zhipu       # zhipu | serpapi | searxng
ZHIPU_SEARCH_ENGINE=search_std
SERPAPI_KEY=your_serpapi_key
SEARXNG_URL=http://localhost:8888

# 渲染与日志
MINDCLI_RENDERER=inline     # inline | lanterna | plain
MINDCLI_NO_STATUSBAR=true
NO_COLOR=1
MINDCLI_LOG_LEVEL=INFO
MINDCLI_LOG_DIR=~/.mindcli/logs

# Runtime
MINDCLI_RUNS_DIR=~/.mindcli/runs
MINDCLI_TASK_DIR=~/.mindcli/tasks
MINDCLI_RUNTIME_API_KEY=your_local_api_key
```

## Runtime HTTP API

Runtime API 只监听 `127.0.0.1`，必须设置 `MINDCLI_RUNTIME_API_KEY` 或 `-Dmindcli.runtime.api.key`。

```bash
MINDCLI_RUNTIME_API_KEY=local-dev-key \
java -jar target/mindcli-1.0-SNAPSHOT.jar serve --http --port 8080
```

接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/threads` | 创建 thread，返回 `{id, object}` |
| `POST` | `/v1/threads/{threadId}/turns` | 提交 `{ "input": "..." }`，异步运行一轮 |
| `GET` | `/v1/threads/{threadId}/events?after=<id>` | 以 SSE 格式读取事件 |

认证头支持：

```text
Authorization: Bearer <MINDCLI_RUNTIME_API_KEY>
X-MindCLI-API-Key: <MINDCLI_RUNTIME_API_KEY>
```

## 开发与验证

```bash
mvn test -Pquick
mvn test -Pphase16-smoke
mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest
mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest
mvn test -Dtest=ExecutionPlanTest
mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest
mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest
mvn test -DskipTests=false
```

常用文档入口：

| 文档 | 说明 |
|---|---|
| `AGENTS.md` | Agent / 新线程首读入口，包含维护硬规则 |
| `PAI.md` | 项目级记忆，会注入 system prompt |
| `docs/mindcli-current-architecture-report.md` | 当前架构分析报告 |
| `docs/mindcli-agent-runtime-implementation-plan.md` | Agent Runtime 演进实现计划 |
| `ROADMAP.md` | 后续规划，不能等同于已交付功能 |

## 技术栈

- Java 17、Maven、JLine 4、Lanterna
- OkHttp、Jackson、Logback
- SQLite、JavaParser、JGit、Jsoup
- JUnit 5、Mockito、OkHttp MockWebServer

## License

MIT
