# MindCLI 自定义子代理设计

> 状态：已实现，后续仅保留体验增强项
> 日期：2026-08-16
> 范围：在已对齐 Codex 的内置 `EXPLORER`/`WORKER` 子代理基础上，引入用户自定义子代理，支持交互式创建与直连唤醒。

## 1. 背景与目标

MindCLI 的 `/team`（Multi-Agent）在上一轮已完成 Codex 对齐：仅保留内置 `explorer`（×2）与 `worker`（×1）两个角色，规划职责收编到 `AgentOrchestrator` 内联执行，`.mindcli/config.toml` 团队配置已移除。

本轮目标是让用户能**自定义子代理**，对齐 Codex 的自定义 agent 能力：

- 每个自定义 agent 用 `.mindcli/agents/<name>.toml` 描述，字段对齐 Codex（`name` / `description` / `developer_instructions` / `sandbox_mode` / `approval_policy` / `model`）。
- 自定义 agent 参与 `/team` 的委派（主代理规划时根据 `description` 选人）。
- 提供 `/agent create` 交互式创建，以及 `/agent <name> <任务>` 直连唤醒。

## 2. 范围

### 本次做

- `.mindcli/agents/*.toml` 配置加载（Codex 字段格式）。
- 数据模型：`AgentRole.CUSTOM` + `AgentProfile` 扩展 + `sandbox_mode` 权限映射。
- 提示词：自定义 agent 走 `developer_instructions`，保留 MindCLI 通用协议。
- 委派：`preferredAgent` 优先 / 只读-写入二分兜底，plan prompt 注入可用 agent 清单。
- 命令族：`/agent`、`/agent <name>`、`/agent <name> <任务>`、`/agent create`。
- 写入隔离：`writeScope` 硬约束 `write_file` / `create_project`，scoped `execute_command` 默认 fail closed，只放行明显只读命令。
- worktree 并行：互不重叠的写入型步骤可在独立 git worktree 中并行执行，merge 冲突显式失败并上报。

### 本次不做（后续）

- `/agent remove`（删自定义 agent 文件）。
- `TeamScheduler` 抽取（调度逻辑仍集中在 `AgentOrchestrator`）。
- Codex 式动态 `spawn_agent` 工具调用（仍沿用"规划→委派"静态框架）。

## 3. 配置格式

目录：`.mindcli/agents/<name>.toml`，一个文件一个 agent，文件名即 `name`。

```toml
# .mindcli/agents/code-reviewer.toml
name = "code-reviewer"
description = "审查代码安全漏洞，输出带文件路径和行号的报告"

developer_instructions = """
你是一个资深代码审查员，专注于安全漏洞。
报告必须包含：文件路径、行号、漏洞类型、修复建议。
只输出审查结论，不要修改任何文件。
"""

sandbox_mode = "read-only"      # read-only | workspace-write | danger-full-access
approval_policy = "on-request"  # on-request | untrusted | never
model = "auto"                  # 可选，默认 auto
```

字段说明：

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `name` | 是 | — | 小写字母/数字/连字符，全局唯一（与内置、其他自定义不重名） |
| `description` | 是 | — | 一句话描述，注入 plan prompt 供主代理选人 |
| `developer_instructions` | 是 | — | 自定义系统提示词（人设），内联多行字符串 |
| `sandbox_mode` | 否 | `workspace-write` | 工具权限级别，见映射表 |
| `approval_policy` | 否 | `on-request` | 审批严格度 |
| `model` | 否 | `auto` | 模型覆盖，沿用现有 `auto` 语义 |

## 4. 数据模型改造

### 4.1 `AgentRole` 新增 `CUSTOM`

```java
public enum AgentRole {
    EXPLORER("探索者", "..."),
    WORKER("执行者", "..."),
    CUSTOM("自定义", "用户通过 .mindcli/agents/*.toml 定义的自定义子代理");
}
```

### 4.2 `AgentProfile` 新增字段

现有 record 追加两个字段（内置 factory 传空/默认）：

```java
public record AgentProfile(
        String name,
        AgentRole role,
        String description,
        List<String> tools,
        List<String> deniedTools,
        List<String> commandAllowlist,
        String model,
        int maxConcurrency,
        String permissionMode,
        String memoryScope,
        String contextMode,
        String developerInstructions,   // 新增，内置为空串
        String approvalPolicy           // 新增，内置为 "on-request"
) { ... }
```

新增工厂方法：

```java
public static AgentProfile custom(
        String name, String description, String developerInstructions,
        String sandboxMode, String approvalPolicy, String model) {
    // 将 sandbox_mode 映射为 tools + permissionMode（见 4.3）
}
```

### 4.3 `sandbox_mode` → 权限映射

`sandbox_mode` 是用户友好层，加载时转换为内部已有的 `tools` + `permissionMode`，避免改动 `AgentToolPolicy` / `SubAgent` 的核心判定逻辑。

| `sandbox_mode` | 内部 `tools` | 内部 `permissionMode` | 说明 |
|---|---|---|---|
| `read-only` | `["@read"]` | `READ_ONLY` | 只读，等同内置 explorer |
| `workspace-write` | `["*"]` | `LEGACY_COMPAT` | 写工作区，等同内置 worker，受 PathGuard 限制 |
| `danger-full-access` | `["*"]` | `DANGER_FULL_ACCESS` | 全权限（新增 permissionMode 值） |

`approval_policy` 映射到现有审批链路（`ApprovalPolicy` + `HitlToolRegistry` + `PromptAssembler.approvalMode`）：

| `approval_policy` | 语义 | MindCLI 映射 |
|---|---|---|
| `on-request` | 模型决定是否请求批准（默认） | `approvalMode="suggest"` + 现有静态规则（危险工具/MCP 才 HITL） |
| `never` | 从不请求批准 | `approvalMode="never"` + 该 agent 绕过 HITL（`requiresApproval` 恒 false） |
| `untrusted` | 每步都请求批准（最严） | 该 agent 的 `requiresApproval` 恒 true（连只读都问） |

改造点（现有审批是全局静态的，需 per-agent 化）：

1. `ApprovalPolicy.requiresApproval(String toolName)` 新增重载 `requiresApproval(String toolName, String approvalPolicy)`：
   - `never` → `false`；`untrusted` → `true`；默认（on-request）→ 现有 `DANGEROUS_TOOLS` 静态判断。
2. `HitlToolRegistry` / `SubAgent` 工具执行路径传入 profile 的 `approvalPolicy`。
3. `PromptContext.approvalMode` 同步设 `suggest` / `never`（软提示层对齐）。

### 4.4 `AgentProfileLoader` 加载逻辑

```java
public static List<AgentProfile> load(Path projectRoot) {
    List<AgentProfile> profiles = new ArrayList<>(builtinDefaults());  // explorer#1/2 + worker#1
    Path agentsDir = projectRoot.resolve(".mindcli").resolve("agents");
    if (Files.isDirectory(agentsDir)) {
        for (Path file : listTomlFiles(agentsDir)) {
            try {
                AgentProfile p = parseCustomAgent(file);
                if (profiles.stream().noneMatch(x -> x.name().equals(p.name()))) {
                    profiles.add(p);
                } else {
                    log.warn("duplicate agent name skipped: {}", p.name());
                }
            } catch (Exception e) {
                log.warn("skip invalid agent config: {}", file, e);   // fail-soft
            }
        }
    }
    return profiles;
}
```

- 内置 3 个永远在，自定义追加。
- 坏文件 / 重名跳过，不阻塞 `/team` 启动。

## 5. 提示词组装

### 5.1 `PromptAssembler.assembleCustom`

新增重载，与 `assemble()` 唯一区别：用 `developer_instructions` 取代 mode 的静态 resource：

```java
public String assembleCustom(String developerInstructions, PromptContext ctx) {
    // 同 assemble()：base + personalities + approvals + runtime + project context
    //              + skills + context-management + handoff
    // 唯一差异：append(developerInstructions) 取代 append(repository.loadRequired(mode.resourcePath()))
}
```

### 5.2 `SubAgent.getSystemPrompt()`

```java
private String getSystemPrompt() {
    if (role == AgentRole.CUSTOM) {
        return promptAssembler.assembleCustom(profile.developerInstructions(), context());
    }
    return promptAssembler.assemble(promptMode(), context());
}
```

自定义 agent 有人设，又自动继承 MindCLI 的工具协议、审批、记忆、skill 等通用能力。

## 6. 委派路由

### 6.1 `AgentPool.acquireByName`

跨 role 按名字精确匹配：

```java
public AgentLease acquireByName(String name, AgentTaskRequirements req) { ... }
```

### 6.2 `runStep` 决策：preferredAgent 优先 / 二分兜底

```java
AgentLease lease;
if (step.preferredAgent() 非空 && agentPool.hasProfile(step.preferredAgent())) {
    lease = agentPool.acquireByName(step.preferredAgent(), requirements);  // 命中自定义/内置 agent
} else {
    lease = agentPool.acquire(executionRoleFor(step), requirements);        // 兜底：只读→explorer、写入→worker
}
```

### 6.3 plan prompt 注入可用 agent 清单

`planWithOrchestrator` 组装 `TEAM_PLANNER` prompt 时动态注入：

```text
可用子代理：
- explorer#1 / explorer#2 —— 内置，只读探索
- worker#1 —— 内置，执行写操作
- code-reviewer —— 自定义，只读，审查代码安全漏洞（description 原样注入）
```

让主代理规划时按 `description` 在 `preferredAgent` 字段填合适的 agent 名。

## 7. `/agent` 命令族

| 命令 | 行为 |
|---|---|
| `/agent` | 列出可用 agent（内置 3 + 自定义 N，含 name / description / sandbox_mode） |
| `/agent <name>` | 查看单个 agent 详情（含 developer_instructions 摘要） |
| `/agent <name> <任务>` | 直连执行：单代理跑任务，不经 orchestrator 规划/委派/自审 |
| `/agent create` | 交互式创建，生成 `.mindcli/agents/<name>.toml` |

### 7.1 `/agent create` 交互流程

逐步问答，最后写文件：

```
/agent create
→ name?  code-reviewer
→ description?  审查代码安全漏洞
→ sandbox_mode?  1) read-only  2) workspace-write  3) danger-full-access  [默认 2]
→ approval_policy?  1) on-request  2) untrusted  3) never  [默认 1]
→ model?  [回车=auto]
→ developer_instructions?  （多行，单独一行 "." 结束）
→ ✅ 已生成 .mindcli/agents/code-reviewer.toml
```

### 7.2 `/agent <name> <任务>` 执行路径

```
Main 解析到 /agent <name> <任务>
  → AgentProfileLoader 找到 name 对应 profile
  → new SubAgent(profile, llmClient, toolRegistry) + 注入 memory/skill/externalContext
  → 复用 AgentModeRouter + 轻量 SingleAgentAdapter（走 runtime run ledger）
  → 返回结果
```

### 7.3 接入点

- `CliCommandParser` 加 `AGENT` 命令类型 + 四种形式解析。
- 新增 `AgentCommandHandler`（参照 `ConfigCommandHandler` / `MemoryCommandHandler`）。
- `SlashCommandCatalog` 加 `/agent` 补全提示。
- `Main` 加 `AGENT` 分支。

## 8. 错误处理

| 场景 | 策略 |
|---|---|
| toml 解析失败 / 缺必填字段 | fail-soft：加载时 warn 跳过 |
| `name` 非法或重复 | 加载跳过；`/agent create` 就地提示重输 |
| `developer_instructions` 为空 | 视为缺必填字段，跳过 |
| `sandbox_mode` / `approval_policy` 非法值 | 回退默认并 warn |
| `/agent <name>` 找不到 | 提示未找到，列出可用名 |
| `/agent create` 写文件失败 | 打印错误，不崩溃 |
| 委派时 `preferredAgent` 指向已删 agent | 回退二分路由 |
| 直连执行 LLM 失败 | 复用 `SubAgent` 现有 `AgentMessage.error` |

原则：**配置文件坏不炸程序（fail-soft），用户交互错误即时反馈（fail-fast on input）**。

## 9. 测试

| 模块 | 测试点 |
|---|---|
| `AgentProfileLoaderTest` | 读 `.mindcli/agents/*.toml`；坏文件跳过；重名跳过；sandbox 映射正确 |
| `AgentProfileTest` | `CUSTOM` + `developerInstructions` + `approvalPolicy` 字段 |
| `SubAgentTest` | CUSTOM 走 `assembleCustom`；内置走原路径 |
| `AgentPoolTest` | `acquireByName` 按名匹配自定义 agent |
| `AgentOrchestratorTest` | preferredAgent 命中自定义 / 未命中回退二分 |
| `CliCommandParserTest` | `/agent` 四种形式解析 |
| `AgentCommandHandlerTest` | `create` 生成 toml 字段/默认值正确 |
| `AgentToolPolicyTest` | `DANGER_FULL_ACCESS` / sandbox 映射后权限判定 |
| `ApprovalPolicyTest` | `approval_policy` 三档（never / on-request / untrusted）的 `requiresApproval(tool, policy)` 判定 |

命令：`mvn test -DskipTests=false`。

## 10. 依赖

新增 TOML 解析库（当前 `src` 已无 TOML 依赖）：

```xml
<dependency>
    <groupId>org.tomlj</groupId>
    <artifactId>tomlj</artifactId>
    <version>1.1.1</version>
</dependency>
```

## 11. 验收标准

1. `/agent create` 能交互式生成合法 `.mindcli/agents/<name>.toml`。
2. `/agent` 能列出内置 + 自定义 agent。
3. `/agent <name> <任务>` 能用自定义 agent 直连执行。
4. `/team` 规划 prompt 能看到自定义 agent，且 `preferredAgent` 指向自定义 agent 时能正确委派。
5. 坏 toml / 重名不阻塞 `/team` 启动。
6. 全部新增测试通过，现有 53 个测试不回归。
