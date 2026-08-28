# CLAUDE.md

MindCLI 是面向商业使用的 Java Agent CLI 产品，对标 Claude Code。完整规则见根目录 `AGENTS.md`。

## 构建与测试

- 构建：`mvn clean package`（默认跳过测试）
- 快速回归：`mvn test -Pquick`
- TUI 测试：`mvn test -Pphase16-smoke`
- 针对性测试：`mvn test -Dtest=XxxTest -DskipTests=false`

## 架构

- 三条执行路径：ReAct（`Agent.java`）/ Plan（`PlanExecuteAgent.java`）/ Multi-Agent（`AgentOrchestrator.java`）
- 共享：`ToolRegistry` / `MemoryManager` / `SnapshotService`
- 工具调用统一走 `runtime/run/ToolDispatcher.java`

## 关键约定

- 改行为 → 同步 `AGENTS.md` / `README.md` / `ROADMAP.md`
- 改命令入口 → 联动 `Main.java` + `CliCommandParser` + 测试 + 文档
- 不提交 `.env` / 真实 API Key / `target/`
- 代码定位优先 `glob_files` / `grep_code` / `read_file`
- 项目级记忆文件是 `MIND.md`（启动自动注入）
