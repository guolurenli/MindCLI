# MindCLI Package Tree Enterprise Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `src/main/java/com/mindcli` 从能力平铺式文件树逐步治理为入口清晰、能力归位、运行时边界明确的企业级 Agent 代码结构。

**Architecture:** 本轮采用渐进迁移，不重命名启动主类 `com.mindcli.app.cli.Main`，不改变 CLI、Agent、Tool、Memory、MCP 的运行流程。先治理低风险的 CLI 根包叶子类，让 `cli` 根包只保留入口、启动和解析 facade，命令处理进入 `cli.command`，JLine 输入适配进入 `cli.interaction`。

**Tech Stack:** Java 17, Maven, JLine 4, JUnit 5, MindCLI existing package layout.

## Global Constraints

- 改动前先写技术文档，文档确认后再实现代码。
- 实现代码使用极简模式，只做结构归位和 import/package 更新。
- 每个迁移批次必须有独立验证，不改变现有流程、架构语义和用户可见功能。
- 不移动 `com.mindcli.app.cli.Main`，避免影响 `pom.xml` manifest mainClass、README 启动命令和已有脚本。
- 不改 slash command 名称、补全文案、启动 Banner、输入历史策略或 skill 命令输出。
- 不提交 `.env`、真实 API Key、`target/` 产物。

---

## 1. 当前结构问题

当前 `src/main/java/com/mindcli` 顶层包超过 20 个，既有按入口命名的 `cli` / `tui` / `wechat`，也有按能力命名的 `memory` / `rag` / `mcp` / `tool`，还有按基础设施命名的 `runtime` / `policy` / `prompt` / `render`。这种混合结构能工作，但对企业级 Agent 产品有三个问题：

| 问题 | 当前表现 | 影响 |
| --- | --- | --- |
| 入口层过厚 | `cli` 直接认识 Agent、Memory、MCP、RAG、Wechat、Render、Runtime 等能力 | 新增命令时容易继续把逻辑塞进 `Main` |
| 根包职责不纯 | `cli` 根包同时放 `Main`、命令处理、JLine completer/highlighter/history、项目记忆初始化 | 新线程很难通过文件树判断一个类属于入口编排、命令处理还是输入适配 |
| 能力层与适配层混放 | `/skill` 处理器留在 `cli` 根包，JLine 输入类也留在 `cli` 根包 | 包名没有表达 seam，测试和后续迁移缺少清晰归属 |

企业级文件树的目标不是“目录越多越好”，而是让调用者通过包名就能判断一个 Module 的 Interface 位置。`Main` 应该是 CLI facade；命令处理类应该聚合在 `cli.command`；JLine 输入适配应该聚合在 `cli.interaction`。

## 2. 长期目标结构

长期治理方向如下，本轮不一次性完成：

```text
com.mindcli
├── app                 # 后续目标: CLI / TUI / WeChat / Runtime API 入口适配
├── agent               # ReAct / Plan / Team / profile 等 Agent 编排
├── capability          # 后续目标: memory / rag / tool / browser / web / skill / mcp
├── runtime             # run ledger / dispatcher / lock / hook / durable task
├── platform            # llm / render / config / policy / snapshot / prompt
└── util                # 少量真正通用工具
```

本轮只执行长期目标下的第一步：**治理现有 `cli` 包内部结构**。原因是 `com.mindcli.app.cli.Main` 当前仍是 Maven manifest 主类，直接移动顶层入口会牵动启动、文档、脚本和测试，收益不如先让 `cli` 内部边界清楚。

## 3. 本批目标结构

改造前：

```text
cli/
├── Main.java
├── SkillCommandHandler.java
├── MindCliCompleter.java
├── MindCliHighlighter.java
├── MindCliHistory.java
├── command/
│   ├── BrowserCommandHandler.java
│   ├── ConfigCommandHandler.java
│   ├── ExportCommandHandler.java
│   ├── MemoryCommandHandler.java
│   ├── RunCommandHandler.java
│   ├── SlashCommandCatalog.java
│   ├── SnapshotCommandHandler.java
│   └── WechatCliCommandHandler.java
└── interaction/
    └── CliInputSupport.java
```

改造后：

```text
cli/
├── Main.java
├── CliBootstrap.java
├── CliStartupView.java
├── CliCommandParser.java
├── PlanReviewInputParser.java
├── LocalPathMentionExpander.java
├── ProjectMemoryInitializer.java
├── command/
│   ├── BrowserCommandHandler.java
│   ├── ConfigCommandHandler.java
│   ├── ExportCommandHandler.java
│   ├── MemoryCommandHandler.java
│   ├── RunCommandHandler.java
│   ├── SkillCommandHandler.java
│   ├── SlashCommandCatalog.java
│   ├── SnapshotCommandHandler.java
│   └── WechatCliCommandHandler.java
└── interaction/
    ├── CliInputSupport.java
    ├── MindCliCompleter.java
    ├── MindCliHighlighter.java
    └── MindCliHistory.java
```

## 4. 本批迁移清单

| 文件 | 从 | 到 | 调整 |
| --- | --- | --- | --- |
| `SkillCommandHandler.java` | `com.mindcli.app.cli` | `com.mindcli.app.cli.command` | 类和静态方法改为 `public`，供 `Main` 与测试导入 |
| `MindCliCompleter.java` | `com.mindcli.app.cli` | `com.mindcli.app.cli.interaction` | 类和构造器改为 `public`；slash command 数据源改为 `SlashCommandCatalog` |
| `MindCliHighlighter.java` | `com.mindcli.app.cli` | `com.mindcli.app.cli.interaction` | 类改为 `public`，行为不变 |
| `MindCliHistory.java` | `com.mindcli.app.cli` | `com.mindcli.app.cli.interaction` | 类和 `shouldSkip` 改为 `public`，行为不变 |
| `Main.java` | 保持 | 保持 | 更新 import，继续创建 JLine 适配器 |
| CLI 相关测试 | 保持测试包 | 保持测试包 | 添加新包 import，不改断言 |
| `AGENTS.md` | 更新结构说明 | 更新结构说明 | 只更新文件树说明，不写路线图状态 |

## 5. 非目标

- 不移动 `Main.java` 到 `app.cli`。
- 不移动 `tui`、`wechat`、`runtime.api` 等入口包。
- 不合并 `agent` 与 `runtime.agent`。
- 不拆 `ToolRegistry.java`、`Agent.java`、`PlanExecuteAgent.java`。
- 不修改 `SkillRegistry`、`McpServerManager`、`LineReader` 配置逻辑。
- 不改变任何命令输出文案或补全候选。

## 6. 行为保持要求

- 启动时仍通过 `LineReaderBuilder.history(new MindCliHistory())` 使用同一输入历史过滤策略。
- `MindCliCompleter` 仍补全 `/model`、`/config`、`/mcp`、`/skill`、`/task`、`/run`、`/browser`、`/snapshot`、本地 `@path` 和 `@image:`。
- slash command 全量候选仍来自 `SlashCommandCatalog.slashCommandHints()`。
- `MindCliHighlighter` 仍只影响编辑态显示，不改变提交文本。
- `/skill list/show/on/off/reload` 行为和文案保持不变。

## 7. 验证策略

本批目标验证：

```powershell
mvn test "-Dtest=MindCliCompleterTest,MindCliHighlighterTest,MindCliHistoryTest,SkillCommandHandlerTest,MainInputNormalizationTest" -DskipTests=false
```

CLI 回归验证：

```powershell
mvn test "-Dtest=CliCommandParserTest,MainCliBootstrapRefactorTest,MainCliStartupViewRefactorTest,MainInputNormalizationTest,SkillCommandHandlerTest" -DskipTests=false
```

最终回归：

```powershell
mvn test -Pquick
git diff --check
```

## 8. 实施任务

### Task 1: CLI Root Leaf Classes Rehome

**Files:**
- Modify: `src/main/java/com/mindcli/cli/SkillCommandHandler.java`
- Modify: `src/main/java/com/mindcli/cli/MindCliCompleter.java`
- Modify: `src/main/java/com/mindcli/cli/MindCliHighlighter.java`
- Modify: `src/main/java/com/mindcli/cli/MindCliHistory.java`
- Modify: `src/main/java/com/mindcli/cli/Main.java`
- Modify: `src/test/java/com/mindcli/cli/PaiCliCompleterTest.java`
- Modify: `src/test/java/com/mindcli/cli/PaiCliHighlighterTest.java`
- Modify: `src/test/java/com/mindcli/cli/PaiCliHistoryTest.java`
- Modify: `src/test/java/com/mindcli/cli/SkillCommandHandlerTest.java`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: `SlashCommandCatalog.slashCommandHints()`
- Produces: `com.mindcli.app.cli.command.SkillCommandHandler`
- Produces: `com.mindcli.app.cli.interaction.MindCliCompleter`
- Produces: `com.mindcli.app.cli.interaction.MindCliHighlighter`
- Produces: `com.mindcli.app.cli.interaction.MindCliHistory`

- [ ] **Step 1: 修改 package 与可见性**

`SkillCommandHandler` package 改为 `com.mindcli.app.cli.command`，类和静态方法改为 `public`。

`MindCliCompleter`、`MindCliHighlighter`、`MindCliHistory` package 改为 `com.mindcli.app.cli.interaction`，类和构造器改为 `public`。

- [ ] **Step 2: 更新 Main imports**

`Main.java` 添加：

```java
import com.mindcli.app.cli.command.SkillCommandHandler;
import com.mindcli.app.cli.interaction.MindCliCompleter;
import com.mindcli.app.cli.interaction.MindCliHighlighter;
import com.mindcli.app.cli.interaction.MindCliHistory;
```

- [ ] **Step 3: 去掉 completer 对 Main 的数据依赖**

`MindCliCompleter.completeSlashCommand` 遍历：

```java
for (SlashCommandCatalog.SlashCommandHint hint : SlashCommandCatalog.slashCommandHints()) {
    String command = hint.insertText();
}
```

- [ ] **Step 4: 移动文件到目标目录**

文件物理路径与 package 保持一致：

```text
src/main/java/com/mindcli/cli/command/SkillCommandHandler.java
src/main/java/com/mindcli/cli/interaction/MindCliCompleter.java
src/main/java/com/mindcli/cli/interaction/MindCliHighlighter.java
src/main/java/com/mindcli/cli/interaction/MindCliHistory.java
```

- [ ] **Step 5: 更新测试 import**

相关测试继续留在 `com.mindcli.app.cli`，只添加新包 import：

```java
import com.mindcli.app.cli.command.SkillCommandHandler;
import com.mindcli.app.cli.interaction.MindCliCompleter;
import com.mindcli.app.cli.interaction.MindCliHighlighter;
import com.mindcli.app.cli.interaction.MindCliHistory;
```

- [ ] **Step 6: 运行验证**

```powershell
mvn test "-Dtest=MindCliCompleterTest,MindCliHighlighterTest,MindCliHistoryTest,SkillCommandHandlerTest,MainInputNormalizationTest" -DskipTests=false
mvn test "-Dtest=CliCommandParserTest,MainCliBootstrapRefactorTest,MainCliStartupViewRefactorTest" -DskipTests=false
mvn test -Pquick
git diff --check
```

## 9. 后续批次建议

完成本批后，再按风险从低到高推进：

1. `McpCommandHandler`: `/mcp list/restart/logs/disable/enable/resources/prompts` 从 `Main` 迁入 `cli.command`。
2. `ModelCommandHandler`: `/model` 展示、切换和 provider 选择迁入 `cli.command`。
3. `RagCommandHandler`: `/index`、`/search`、`/graph` 迁入 `cli.command`。
4. `RuntimeCommandEntrypoint`: runtime serve、headless task、task manager 入口从 `Main` 拆出。
5. `InteractiveCliSession`: 交互主循环独立成深 Module，`Main` 只负责 bootstrap。

## 10. 文档正确性检查

- 本文提到的待迁移文件当前均存在于 `src/main/java/com/mindcli/cli`。
- `cli.command` 和 `cli.interaction` 目录当前已存在，迁移不需要新增新的命名维度。
- `SlashCommandCatalog` 当前已提供 public `slashCommandHints()`，可作为 `MindCliCompleter` 的直接数据源。
- `pom.xml` 当前 mainClass 是 `com.mindcli.app.cli.Main`，本批不移动 Main，因此不需要修改 Maven manifest。
