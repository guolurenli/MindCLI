# MindCLI ToolRegistry Step 2 低风险工具族迁移技术文档

文档类型: 步骤级实施说明  
编写日期: 2026-08-10  
对应总方案: `docs/mindcli-tool-registry-structure-refactor.md` Step 2

## 1. 本步骤目标

把 `ToolRegistry` 中低风险内置工具的“注册声明”迁移到 `tool/builtin/*Registrar`，让 `ToolRegistry` 构造函数只负责调用 registrar，不再直接承载这些工具族的 schema 声明。

迁移工具:

- `read_file`
- `write_file`
- `list_dir`
- `glob_files`
- `grep_code`
- `execute_command`
- `create_project`

## 2. 极简实现策略

本步骤只拆注册职责，不重写执行逻辑:

- `FileToolRegistrar` 负责注册 `read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code`。
- `ShellToolRegistrar` 负责注册 `execute_command`。
- `CodeToolRegistrar` 负责注册 `create_project`。
- `ToolRegistry` 暂时保留文件读写、glob、grep、命令执行和项目创建的实际执行方法，作为 registrar 调用的兼容支撑。

这样可以先降低 `ToolRegistry` 注册职责，同时避免把文件系统、命令执行、LSP hook、observer、ripgrep fallback 等行为在同一步重写。

## 3. 接口调整

在 Step 1 已有 `ToolRegistrationContext` 基础上增加一个极小能力:

```java
ToolRegistry registry();
```

registrar 通过 `context.registry()` 调用 `ToolRegistry` 暴露的低风险工具执行支撑方法。该方法是过渡接口，只服务内置 registrar 拆分，不改变 Agent / CLI / Plan / Multi-Agent 的外部使用方式。

`ToolRegistry` 新增包级或公开支撑方法:

- `readFileTool(Map<String, String> args)`
- `writeFileTool(Map<String, String> args)`
- `listDirTool(Map<String, String> args)`
- `globFilesTool(Map<String, String> args)`
- `grepCodeTool(Map<String, String> args)`
- `executeCommandTool(Map<String, String> args)`
- `createProjectTool(Map<String, String> args)`

命名统一以 `Tool` 结尾，表示它们是内置工具 executor 的薄包装，不是新的业务 API。

## 4. 行为保持要求

- 工具名称不变。
- 工具描述不变。
- JSON schema 字段名、类型、required 不变。
- `write_file` 的 5MB 限制、observer、post-edit LSP hook 不变。
- `glob_files` 的忽略目录、结果上限和输出格式不变。
- `grep_code` 的 ripgrep 优先、partial、`suggested_reads` 行为不变。
- `execute_command` 的 cwd、超时、`CommandGuard`、输出截断不变。
- `create_project` 的 java/python/node 模板输出不变。

## 5. 测试策略

先补结构测试，证明低风险工具仍通过构造函数注册:

```java
assertTrue(registry.hasTool("read_file"));
assertTrue(registry.hasTool("write_file"));
assertTrue(registry.hasTool("list_dir"));
assertTrue(registry.hasTool("glob_files"));
assertTrue(registry.hasTool("grep_code"));
assertTrue(registry.hasTool("execute_command"));
assertTrue(registry.hasTool("create_project"));
```

该测试在迁移前会通过，因此它不是 RED 行为测试；本步骤是无行为变更重构，主要依赖现有 `ToolRegistryTest` 和 `CodeSearchGoldenSetTest` 保护输出行为。

验证命令:

```bash
mvn test "-Dtest=ToolRegistryTest,CodeSearchGoldenSetTest" -DskipTests=false
```

完成本步骤后再运行:

```bash
mvn test "-Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest" -DskipTests=false
```

## 6. 验收标准

1. 新增 3 个 registrar 文件。
2. `ToolRegistry` 构造函数改为通过 registrar 注册低风险工具。
3. 低风险工具原注册方法从 `ToolRegistry` 删除或清空，不再直接承载 schema 声明。
4. 所有指定测试通过。
5. 不修改 ReAct / Plan / Multi-Agent / HITL 外部流程。
