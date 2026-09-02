# AGENTS.md

仓库给 Agent / 新线程使用的首读入口。详细行为描述见 `docs/agents-reference.md`。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `MIND.md` > 4. `README.md` > 5. `ROADMAP.md` > 6. `CLAUDE.md`

`ROADMAP.md` 代表演进方向，不代表已交付。

## 项目快照

- 项目名：`MindCLI`
- 定位：面向商业使用的 Java Agent CLI 产品，对标 Claude Code
- 已交付 23 期 + Agent Runtime 企业级改造 Phase 6 + 记忆治理 Phase 8（ReAct → Plan+DAG → Memory → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools MCP → Skill → inline 渲染 → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入 → 微信 iLink 通道文本 MVP；Runtime Spine / Dispatcher / Profile 化 Multi-Agent、记忆候选审批、策略裁决与审计导出已落地）
- `MIND.md` 是 MindCLI 的项目级记忆文件：启动时自动注入 system prompt，适合团队共享的长期稳定规则；个人/会变化的经验继续用 `/save` 长期记忆。
- 下一步：OAuth / sampling / recovery 作为后续 MCP 增强
- Banner 版本：`v16.1.0`，Maven 产物：`mindcli-1.0-SNAPSHOT.jar`（两者不一致是正常状态）

## 运行前提

- Java 17+ / Maven
- 可选：`ripgrep`（`grep_code` 会优先使用；未安装时自动回退 Java 扫描）
- 至少一个 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY`

## 常用命令

```bash
cp .env.example .env
mvn clean package        # 默认跳过测试，优先产出可手工验收 jar
java -jar target/mindcli-1.0-SNAPSHOT.jar
java -jar target/mindcli-1.0-SNAPSHOT.jar wechat setup   # 主动绑定微信 iLink 通道，默认不开启
java -jar target/mindcli-1.0-SNAPSHOT.jar wechat start   # 前台启动微信通道
/wechat                   # 交互式 CLI 内扫码绑定并后台启动微信通道
mvn test -Pquick          # 常规回归
mvn test -Pphase16-smoke  # 终端渲染相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性
mvn test -DskipTests=false                  # 全量回归
/init                    # 生成精简项目级记忆 MIND.md；已有文件不覆盖，/init --force 可重写
/export                  # 导出当前 ReAct 会话为 Markdown，包含完整 system prompt
/memory export --audit   # 导出记忆审计证据 Markdown
/run inspect <runId>     # 检查指定 Agent Runtime run 的状态、snapshot checkpoint 与恢复提示
/run resume <runId>      # 对 CANCELLED / BUDGET_EXHAUSTED 等可恢复 run 重新进入原始任务
```

## 架构概览

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` + `agent/profile/*` | `/team` |

`/plan` 与 `/team` 是两个不同执行模式，不合并编排语义；二者只共享 `agent/plan/DependencyGraph.java` 里的中性 DAG 计算（拓扑顺序、就绪节点、批次和阻塞依赖诊断），依赖满足规则仍由各自模式按 Task/Step 状态传入。`/plan` 的失败恢复按任务字段 `critical` / `degradation` 决策：`critical=false + degradation=SKIP` 才可跳过，`BLOCK` 直接失败，其余默认回退为局部重规划。`/team` 的 ready batch 现在会先按 step 指纹去重，重复步骤复用同一执行结果；只读 / 非写入步骤优先路由给 `EXPLORER` profile 执行，只允许 `@read` 工具组；编排器内建规划（无独立 planner 子代理）；写入型步骤（`FILE_WRITE`、`CREATE_PROJECT`、高风险 `execute_command` 等）交给 `WORKER`，同一 ready wave 内多个无依赖写入步骤各自创建独立 git worktree 并行执行，完成后先在临时 integration worktree 中统一合并；冲突时整批不更新主工作区，标记步骤失败并上报冲突文件清单；非 git 仓库、checkpoint 或 worktree 创建失败时回退为串行单步。执行者随后进入自己的 review->repair 循环，通过后才关闭步骤，底层 `ToolDispatcher` / `ResourceLockManager` 和 workspace 路径、命令策略仍作为最后一道防线。

ReAct 的多轮控制由 `runtime/run/loop/AgentLoopExecutor.java` 承担，单轮 LLM/tool 交互由 `runtime/run/loop/AgentTurnKernel.java` 承担；`Agent.java` 继续负责 prompt / memory / renderer / 状态栏等 ReAct 周边体验。CLI 生产入口中，ReAct、`/plan`、`/team` 都会先经 `AgentModeRouter` 选择 `ReActModeAdapter` / `PlanModeAdapter` / `TeamModeAdapter`，再进入 `AgentRuntime`；runtime 提供的 `AgentRunContext` 与共享 `RunStore` 会向下传递，避免分裂 runId / store。三条路径的工具调用都会先经 `runtime/run/dispatch/ToolDispatcher.java` 进入内部 Hook、资源分类和资源锁，再映射为结构化 `ToolOutcome`；Plan 仍保留任务级 loop、DAG 和失败恢复，但每个任务的单轮 LLM/tool 交互也复用 `AgentTurnKernel`，任务级预算、结果聚合和 `TOOL_OUTCOME` 事件仍由 Plan 外层负责；SubAgent 的单轮 LLM/tool 交互同样复用 `AgentTurnKernel`，其 profile / 只读 / 自审逻辑仍由 `SubAgent` 自己负责，并继续写入 `TOOL_OUTCOME` 事件。`/team` 已接入 `AgentProfile` / `AgentPool`：内置 `EXPLORER` / `WORKER` 两个子代理硬编码在源码（`AgentProfile.builtinExplorer` / `builtinWorker`），实例固定为 `explorer#1`、`explorer#2`、`worker#1`；规划职责收编到 orchestrator 内建（直接调 LLM + `TEAM_PLANNER` prompt），不再有独立 planner 子代理，也不再读取 `.mindcli/config.toml` 的 `[team.*]` 或 `.mindcli/agents.json`。Profile 的 `commandAllowlist` 为空时表示不增加 Profile 级命令限制，实际命令仍必须经过全局命令策略、HITL 和路径/工作区防护；`AgentPool` 按 profile semaphore 原子 `tryAcquire` 分配 lease，避免并行步骤争抢同一个 profile 后串行化；child run 与 `TOOL_OUTCOME` 会记录 `profileName`、`permissionMode`、`selectedReason` 和 profile policy 决策。

Agent Runtime 账本默认通过 `RunStoreFactory` 写到 `~/.mindcli/runs`（可用 `mindcli.runs.dir` / `MINDCLI_RUNS_DIR` 改写），`InMemoryRunStore` 仅保留给测试和降级。JSONL ledger 是 source of truth：每个事件包含 run 内递增 `seq` 和唯一 `eventId`，`run.meta.json` / `run.state.json` 由事件投影生成；append 会单次加载当前 ledger，并复用同一事件快照完成 seq 分配、坏尾修复与派生文件投影，读取会忽略尾部坏行，继续 append 前会先截断坏尾，`runId` 只能使用安全路径字符。AgentRuntime 可写 `SNAPSHOT_CREATED`，把 `PRE_RUN` / `POST_RUN` Side-Git checkpoint 与 runId 关联；CLI Agent 主路径不再额外套 `SnapshotService.runTurn(...)`，避免同一 run 产生旧 turn snapshot 与 runtime snapshot 两套快照；`/run inspect <runId>` 通过 `RunRecoveryService` 展示状态、checkpoint 和恢复提示。Multi-Agent 的规划阶段由 parent run 直接记录 `LLM_RESPONSE phase=plan`；explorer / worker 的执行与自审会写入 child run，目录布局为 `parentRun/children/childRun/`，事件 attributes 带 `parentRunId`、`rootRunId`、`role`、`stepId`、`attempt`、`phase=execute|review`；parent `run.state.json` 会 materialize child run 摘要。自审调用失败、输出不可解析、重试后仍拒绝时必须 fail closed，不能把执行候选结果标记为完成；review phase 摘要要保留 `approved` / `businessStatus`，供恢复和审计判断。

核心内置工具 13 个：`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `web_search` / `web_fetch` / `save_memory` / `search_memory` / `read_memory` / `revert_turn`

`ToolRegistry` 是工具对外 facade；内置工具的名称、描述、参数 schema 由 `capability/tool/builtin/*ToolRegistrar.java` 维护，通过 `capability/tool/registry/ToolRegistrar` / `ToolRegistrationContext` 注册。文件读取、写入、目录枚举由 `capability/tool/builtin/FileToolExecutor` 承担；`glob_files` / `grep_code` 的参数解析、实时扫描调用和结果预算由 `capability/tool/search/CodeSearchToolExecutor` 承担；项目骨架生成由 `capability/tool/builtin/ProjectToolExecutor` 承担；Skill 正文查找、禁用提示和正文预算由 `capability/tool/builtin/SkillToolExecutor` 承担；Web 搜索、抓取、网络策略与 StepSearch bridge 由 `capability/tool/WebToolExecutor` 承担；Memory 工具由 `capability/tool/MemoryToolExecutor` 承担；Shell 命令的进程启动、超时和输出截断由 `ShellCommandExecutor` 承担，`ToolRegistry` 仅保留兼容入口和跨工具依赖编排。MCP 动态工具状态由 `capability/tool/namespace/McpToolNamespace.java` 管理，`ToolRegistry` 继续保留原有 `registerMcpTool*` / `replaceMcpTool*` 兼容入口。

代码库理解默认走 Claude Code 式实时探索：`glob_files` 找候选文件、`grep_code` 精确定位符号或字符串、`read_file` 按需读取具体行段。长期记忆只在 session 注入受当前项目 scope/status/expiry 过滤的 `MEMORY.md` 短目录，正文通过 `search_memory` 查询后再用 `read_memory(id)` 按需读取；不得让模型直接读取用户目录下的记忆文件。`grep_code` 优先使用本机 `ripgrep`，不可用时回退到 Java 扫描；结果受 `max_results` / `head_limit` / `max_chars` 预算约束，返回 `partial: true` 或 `suggested_reads` 时应继续缩小搜索范围或按建议读取行段。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）。协议与 stdio/Streamable HTTP 传输仅由官方 MCP Java SDK 2.0.1 提供，不保留自研 JSON-RPC / wire-protocol / transport fallback；MindCLI 只保留生命周期、命名空间、策略、审计、内容适配和资源缓存 facade。MCP 启动协调与官方 transport 创建位于 `capability/mcp/lifecycle/`，`McpServerManager` 继续作为对外 facade。

MCP 配置会合并用户级 `~/.mindcli/mcp.json` 与项目级 `.mindcli/mcp.json`；`${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`。检测到 `STEP_API_KEY` 时会自动内置 `step_search` 远程 MCP（显式同名配置优先）。

应用级运行配置统一通过 `platform/config/ConfigValueResolver` 读取，优先级固定为 `JVM system property > OS environment > 项目 .env > 用户 ~/.env > 默认值`；新增配置不要在业务模块重复实现 property/env/.env 解析。`user.home` / `user.dir` / `os.name`、JVM 编码和 `TERM` / `COLORTERM` / `COLUMNS` / `NO_COLOR` 等运行环境探测保留直接读取。

DeepSeek V4 / Kimi thinking 模式下，assistant tool-call 消息的 `reasoning_content` 必须随下一轮请求历史带回；其他 provider 默认只把 reasoning 写日志 / 展示。
DeepSeek SSE 调用默认强制 HTTP/1.1，避免部分网络/网关下 HTTP/2 长流被远端重置成 `stream was reset: INTERNAL_ERROR`。

讯飞星辰 MaaS provider 名为 `xfyun`，默认 Base URL 为 `https://maas-api.cn-huabei-1.xf-yun.com/v2`。`model` 必须使用服务管控页展示的 `modelId`；公开模型名 / Hugging Face 仓库名不一定可直接调用。微调模型用 `/config provider xfyun --lora-id <resourceId>` 配置服务卡片上的 resourceId，MindCLI 会作为 HTTP header `lora_id` 发出。`xfyun` 当前按 MaaS 文档走纯对话请求，不向上游发送 MindCLI 内置工具列表。

## 仓库结构

```
src/main/java/com/mindcli/
├── agent/       ReAct / Plan / Multi-Agent 编排；plan/ 放 Planner / ExecutionPlan / Task / DependencyGraph，team/ 放 Team 编排、调度模型与 TeamStepFormatter，profile/ 放 AgentProfile / AgentPool
├── app/         用户入口适配：cli/（runtime/ 负责模式运行与 SessionContext 交接）、wechat/
├── capability/  Agent 能力：browser/、image/、lsp/、mcp/、memory/（policy/）、skill/、tool/（builtin/registry/namespace/search/）、web/
├── platform/    平台支撑：config/、hitl/、llm/、prompt/、render/、security/、snapshot/、text/
└── runtime/     run/ (facade + store/dispatch/loop/mode/recovery/hook/legacy/session) + api/ (RuntimeApiServer) + task/ (DurableTaskManager)
```

启动与 inline 渲染当前约定：

- 开屏 Banner 使用无右边框的 cyber-lite 简洁布局，避免 CJK/ANSI 字宽导致右侧竖线错位；默认首屏展示 `MindCLI // v...`、Model/Runtime、Mcp、Skills 与 `Command /`、`Context @path`、`Image @image:` 操作提示，不再把 MCP server 明细刷成启动日志。启动 Banner 必须显式使用猫耳助手暖色语义分层：品牌/运行态用 `ACCENT`，版本/模型/主要值用 `PRIMARY`，字段标签用 `SECONDARY`，说明性提示用 `MUTED`，避免首屏退化成黑白灰。启动期 MCP 只允许一行后台启动摘要，并且由 Banner note 展示在 logo 与主信息下方；不要在猫耳图渲染前刷多行进度日志。启动首屏优先从 `src/main/resources/ui/*.png` 随机选择一张图片，并调用本机 `chafa -s 10x10 --dither ordered` 直接渲染到真实终端，chafa 子进程必须继承真实 stdin/stdout 以保留终端探测能力，文字 Banner 放在图片下方；`MINDCLI_CHAFA_BIN` 可指定 chafa 路径，`MINDCLI_UI_MASCOT=false` 禁用。若 chafa 不存在、超时或渲染失败，必须直接回退纯文字首屏，不再维护 `.ans` 资源兜底。启动路径会先通过 `TerminalEncoding` 探测/配置 JLine 终端编码（优先 `-Dmindcli.terminal.encoding`，其次 `MINDCLI_TERMINAL_ENCODING` / `.env`，再到 JVM `sun.stdout.encoding` / `sun.stderr.encoding` / `sun.stdin.encoding`、`System.console().charset()` 和 JVM 默认编码），并把 `-Dmindcli.terminal.type` / `MINDCLI_TERMINAL_TYPE` / `TERM` 中的非 `dumb` 类型传给 JLine。
- inline 模式使用 JLine 4 的 LineReader 编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`。
- 默认 CLI 启动路径先建立 `Terminal -> LineReader -> Renderer`，但 `Renderer.start()` 和底部 dock 初始化必须放在启动猫耳图之后；猫耳图由 native chafa 直接写真实终端，文字 Banner 随后直接打印在图片下方，避免 JLine Status/scroll-region 改变 chafa 的终端探测与显示效果。
- `BottomStatusBar` 现在是 JLine `Status` 托管的底部 dock：由 JLine 维护滚动区域和状态行位置，不再手写 `\n` / `moveUp` / `CLEAR_TO_EOS` 清屏。输入期会把 LineReader 光标定位到 dock 上方一行，让 `*` 输入行和 Status 同处底部区域；dock 保留两类信息：上层模式 + `MCP n/n | SKILL n/n` 摘要，下层 `MINDCLI // model | phase | CTX [...]` 与 `IN/OUT/CACHE`、cost、elapsed、cwd。关键字段可用 cyber-lite 的 JLine `AttributedString` 彩色样式突出，但纯文本格式和宽度裁剪逻辑要保持稳定。`CTX` 表示当前仍会带入下一轮请求的上下文估算；`IN/OUT/CACHE` 表示最近任务的 LLM 调用统计，二者不要混用。
- inline/plain 渲染保持 cyber-lite 语言；对话流标签为 `USER //`、`MINDCLI //`、`SYS //`、`TOOL //`、`OUT //`。
- 普通任务和斜杠命令提交后，`Main` 会把本轮原始输入以暗色整行块写回 transcript：输入态左提示仍是 `* `，提交回显左提示改为 `>`；单行输入只占一行，不额外追加空白行。普通任务随后再展开 MCP resource / 本地 `@path` 并进入 Agent；不要只依赖 JLine 提交行残留，否则 activity 重绘或 dock 刷新可能让用户输入从可见历史里消失。`/clear` 清空 conversationHistory、shortTermMemory，并重建不含上一轮检索记忆的 system prompt；长期记忆保留。`/compact` 会手动压缩当前 ReAct conversationHistory，不等待上下文阈值触发，保留最近 1 个 user 轮次和 tool_call/tool_result 边界。
- ReAct LLM 调用期间，inline renderer 使用固定高度 live thinking 区动态显示 `Thinking...` 和灰色竖线 reasoning 预览；该区域只能清理自己刚打印的几行，不能用独立 JLine `Display.update()` / `CLEAR_TO_EOS` 向上覆盖 transcript。content 或 tool call 开始前先清掉 live 区，再把完整 reasoning 引用块落到正文区，正文回答用低调标记起始，不再刷强标题。
- 交互期输出应优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都支持把输出流接到 inline renderer，避免直接争抢 stdout。
- Phase 22 开始，`InlineRenderer` 可绑定当前 `LineReader`；当 `LineReader.isReading()` 为 true 时，`Renderer.stream()` 的完整行输出优先通过 `LineReader#printAbove` 显示在输入行上方，未绑定 / 非读取态 / 测试路径回退到原 `PrintStream`。
- Markdown 表格渲染要按当前终端列宽分配列宽；长内容在单元格内部换行，不能依赖终端自动折行把整行表格打散。
- ReAct 正常结束后不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 会保留在底部强状态行，phase 回到 `idle`。
- 默认 CLI 启动路径应尽早建立 `Terminal -> LineReader -> Renderer`，启动 Banner、模型加载、MCP 启动、Skill summary、ReAct 提示和退出提示都应走 `Renderer.stream()`；除 fatal bootstrap / runtime API 外，不要在交互主路径新增裸 `System.out.println`。
- 启动期 MCP 不得阻塞首屏：CLI 默认最多等待 8 秒（`MINDCLI_MCP_STARTUP_WAIT_SECONDS` / `-Dmindcli.mcp.startup.wait.seconds` 可调），超时后保留未完成 server 为 `STARTING` 并后台继续初始化；`/mcp` 查看最新状态。
- `LineReader` 使用 `app/cli/interaction/MindCliHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词和明显危险 shell 片段会在编辑阶段被标记；不要把这类视觉提示混入最终提交文本。
- `LineReader` 使用 `app/cli/interaction/MindCliCompleter` 做上下文补全：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、`/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path` 和 MCP resource `@server:uri` 引用都应从同一个 completer 出口维护。
- 普通用户输入进入 Agent 前会先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件会内联为 `<file>` 块，目录会内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文不展开。
- `LineReader` 使用 `app/cli/interaction/MindCliHistory` 持久化输入历史到 `~/.mindcli/history/input.history`；如果 `mindcli.history.file` / `MINDCLI_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。
- 启动期会加载 `~/.mindcli/MIND.md`、项目根 `MIND.md`、项目根 `.mindcli/MIND.md`、`MIND.local.md`、`.mindcli/MIND.local.md`，按此顺序注入 Project Context；`@relative/path.md` 可导入项目根内文件，总注入内容有字符预算，避免项目记忆变成 token 噪音。
- `/init` 会根据当前项目生成短 `MIND.md`，只放 commands / project positioning / architecture / pitfalls / don'ts；默认不覆盖已有文件。
- `/export` 导出当前 ReAct `conversationHistory` 为 Markdown 到 `~/.mindcli/exports/session-*.md`；只支持无参数命令，包含完整 system prompt，便于检查 LLM 实际接收前的指令。
- `Main.java` 是 CLI 入口 facade，当前包路径为 `app/cli/Main.java`；启动前置配置 helper 由 `CliBootstrap` 承接，启动首屏和状态摘要由 `CliStartupView` 承接；`CliCommandRouter` 统一分发低风险 slash command 到 `app/cli/command/*`，当前 `/export`、`/memory`、`/save`、`/snapshot`、`/restore`、`/run inspect`、MCP、Task、Skill、Wechat 已从 `Main` 主循环移出；模型/模式切换和交互生命周期仍由 `Main` facade 管理。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。

## 关键行为约束（Agent 必读）

同一个 CLI 进程维护轻量的 `SessionContext`：一个 session 可包含 ReAct、Plan、Team 等多个 run。每个 run 结束后生成受长度限制的 `RunSummary`，下一次模式启动时注入最近摘要；旧摘要超限后合并为历史摘要。它只负责进程内跨 run 上下文衔接，不替代 `RunStore`，也不改变 `~/.mindcli/runs/<runId>/` 的持久化布局；`/clear` 会清空会话摘要，长期记忆保留。

### Memory

- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取事实
- 自动长期记忆提取默认关闭；即使显式设置 `mindcli.memory.autoExtract.enabled=true` 或 `MINDCLI_MEMORY_AUTO_EXTRACT=true`，也只能生成 `MemoryProposal` 候选，不得直接写入长期记忆。
- 自动提取通过可等待的 `CompletableFuture` 异步执行；候选必须先成功持久化到 `proposals.jsonl`，再发布到内存 pending 列表，后台异常保留在 Future 并记录日志。
- 候选记忆必须经 `/memory proposals` 查看、`/memory approve <id>` 批准或 `/memory reject <id>` 拒绝；不要绕过候选层直接把自动提取结果写入长期记忆。
- `MIND.md` 管团队共享的项目规则，长期记忆管个人或项目作用域的稳定事实；不要把一次性协作经验写进 `MIND.md`
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆注入 prompt 前必须过滤 `status=revoked/deleted/expired` 或 `expiresAt` 已过期的条目；缺失这些治理 metadata 的旧记忆按原兼容规则可见。
- 长期记忆必须可审计和可删除：`/memory policy` / `/memory list` / `/memory search <关键词>` / `/memory delete <id>` / `/memory clear` / `/memory export --audit`
- `search_memory` / `read_memory` 是 run 级只读工具，只接受查询和记忆 ID，不暴露绝对路径；搜索结果是确定性排序的候选和读取指引，不代表事实裁决，正文不会在 run 启动时自动注入。多个候选必须逐一读取比较；涉及当前代码、配置和命令时，实时项目证据优先，不能按更新时间自动覆盖或删除记忆。
- 记忆审计本地 source of truth 是长期记忆目录下的 `audit.jsonl`；有 `AgentRunContext` 的路径还会同步写 RunStore。导出文件写到 `~/.mindcli/exports/memory-audit-*.md`。
- 删除长期记忆使用 tombstone 语义：活动集合移除，原 `.md` 文件保留 `status: deleted` / `deletedAt`，重启加载时不得重新变成活动记忆。
- 两道压缩不要混淆：shortTermMemory 压缩 vs conversationHistory 压缩（后者是防 window 超限的关键）
- 自动压缩阈值按 Claude Code 风格预留摘要输出和安全缓冲：大窗口使用 `window - 20k - 13k`，例如 200k 窗口约 167k 触发、1M 窗口约 967k 触发；小窗口按比例缩小预留。

### HITL + 策略层

- 拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard
- 用户无法批准策略拒绝的请求
- PathGuard 强制路径限定在项目根内
- CommandGuard 是辅助黑名单，不是主防线
- 微信 iLink 通道没有人工审批面板，必须走非交互式默认拒绝策略：只读工具默认允许，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝，文件写入仍由 PathGuard 限定在绑定 workspace 内。

### Plan 审阅交互

- `Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划
- 方向键不应被误判为 ESC
- 涉及改动要连 raw mode 和回退路径一起看

### 并行工具

- 工具调度统一从 `ToolDispatcher` 进入；ReAct、Plan、Multi-Agent 的实际工具执行都使用 context-aware dispatcher，并把结构化 `TOOL_OUTCOME` 写入同一 run ledger。`ToolDispatcher` 是唯一的并行、批超时、资源锁和结果顺序控制者；`ToolRegistry` 只负责单个工具执行并返回结构化 `ToolExecution`。
- `ToolOutcomeStatus` 结构化表达 `COMPLETED` / `PARTIAL` / `DENIED_BY_POLICY` / `DENIED_BY_USER` / `TIMED_OUT` / `CANCELLED` / `FAILED`，运行时不要解析自然语言当唯一控制信号
- `ToolResourceClassifier` 会为工具推导资源锁：文件读 shared、文件写 exclusive，文件访问补充祖先目录 shared 锁；`list_dir` 对目标目录 exclusive，使目录枚举与该目录下文件写入互斥，但同目录不同文件仍可并行写；长期记忆读取 shared、`save_memory` exclusive；workspace 命令默认 exclusive，已知只读命令 shared，但含管道、重定向、命令连接符或外部 diff / output 选项时降级为 exclusive；browser MCP session exclusive、普通 MCP server exclusive、未知副作用工具 workspace exclusive
- `ResourceLockManager` 对规范化真实路径按排序后的资源 key 获取 shared / exclusive 锁，避免死锁；锁由实际工具工作线程持有，超时取消后也必须等工具代码真正退出才能释放；等待锁可被线程中断，dispatcher 将其映射为 `CANCELLED`；结果必须保持原始 tool_call 顺序
- `ToolDispatcher` 会把 run 的 `approvalPolicy` 显式绑定到实际工具工作线程并在 `finally` 清理；需要 HITL 的调用必须拆成单调用批次串行执行，避免并发审批提示，其他无资源冲突调用仍可并行
- `HookManager` 目前只支持内部 Java Hook，生命周期点为 `PRE_TOOL_USE` / `POST_TOOL_USE` / `TOOL_ERROR` / `RUN_STOP`；不要在本阶段新增外部脚本 Hook 生态

### Web + Browser

- 每轮 system prompt 会注入当前日期/时区，用于相对日期理解；联网搜索不再由 prompt 的 Freshness Policy 强制，是否调用 `web_search` 交给模型基于工具 schema 和用户目标自主决定。
- “当前项目/当前 README/当前文件/当前代码”等表达属于本地上下文任务，通常应由模型选择 `glob_files` / `grep_code` / `read_file`，而不是联网工具。
- 当前模型为 `step-3.7-flash*` 且自动/显式 `step_search` MCP 的 `web_search` / `web_fetch` 已就绪时，内置 `web_search` / `web_fetch` 会优先转调 StepSearch MCP；未就绪或调用失败时回退到原 SearchProvider / WebFetcher。
- 已知 URL 先 `web_fetch`，SPA/防爬墙 fallback 到 Chrome DevTools MCP
- 浏览器读取优先 `take_snapshot`，不默认 `take_screenshot`
- 公开页面不要提前切 shared 模式

### Skill

- system prompt 索引段注入三处提示词，上限 20 个 / 4KB
- `load_skill` → tool_result 直接返回 SKILL.md 全文 → 同一 turn 内立即生效

### 视觉输出（HTML 交付物）

- 凡是要「看」或「分享」而非「粘贴到外部平台」的交付物（计划、代码评审、方案对比表、报告、看板、会话交接），渲染成**单个自包含 HTML 文件**：内联 CSS、不发起外部请求。
- HTML 文件统一写到项目根 `html/` 目录（绝对路径 `D:\IntelliJ IDEA 2024.3\IdeaProjects\MindCLI\html`），不要写到系统临时目录。
- 交付前必须在浏览器真正**查看**渲染结果（Windows `start "" "<路径>"`），并**打印绝对路径**；不要把同样的内容再以整段 Markdown 复述一遍。
- 保留 Markdown 的内容：要粘贴到外部平台的东西（发帖文案、正文、Notion 页面正文），以及所有核心配置、指令、记忆文件——HTML 会破坏粘贴目标或白白消耗 token。
- 「给我看看 / show me」→ 渲染成单页 HTML 并打开，而不是输出大段段落。

## 修改时的硬规则

### 1. 改行为 → 同步文档

`AGENTS.md` / `README.md` / `ROADMAP.md`（仅状态变化时）

### 2. 改命令入口 → 联动

`Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`

未识别的 `/xxx` 在 CLI 层直接报"未知命令"，不回退给 Agent。

### 3. 改 Plan 审阅交互 → 联动

`Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证

### 4. 改工具集 → 联动

`ToolRegistry.java` + Agent/PlanExecuteAgent/SubAgent 提示词 + 可能 Planner 提示词 + 文档

### 5. 改模型/接口 → 联动

对应 Client + `LlmClientFactory.java` + `.env.example` + 文档

### 5.2 改 Web/搜索 → `capability/web/` 相关 + ToolRegistry + `.env.example` + 文档 + 测试

### 5.3 改 Memory → `capability/memory/MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档

### 5.4 改 HITL/策略 → `platform/security/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试

### 5.5 改 MCP → `capability/mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试

### 6. 不提交 `.env` / 真实 API Key / `target/` 产物

### 7. 保持代码可读性，不过度抽象

## 验证路径

| 场景 | 命令 |
|------|------|
| 代码搜索工具 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,CliCommandRouterTest,PlanReviewInputParserTest,MainInputNormalizationTest,MainCliBootstrapRefactorTest,MainCliStartupViewRefactorTest,MainMemoryCommandHandlerRefactorTest,MainCommandHandlerRefactorTest,MainConfigCommandHandlerRefactorTest,MainWechatCommandHandlerRefactorTest` |
| DAG/Plan | `mvn test -Dtest=ExecutionPlanTest` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,SubAgentTest,AgentProfileLoaderTest,AgentOrchestratorTest` |
| 终端/渲染 | `mvn test -Pphase16-smoke` |
| 常规回归 | `mvn test -Pquick` |

## 给新线程的导航

1. 先看本文件 → 2. `README.md` → 3. `app/cli/Main.java` → 4. 按任务进入对应模块

| 任务类型 | 先看 |
|----------|------|
| CLI 命令 / 启动 | app/cli/Main.java + CliBootstrap.java + CliStartupView.java + CliCommandParser.java + app/cli/command/* + app/cli/interaction/* |
| 规划/DAG | Agent.java + PlanExecuteAgent.java + agent/plan/Planner.java + agent/plan/ExecutionPlan.java |
| 工具调用 | capability/tool/ToolRegistry.java + capability/tool/builtin/* + capability/tool/namespace/McpToolNamespace.java + runtime/run/dispatch/ToolDispatcher.java + runtime/run/dispatch/ToolOutcome.java |
| ReAct loop | Agent.java + runtime/run/loop/AgentLoopExecutor.java + runtime/run/loop/AgentTurnKernel.java |
| 代码搜索 | capability/tool/builtin/FileToolRegistrar.java + capability/tool/search/CodeSearchToolExecutor.java + ToolRegistry.java 兼容入口 (`glob_files` / `grep_code` / `read_file`) |
| 模型/API | platform/llm/*Client.java + LlmClientFactory.java |
| Multi-Agent | agent/team/AgentOrchestrator.java + agent/team/SubAgent.java + agent/profile/* |
| MCP | capability/mcp/McpServerManager.java + lifecycle/McpStartupCoordinator.java + lifecycle/McpTransportFactory.java + McpClient.java |
| 终端渲染 | platform/render/Renderer.java + RendererFactory.java |

## 当前已知边界

以下在路线图但未交付：容器/VM 沙箱 / MCP OAuth + sampling + server 自动重启

- `/run resume <runId>` 已支持安全重入：仅接受有原始输入且状态为 `RESUMABLE` 的 run，继续使用现有策略/HITL；包含已知写入、命令或 MCP 调用时，必须追加 `--confirm`；存在未完成或无法判断结果的工具调用时，即使 `--confirm` 也必须先人工检查。ReAct run 会从 ledger 重建规范的 `user -> assistant(tool_call) -> tool_result` 消息边界并复用已完成的工具结果；每个 assistant 工具调用都必须有且只有对应的成功结果，账本不完整时不会追加 `RUN_RESUMED` 标记。多次取消/恢复始终从账本生成单份消息历史，避免重复 user/tool 消息；Plan/Team 仍是同一 runId 下的适配器重试。缺少原始输入、终态或人工介入状态的 run 必须先人工处理。
- TODO：启动期自动发现可恢复 run，以及对文件写入、命令执行、未完成 child run 的更细粒度恢复计划确认。

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。
