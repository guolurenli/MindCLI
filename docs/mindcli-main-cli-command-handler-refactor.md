# MindCLI Main CLI 命令处理层拆分技术文档

文档类型: 批次级技术方案  
编写日期: 2026-08-10  
适用范围: `src/main/java/com/mindcli/cli/`, `src/test/java/com/mindcli/cli/`, `AGENTS.md`

## 1. 背景

第一批已经把 `Main.java` 中的 slash command catalog、`/browser` 子命令、输入归一化和历史 helper 拆到 `cli/command` 与 `cli/interaction`。`Main.java` 当前仍包含多类 slash command 的输出格式化和业务编排，其中低风险、相对独立的部分是:

- `/export`
- `/snapshot`
- `/restore`
- `/run inspect`

这些命令不参与模型切换、不修改 provider 配置、不改变 Agent 主循环，只是把已有状态对象格式化为 CLI 输出。因此本批适合继续拆分命令处理层，让 `Main` 更接近入口 facade。

## 2. 非目标

- 不改 `/config provider`，该命令涉及配置解析、环境变量和模型切换，单独进入后续批次。
- 不改 `CliCommandParser` 的命令名、命令类型或未知命令策略。
- 不改 `SnapshotService`、`RunRecoveryService`、`RunStore` 的业务行为。
- 不改导出 Markdown 格式、文件命名规则或默认导出目录。
- 不改 `/snapshot`、`/restore`、`/run inspect` 的用户可见文案。
- 不创建 Git commit。

## 3. 目标结构

```text
cli/
├── Main.java
└── command/
    ├── BrowserCommandHandler.java
    ├── SlashCommandCatalog.java
    ├── ExportCommandHandler.java
    ├── SnapshotCommandHandler.java
    └── RunCommandHandler.java
```

## 4. 拆分范围

### 4.1 ExportCommandHandler

迁移内容:

- 导出命令执行: `printExportCommand`
- 导出前判断: `hasExportableMessages`
- 导出消息计数: `countExportedMessages`
- Markdown 渲染: `renderConversationExport`
- Markdown fence / role / JSON 参数格式化 helper

兼容策略:

- `Main.hasExportableMessages`
- `Main.countExportedMessages`
- `Main.renderConversationExport`
- `Main.markdownFenceFor`

以上 package-private 兼容方法继续保留，内部委托 `ExportCommandHandler`。

### 4.2 SnapshotCommandHandler

迁移内容:

- `/snapshot`
- `/snapshot status`
- `/snapshot clean`
- `/restore <N>`
- 快照列表输出格式和恢复序号提示

兼容策略:

- `Main.printSnapshotCommand(...)`
- `Main.printRestoreCommand(...)`

保持为包内 facade 方法，主循环仍经 `Main` 调用，内部委托 handler。

### 4.3 RunCommandHandler

迁移内容:

- `/run inspect <runId>`
- run 状态、last event、snapshot checkpoint、恢复提示输出
- 空参数 / 非 inspect 子命令的用法提示

兼容策略:

- `Main.printRunInspect(...)` 保留为包内 facade 方法，内部委托 handler。

## 5. 行为保持要求

- `/export` 仍只支持无参数命令。
- 导出文件仍写入 `~/.mindcli/exports/session-*.md`。
- 导出 Markdown 仍包含 system prompt、reasoning、tool call、tool result，并保持安全 fence 逻辑。
- `/snapshot` 默认列出最近 20 条快照。
- `/snapshot status` 和 `/snapshot clean` 仍直接返回 `SnapshotService` 的格式化输出。
- `/restore` 的 offset 解析仍复用原 `parseAuditCount` 行为: 默认 1，范围 1-100。
- `/run inspect` 仍使用 `RunRecoveryService.inspect(runId)`，不新增 resume 行为。

## 6. 验证策略

TDD RED:

```bash
mvn test "-Dtest=MainCommandHandlerRefactorTest" -DskipTests=false
```

本批目标验证:

```bash
mvn test "-Dtest=CliCommandParserTest,MainCommandHandlerRefactorTest,MainInputNormalizationTest,MainBrowserCommandTest,RunRecoveryServiceTest,SideGitManagerTest" -DskipTests=false
```

交叉验证:

```bash
mvn test "-Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest,McpToolRegistrationTest" -DskipTests=false
```

最终回归:

```bash
mvn test -Pquick
git diff --check
```

## 7. 后续批次

本批完成后，下一批再拆:

- `ConfigCommandHandler`: `/config provider ...`
- `WechatCliCommandHandler`: `/wechat setup/status/stop`
- `CliBootstrap`: Terminal / Renderer / LineReader / Runtime API 启动编排

