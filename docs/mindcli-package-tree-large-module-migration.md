# MindCLI 大模块文件树迁移技术文档

文档类型: 大模块迁移方案  
编写日期: 2026-08-10  
适用范围: `src/main/java/com/mindcli/`, `src/test/java/com/mindcli/`, `pom.xml`, `README.md`, `AGENTS.md`

## 1. 目标

将当前平铺在 `com.mindcli` 下的 20 多个顶层包，按企业级 Agent 产品的职责重新归位为大模块结构。迁移只调整包名、目录和 import，不改业务流程、命令语义、工具行为、记忆策略、MCP 协议或交互文案。

## 2. 迁移原则

- 大模块迁移一次完成，不再拆很多小批次。
- 迁移后统一做编译和回归测评。
- `Main` 可以迁移，但必须同步 `pom.xml` 的 `mainClass`。
- Java 源码和测试包同步迁移，避免 package-private 测试失效。
- 使用机械化全局替换，减少手工漏改。

## 3. 目标文件树

```text
com.mindcli
├── app/                 # 用户入口和外部通道适配
│   ├── cli/
│   ├── tui/
│   └── wechat/
├── agent/               # Agent 编排核心
│   ├── profile/
│   └── plan/
├── capability/          # Agent 可调用或可组合的能力模块
│   ├── browser/
│   ├── image/
│   ├── lsp/
│   ├── mcp/
│   ├── memory/
│   ├── rag/
│   ├── skill/
│   ├── tool/
│   └── web/
├── platform/            # 平台支撑能力
│   ├── config/
│   ├── hitl/
│   ├── llm/
│   │   └── context/
│   ├── prompt/
│   ├── render/
│   │   └── terminal/
│   ├── security/
│   ├── snapshot/
│   └── text/
├── runtime/
│   ├── api/
│   ├── run/
│   └── task/
```

## 4. 包迁移映射

| 原包 | 目标包 |
| --- | --- |
| `com.mindcli.app.cli` | `com.mindcli.app.cli` |
| `com.mindcli.app.tui` | `com.mindcli.app.tui` |
| `com.mindcli.app.wechat` | `com.mindcli.app.wechat` |
| `com.mindcli.agent.plan` | `com.mindcli.agent.plan` |
| `com.mindcli.capability.browser` | `com.mindcli.capability.browser` |
| `com.mindcli.capability.image` | `com.mindcli.capability.image` |
| `com.mindcli.capability.lsp` | `com.mindcli.capability.lsp` |
| `com.mindcli.capability.mcp` | `com.mindcli.capability.mcp` |
| `com.mindcli.capability.memory` | `com.mindcli.capability.memory` |
| `com.mindcli.capability.rag` | `com.mindcli.capability.rag` |
| `com.mindcli.capability.skill` | `com.mindcli.capability.skill` |
| `com.mindcli.capability.tool` | `com.mindcli.capability.tool` |
| `com.mindcli.capability.tool.namespace` | `com.mindcli.capability.tool.namespace` |
| `com.mindcli.capability.web` | `com.mindcli.capability.web` |
| `com.mindcli.platform.config` | `com.mindcli.platform.config` |
| `com.mindcli.platform.llm.context` | `com.mindcli.platform.llm.context` |
| `com.mindcli.platform.hitl` | `com.mindcli.platform.hitl` |
| `com.mindcli.platform.llm` | `com.mindcli.platform.llm` |
| `com.mindcli.platform.security` | `com.mindcli.platform.security` |
| `com.mindcli.platform.prompt` | `com.mindcli.platform.prompt` |
| `com.mindcli.platform.render` | `com.mindcli.platform.render` |
| `com.mindcli.platform.render.terminal` | `com.mindcli.platform.render.terminal` |
| `com.mindcli.platform.snapshot` | `com.mindcli.platform.snapshot` |
| `com.mindcli.platform.text` | `com.mindcli.platform.text` |
| `com.mindcli.runtime.run` | `com.mindcli.runtime.run` |

保留不迁移：

- `com.mindcli.agent`
- `com.mindcli.runtime`
- `com.mindcli.runtime.api`
- `com.mindcli.runtime.task`

## 5. 配套修改

- `pom.xml`
  - `maven-jar-plugin` mainClass 改为 `com.mindcli.app.cli.Main`
  - `maven-shade-plugin` mainClass 改为 `com.mindcli.app.cli.Main`
- `AGENTS.md`
  - 更新仓库结构说明
  - 更新导航路径
- `README.md`
  - 若存在旧包路径或 mainClass 描述，同步更新
- `.gitignore`
  - 放行本技术文档

## 6. 非目标

- 不拆分 `Main.java` 的剩余命令逻辑。
- 不改变 slash command、模型选择、MCP、Memory、RAG、ToolRegistry 的行为。
- 不引入新的接口层或适配器抽象。
- 不修改测试断言，仅同步 package/import。

## 7. 统一回归测评

迁移完成后统一运行：

```powershell
mvn test "-DskipTests=false"
mvn test -Pquick
git diff --check
```

如果全量测试耗时过长，至少必须完成：

```powershell
mvn test "-DskipTests=false"
```

## 8. 预期收益

- 顶层包数量显著减少，新线程通过文件树即可判断入口、能力、平台和运行时归属。
- `agent` 与 `runtime.run` 语义更清楚，避免 `agent` / `runtime.agent` 双重命名造成误读。
- 能力模块统一进入 `capability`，后续做企业级能力注册、权限、审计、沙箱和插件化时边界更自然。
- 平台能力统一进入 `platform`，LLM、Prompt、Renderer、Security、Config、Snapshot 不再和业务能力平铺混放。
