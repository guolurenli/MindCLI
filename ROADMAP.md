# MindCLI 能力地图与规划

> 本文档描述 MindCLI 的**当前能力**与**未来规划**。
> 判定标准是「代码实际行为」：只有已经在代码里落地、可运行的能力才会列入「已交付」；
> 「规划中」一律不代表已实现。不要把本文件当成已交付清单的补充承诺。

---

## 一、已交付能力

### 1. 核心执行引擎

三条主执行路径,共享 `ToolRegistry` / `MemoryManager` / `SnapshotService`：

- **ReAct**（默认模式）—— 思考-行动-观察循环，LLM/tool 循环委托 `runtime/run/AgentLoopExecutor.java`，`Agent.java` 负责 prompt / memory / renderer。
- **Plan-and-Execute**（`/plan`）—— 先规划后执行，任务分解 + DAG 依赖管理 + 失败重规划，`PlanExecuteAgent.java` + `agent/plan/*`。
- **Multi-Agent**（`/team`）—— 编排器内建规划，`EXPLORER` / `WORKER` 双 Profile 分工，`AgentOrchestrator.java` + `SubAgent.java` + `agent/profile/*`。
- **模式路由** —— `AgentModeRouter` 统一把三种模式接入 `AgentRuntime`。

### 2. 记忆与上下文

- **短期记忆** —— 对话历史管理；**长期记忆** —— `/save` 持久化 + `/memory` 治理。
- **项目级记忆** —— 启动时自动注入 `MIND.md`（`ProjectMemoryLoader`），支持 `@relative/path.md` 导入。
- **记忆治理** —— 候选审批（`/memory proposals` / `approve` / `reject`）、审计（`audit.jsonl`）、tombstone 删除语义。
- **上下文压缩** —— 区分 `shortTermMemory` 压缩与 `conversationHistory` 压缩，阈值按窗口动态预留。

### 3. 代码理解与检索

- **精确定位** —— `glob_files` / `grep_code`（优先 ripgrep）/ `read_file`，Claude Code 式实时探索。
- **实时代码探索** —— `glob_files` / `grep_code` / `read_file` 逐步定位代码；语义检索不再由 MindCLI 内置维护，按需通过官方 MCP 接入外部能力。
- **LSP 诊断** —— `LspManager` 惰性启动语言服务，编辑后注入编译诊断（`capability/lsp/*`）。

### 4. 内置工具集

10 个内置工具：`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `web_search` / `web_fetch` / `revert_turn`。

- `ToolRegistry` 作为工具 facade，`ToolRegistrar` 维护内置工具 schema。
- 并行工具调用统一走 `ToolDispatcher`，产出结构化 `ToolOutcome`。

### 5. 多模型接入

- 支持 **GLM / DeepSeek / StepFun / Kimi / 讯飞星辰**，`LlmClient` 接口 + `AbstractOpenAiCompatibleClient` 基类。
- 运行时切换 `/model`；配置持久化 `~/.mindcli/config.json`。
- 长上下文（200k–1M）适配 + prompt caching。

### 6. 联网与浏览器

- **Web 工具** —— `web_search` / `web_fetch`（StepSearch MCP 就绪时优先转调）。
- **浏览器操控** —— 接入 Chrome DevTools MCP（28 个工具）。
- **登录态访问** —— CDP 会话复用，`/browser connect` 复用调试 Chrome。

### 7. MCP 生态

- 双传输：stdio 子进程 + Streamable HTTP 远程 server。
- 动态工具按 `mcp__{server}__{tool}` 注册；resources 双轨 + prompts 查看。
- 配置合并 `~/.mindcli/mcp.json` + `.mindcli/mcp.json`，默认开启。

### 8. Skill 系统

- 三层目录扫描（jar 内置 / 用户级 / 项目级）+ `load_skill(name)` 懒加载。
- 首个落地 `web-access` Skill（决策手册 + 站点经验文件）。

### 9. 安全与治理

- **HITL** 审批流（`HitlToolRegistry` 透明拦截）。
- **路径/命令防护** —— `PathGuard` 限定项目根 + `CommandGuard` 黑名单快速拒绝。
- **审计** —— 危险工具调用写 `~/.mindcli/audit/` JSONL。
- **writeScope** —— Multi-Agent 写入型步骤的硬约束范围。
- 微信 iLink 通道无审批面板，走非交互默认拒绝策略。

### 10. 交互界面

- **inline 流式渲染**（默认，Claude Code 风格）—— 主屏直出 + 底部状态栏。
- **微信 iLink 通道** —— 文本 MVP（`/wechat`）。

### 11. 快照与恢复

- **Side-Git 快照** —— turn 前后自动快照，`/restore` / `revert_turn` 一键回滚，不污染用户 `.git`。
- **Agent Runtime 账本** —— JSONL ledger 是 source of truth，`/run inspect <runId>` 检查状态与 checkpoint。

### 12. 扩展接口

- **后台任务** —— `DurableTaskManager`（SQLite 队列）+ `/task` 闭环，进程重启自动重入队。
- **Runtime API** —— `RuntimeApiServer`（HTTP/SSE），仅监听 localhost，兼容 OpenAI Assistants 风格端点。
- **图片输入** —— `@image:` 引用或粘贴，`LlmClient.Message` 支持 `ContentPart`。

---

## 二、规划中（未交付）

### 当前技术债优先级

以下是当前主线优先级。P0 配置读取统一已经完成，保留在这里作为已关闭项，避免后续盘点时重复提出。

| 优先级 | 方向 | 原因与范围 |
|---|---|---|
| 已完成 P0 | 配置读取统一 | `ConfigValueResolver` 已统一 `System property → OS environment → 项目 .env → 用户 ~/.env → 默认值`；原先分散在 `SnapshotConfig`、`RuntimeApiServer`、`DurableTaskManager`、`LspManager`、`AuditLog`、`CliInputSupport`、`McpClient` 等模块的业务配置读取已迁移。 |
| 已完成 P1 | 继续压薄 `Main.java` | `CliCommandRouter` 已统一承接低风险 slash 命令、session 清理/压缩、配置、HITL、审计、浏览器、MCP、Skill、Wechat 与 Agent 展示；`Main` 保留启动、模式切换和 Agent 直连执行，当前约 1467 行。 |
| P1（进行中） | 拆薄 `ToolRegistry` | 文件读写/目录枚举已下沉到 `FileToolExecutor`，`glob_files` / `grep_code` 已下沉到 `CodeSearchToolExecutor`，Web 搜索/抓取已下沉到 `WebToolExecutor`，Memory 工具已下沉到 `MemoryToolExecutor`，Shell 命令执行已下沉到 `ShellCommandExecutor`；`ToolRegistry` 保留兼容入口与注册 facade。下一步只需评估是否继续拆 `create_project` / Snapshot，避免为少量逻辑过度抽象。 |
| P1 | 统一三套 Agent 循环 | `Agent`、`PlanExecuteAgent`、`SubAgent` 仍各自保留部分 LLM/tool loop。长期统一到一个执行 seam，减少同一问题需要改三处的情况；风险较高，不与配置清理同时进行。 |
| P1 | `/run resume` 与启动期自动恢复 | 当前只有 `/run inspect`，能查看但不能继续执行。恢复前需要生成计划并对文件写入、命令执行和未完成 child run 请求确认。 |
| P2 | 清理兼容 API | `MemoryManager`、`MemoryExtractor` 仍有 deprecated 入口，`ToolRegistry` 仍有 `legacyWritten` 和旧 memory saver 适配。先保留一个版本周期，确认外部调用者不存在后再删除。 |
| P2 | 依赖审计 | 当前没有明显可直接删除的核心依赖；应先运行 `mvn dependency:analyze` 验证，再评估是否删除，不能仅凭文件名判断。 |
| P2 | Runtime 按 run 粒度加锁 | 当前 `JsonlRunStore` 仍使用实例级全局同步，正确性已保证但多 run 并发时会互相等待。属于性能优化，需单独验证锁顺序和派生状态一致性。 |
| P2 | MCP OAuth / sampling / server 自动重启 | 路线图中的 MCP 增强能力，当前尚未实现，不应与核心重构混做。 |
| P2 | 容器 / VM 沙箱 | 当前安全模型仍是 HITL + PathGuard + CommandGuard + 审计，不是真正的进程隔离；属于商业化安全升级。 |
| P3 | 视频 / 音频输入 | 当前多模态只支持图片，视频和音频作为独立迭代。 |

以下在路线图但**尚未在代码中落地**，不要把「将来要做」当成「现在已有」：

- **容器 / VM 沙箱** —— 真正的隔离执行环境（Docker / microVM）。当前安全模型是 HITL + 路径校验 + 命令拒绝 + 审计，而非隔离；沙箱方案参考「Pro 升级版本」章节。
- **MCP OAuth 2.0 + sampling + server 自动重启** —— OAuth（Authorization Code + PKCE）、`sampling/createMessage`、server 崩溃自动拉起均未实现。
- **`/run resume` + 启动期自动恢复** —— 当前只有 `/run inspect` 的检查能力，后续补 resume 与启动期自动发现可恢复 run。
- **视频 / 音频输入** —— 多模态暂只支持图片，视频音频留作后续独立迭代。

---

## 三、参考项目

- **Claude Code** —— 人机协同、终端界面
- **OpenClaw** —— 多 Agent、MCP 集成
- **PaiAgent** —— 工作流编排、可视化
- **LangGraph** —— 状态管理、循环控制
- **Spring AI** —— 多模型适配、工具回调

---

## 四、Pro 升级版本（独立分支）

主线能力稳定后，开启独立分支做框架重构，作为「手写版 → 框架版」的对照实现。不并入主分支，主线手写版保持稳定基线。
