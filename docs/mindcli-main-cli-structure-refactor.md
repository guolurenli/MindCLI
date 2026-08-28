# MindCLI Main CLI 结构改造技术文档

文档类型: 批次级技术方案  
编写日期: 2026-08-10  
适用范围: `src/main/java/com/mindcli/cli/`, `src/test/java/com/mindcli/cli/`, `AGENTS.md`

## 1. 背景

`Main.java` 当前约 3000 行，承担启动初始化、JLine 交互、slash command 展示、浏览器命令、模型配置、导出、历史记录、输入归一化、运行时 API、微信通道等职责。它能正常工作，但对后续企业级演进不友好：小改动容易碰到启动流程，测试也被迫通过 `Main.*` 访问大量静态 helper。

本次改造目标是让 `Main` 从“大型脚本式入口”逐步变为 CLI facade。第一批只迁移低风险、测试覆盖充足、行为纯度较高的 CLI 支撑逻辑；不改变命令名、渲染文案、执行流程或启动行为。

## 2. 非目标

- 不重写 `main(String[] args)` 主流程。
- 不修改 slash command 名称、补全文案或帮助文本。
- 不修改 `/browser` 命令行为。
- 不修改模型配置、Memory、MCP、Wechat、Runtime API 功能。
- 不改变现有测试调用 `Main.*` 的兼容入口。
- 不提交代码、不合并分支。

## 3. 当前问题

| 问题 | 当前表现 | 风险 |
| --- | --- | --- |
| 主入口过大 | `Main.java` 包含启动、交互、命令、格式化和配置解析 | 后续命令改动容易影响启动主路径 |
| 命令目录不清晰 | slash command catalog、browser command、config command 混在同一类 | 企业级命令扩展缺少归属 |
| 输入交互 helper 分散 | ESC、历史、粘贴、敏感信息脱敏等逻辑都在 `Main` | TUI / inline 行为难以独立测试 |
| 测试耦合入口类 | 多个测试直接调 `Main.*` 静态方法 | 不能一次性删除兼容入口 |

## 4. 目标结构

```text
cli/
├── Main.java                         # CLI facade 与主流程
├── command/
│   ├── BrowserCommandHandler.java     # /browser 子命令
│   └── SlashCommandCatalog.java       # slash command 提示、help、choices
└── interaction/
    └── CliInputSupport.java           # 输入归一化、ESC 分类、历史文件、脱敏
```

第一批完成后，`Main` 仍保留以下 package-private 兼容方法，内部委托新类:

- `startupHints()`
- `slashCommandHints()`
- `slashCommandTailTips()`
- `formatSlashCommandChoices(int)`
- `handleBrowserCommand(...)`
- `prepareSeedBuffer(String)`
- `normalizeLineEndings(String)`
- `classifyEscapeSequence(String)`
- `seedBufferForHistoryNavigation(LineReader, String)`
- `configureHistory(LineReader, Path)`
- `resolveHistoryFile(Path)`
- `normalizeHistoryFile(Path)`
- `clearLineReaderHistory(LineReader)`
- `redactSensitiveInput(String)`

## 5. 第一批改造范围

### 5.1 SlashCommandCatalog

迁移内容:

- `startupHints`
- `SlashCommandHint` 数据结构的新外部版本
- `slashCommandHints`
- `printSlashCommandHelp` 使用的数据源
- `slashCommandTailTips`
- `formatSlashCommandChoices`

兼容策略:

- 保留 `Main.SlashCommandHint` record，避免 `MindCliCompleter` 和现有测试大面积改动。
- `Main.slashCommandHints()` 从 `SlashCommandCatalog` 读取后映射为 `Main.SlashCommandHint`。

### 5.2 BrowserCommandHandler

迁移内容:

- `/browser status`
- `/browser connect`
- `/browser connect <port>`
- `/browser disconnect`
- `/browser tabs`
- 浏览器端口解析和 Chrome 启动帮助文案

兼容策略:

- `Main.handleBrowserCommand(...)` 保持原签名，内部委托 `BrowserCommandHandler.handle(...)`。

### 5.3 CliInputSupport

迁移内容:

- 输入换行归一化
- bracketed paste end marker 清理
- ESC sequence 分类
- 上/下方向键历史 seed
- JLine history 文件解析、配置和清理
- 敏感参数脱敏

兼容策略:

- `Main.EscapeSequenceType` 暂时保留，`Main.classifyEscapeSequence(...)` 把新类枚举映射回旧枚举。
- `Main` 中仍保留主交互读取逻辑，第一批不移动 `readPromptInput` / `readEscapeInput`。

## 6. 行为保持要求

- 所有现有 CLI 测试应继续通过。
- `MindCliCompleter` 可继续通过 `Main.slashCommandHints()` 获得补全候选。
- `/browser` 相关返回文本不变。
- 输入历史默认路径仍为 `~/.mindcli/history/input.history`。
- `MINDCLI_HISTORY_FILE` 指向目录时仍追加 `input.history`。
- ESC、方向键和 bracketed paste 分类行为不变。
- 敏感字段脱敏规则不变。

## 7. 验证策略

第一批目标验证:

```bash
mvn test "-Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest,MainBrowserCommandTest,MainConfigBootstrapTest,SkillCommandHandlerTest,MindCliCompleterTest,MindCliHistoryTest" -DskipTests=false
```

交叉验证:

```bash
mvn test "-Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest,McpToolRegistrationTest" -DskipTests=false
```

最终回归:

```bash
mvn test -Pquick
```

## 8. 后续批次建议

第一批完成后，再考虑:

- `ConfigCommandHandler`: `/config provider` 解析和写入。
- `SessionExportService`: `/export` Markdown 导出。
- `SnapshotCommandHandler`: `/snapshot` / `/restore` / `/run inspect`。
- `WechatCliBootstrap`: 微信 setup/start/status 的 CLI 编排。

后续每批仍遵循: 先写批次文档，再极简迁移，最后跑对应验证。
