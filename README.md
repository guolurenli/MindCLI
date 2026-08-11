# MindCLI

Java 写的 Agent CLI，对标 [Claude Code](https://claude.ai/claude-code)。

## 快速开始

```bash
cp .env.example .env          # 填入 API Key
mvn clean package             # 编译
java -jar target/mindcli-1.0-SNAPSHOT.jar
```

## 主要功能

- **ReAct + Plan-and-Execute + Multi-Agent** 三种工作模式，覆盖从简单问答到复杂多步骤任务
- **Memory + RAG**：短期/长期记忆 + 候选审批 + 策略裁决 + 审计导出 + SQLite 向量存储 + 代码语义检索 + 代码关系图谱
- **多模型自由切换**：GLM / DeepSeek / Kimi / StepFun / Xfyun / FreeLLMAPI，运行时 `/model` 即时切换
- **MCP 协议**：支持 stdio + Streamable HTTP，60+ 工具自动注册，`~/.mindcli/mcp.json` 配置
- **Chrome DevTools**：浏览器操作（navigate / click / fill / snapshot），支持登录态复用
- **联网搜索**：`web_search`（智谱 / SerpAPI / SearXNG）+ `web_fetch`（URL → Markdown）
- **Skill 系统**：把专家手册沉淀为可复用 SKILL.md，内置 web-access skill，三层加载（jar / 用户级 / 项目级）
- **安全防护**：HITL 人工审批 + 路径围栏 + 命令黑名单 + 资源感知工具调度 + JSONL 审计日志
- **Agent Runtime 账本**：ReAct / Plan / Multi-Agent 运行事件写入 JSONL run ledger，支持状态投影、恢复检查和 Multi-Agent child run 审计
- **Side-Git 快照**：每轮自动 pre/post-turn 快照，`/restore <N>` 一键回滚
- **微信通道**：iLink 长轮询，扫码绑定后通过微信使用 Agent
- **其他**：LSP 诊断注入 / 图片复制粘贴输入 / TUI 双形态（inline 流式 + Lanterna 全屏）/ Prompt 分层架构 / 异步后台任务 + Runtime API

## 可用工具

| 工具 | 说明 |
|---|---|
| `read_file` / `write_file` / `list_dir` | 文件读写与目录列出 |
| `glob_files` | 按文件名模式实时查找文件 |
| `grep_code` | 按关键字/正则实时搜索代码（优先 ripgrep） |
| `execute_command` | 执行 Shell 命令（黑名单拦截破坏性命令，60s 超时） |
| `create_project` | 创建项目结构（java/python/node） |
| `search_code` | 语义检索代码库（RAG 辅助） |
| `web_search` / `web_fetch` | 联网搜索与网页抓取 |
| `revert_turn` | 恢复到历史快照 |
| `mcp__{server}__{tool}` | MCP server 动态注册的外部工具 |

ReAct / Plan / Multi-Agent 的工具调用都会先进入 Agent Runtime 的 `ToolDispatcher`：只读文件 / 搜索类工具可共享并行，写文件、目录创建、workspace 命令、浏览器会话、MCP server 和未知副作用工具会按资源锁串行化；文件与目录锁包含祖先目录关系，避免目录创建和子文件写入交叠；工具结果使用 `ToolOutcomeStatus` 区分策略拒绝、用户拒绝、超时、取消、部分成功和普通失败，并写入 JSONL run ledger 的 `TOOL_OUTCOME` 事件。

`/team` 支持项目级 Agent Profile 配置：在 `.mindcli/agents.json` 中声明 `profiles`，可为 planner / worker / reviewer 配置 `tools`、`deniedTools`、`commandAllowlist`、`maxConcurrency`、`permissionMode` 等字段。未配置时使用兼容默认 profile；配置后编排器会按 task 的 `requiredTools` / `preferredAgent` 选择最小权限 worker，并把 `profileName`、`permissionMode`、`selectedReason` 写入 child run 审计。

## 常用命令

### 工作模式

| 命令 | 说明 |
|---|---|
| `/plan <任务>` | 使用 Plan-and-Execute 模式执行复杂任务 |
| `/team <任务>` | 使用 Multi-Agent 协作模式 |
| `/cancel` | 取消当前运行中的任务 |

### 模型与配置

| 命令 | 说明 |
|---|---|
| `/model glm-5.1` | 切换到 GLM-5.1 |
| `/model deepseek` / `step` / `kimi` / `freellmapi` | 切换到对应 provider |
| `/config provider <name> --api-key <key> --model <m>` | 配置模型参数 |

### 记忆系统

| 命令 | 说明 |
|---|---|
| `/save <事实>` | 保存长期记忆（项目级）；`--global` 存为跨项目通用 |
| `/memory` | 查看记忆系统状态 |
| `/memory policy` | 查看自动提取、候选、存储、检索过滤和审计事件策略 |
| `/memory proposals` | 查看待确认候选记忆 |
| `/memory export --audit` | 导出记忆审计证据 Markdown |
| `/memory approve <id>` / `reject <id>` | 批准候选写入长期记忆，或拒绝候选 |
| `/memory list` / `search <关键词>` / `delete <id>` | 管理长期记忆 |
| `/clear` | 清空当前对话历史与短期记忆 |

长期记忆默认只通过 `/save` 或用户明确要求保存。自动长期记忆提取默认关闭；如显式设置 `-Dmindcli.memory.autoExtract.enabled=true` 或 `MINDCLI_MEMORY_AUTO_EXTRACT=true`，系统只会生成待确认记忆候选，不会直接写入长期记忆；候选需经 `/memory approve <id>` 批准后才会落入长期记忆。
长期记忆注入 prompt 前会跳过 `status=revoked/deleted/expired` 或 `expiresAt` 已过期的条目。删除长期记忆会保留 tombstone 文件并写入 `audit.jsonl`，`/memory export --audit` 会把写入、拒绝、审批、删除、注入和导出事件整理到 `~/.mindcli/exports/memory-audit-*.md`。

### MCP 管理

| 命令 | 说明 |
|---|---|
| `/mcp` | 查看所有 MCP server 状态 |
| `/mcp restart <name>` | 重启指定 server |
| `/mcp logs <name>` | 查看 server 最近日志 |
| `/mcp disable <name>` / `enable <name>` | 运行时禁用/启用 server |

### 安全与快照

| 命令 | 说明 |
|---|---|
| `/hitl on` / `off` | 开关人工审批 |
| `/policy` | 查看安全策略（路径围栏 / 命令黑名单 / 审计） |
| `/audit [N]` | 查看最近 N 条审计记录 |
| `/run inspect <runId>` | 检查指定 Agent Runtime run 的状态、checkpoint 与恢复提示 |
| `/snapshot` | 查看 Side-Git 快照列表 |
| `/restore <N>` | 回滚到最近第 N 个 pre-turn 快照 |

### Skill 与浏览器

| 命令 | 说明 |
|---|---|
| `/skill list` / `show <name>` / `on <name>` / `off <name>` | 管理 Skill |
| `/browser status` / `connect` / `disconnect` / `tabs` | 管理浏览器连接 |

### 其他

| 命令 | 说明 |
|---|---|
| `/wechat setup` / `status` / `stop` | 微信 iLink 通道管理 |
| `/init` | 生成项目级 `PAI.md` |
| `/export` | 导出当前会话为 Markdown |
| `/task add <内容>` / `cancel <id>` / `log <id>` | 后台任务管理 |
| `/exit` | 退出程序 |

## 配置与环境变量

```bash
# 模型 API Key
export GLM_API_KEY=your_api_key_here
export DEEPSEEK_API_KEY=your_api_key_here
export STEP_API_KEY=your_step_api_key_here
export KIMI_API_KEY=your_kimi_api_key_here
export FREELLMAPI_API_KEY=your_key_here
export FREELLMAPI_BASE_URL=http://localhost:5173/v1

# 日志
export MINDCLI_LOG_LEVEL=DEBUG
export MINDCLI_LOG_DIR=~/.mindcli/logs
export MINDCLI_RUNS_DIR=~/.mindcli/runs  # Agent Runtime JSONL 账本目录

# 渲染
export MINDCLI_RENDERER=inline    # inline（默认）| lanterna | plain
export MINDCLI_NO_STATUSBAR=true  # 禁用 JLine 底部状态栏
export NO_COLOR=1                # 禁用 ANSI 颜色
```

MCP server 配置详见 `~/.mindcli/mcp.json` 或项目级 `.mindcli/mcp.json`。

## 技术栈

- Java 17 · Maven · JLine 4
- OkHttp · Jackson · SQLite
- JavaParser（AST 分析）· Ollama（本地 Embedding）· Lanterna（TUI）

## License

MIT
