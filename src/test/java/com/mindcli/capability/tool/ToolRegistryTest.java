package com.mindcli.capability.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.capability.browser.BrowserConnector;
import com.mindcli.capability.memory.MemoryWriteResult;
import com.mindcli.capability.mcp.protocol.McpToolDescriptor;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.platform.llm.context.ContextProfile;
import com.mindcli.capability.tool.builtin.BrowserToolRegistrar;
import com.mindcli.capability.tool.builtin.CodeToolRegistrar;
import com.mindcli.capability.tool.builtin.FileToolRegistrar;
import com.mindcli.capability.tool.builtin.MemoryToolRegistrar;
import com.mindcli.capability.tool.builtin.ShellToolRegistrar;
import com.mindcli.capability.tool.builtin.SkillToolRegistrar;
import com.mindcli.capability.tool.builtin.SnapshotToolRegistrar;
import com.mindcli.capability.tool.builtin.WebToolRegistrar;
import com.mindcli.capability.tool.registry.ToolRegistrar;
import com.mindcli.capability.tool.registry.ToolRegistrationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ToolRegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldRegisterToolThroughRegistrar() {
        ToolRegistry registry = new ToolRegistry();

        registry.registerTools(context -> context.register(new ToolRegistry.Tool(
                "test_echo",
                "test tool",
                context.parameters(new ToolRegistrationContext.Parameter("value", "string", "value", true)),
                args -> "echo:" + args.get("value")
        )));

        assertTrue(registry.hasTool("test_echo"));
        assertEquals("echo:hello", registry.executeTool("test_echo", "{\"value\":\"hello\"}"));
    }

    @Test
    void registrarEntryPointIsPackagePrivate() throws Exception {
        int modifiers = ToolRegistry.class.getDeclaredMethod("registerTools", ToolRegistrar.class).getModifiers();

        assertFalse(Modifier.isPublic(modifiers));
    }

    @Test
    void registrarCannotOverwriteExistingTool() {
        ToolRegistry registry = new ToolRegistry();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.registerTools(context -> context.register(new ToolRegistry.Tool(
                        "read_file",
                        "override",
                        context.parameters(),
                        args -> "override"
                ))));

        assertTrue(error.getMessage().contains("工具已注册"));
    }

    @Test
    void registrarCannotRegisterReservedMcpToolName() {
        ToolRegistry registry = new ToolRegistry();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.registerTools(context -> context.register(new ToolRegistry.Tool(
                        "mcp__demo__echo",
                        "fake mcp",
                        context.parameters(),
                        args -> "fake"
                ))));

        assertTrue(error.getMessage().contains("mcp__"));
    }

    @Test
    void directToolExecutorsArePackagePrivate() throws Exception {
        for (String method : List.of(
                "readFileTool",
                "writeFileTool",
                "listDirTool",
                "globFilesTool",
                "grepCodeTool",
                "executeCommandTool",
                "createProjectTool",
                "webSearchTool",
                "webFetchTool",
                "browserConnectTool",
                "browserDisconnectTool",
                "browserStatusTool",
                "loadSkillTool",
                "saveMemoryTool",
                "searchMemoryTool",
                "readMemoryTool",
                "revertTurnTool")) {
            int modifiers = ToolRegistry.class.getDeclaredMethod(method, Map.class).getModifiers();

            assertFalse(Modifier.isPublic(modifiers), method);
        }
    }

    @Test
    void shouldKeepLowRiskToolsInDedicatedRegistrars() {
        assertInstanceOf(ToolRegistrar.class, new FileToolRegistrar());
        assertInstanceOf(ToolRegistrar.class, new ShellToolRegistrar());
        assertInstanceOf(ToolRegistrar.class, new CodeToolRegistrar());

        ToolRegistry registry = new ToolRegistry();

        assertTrue(registry.hasTool("read_file"));
        assertTrue(registry.hasTool("write_file"));
        assertTrue(registry.hasTool("list_dir"));
        assertTrue(registry.hasTool("glob_files"));
        assertTrue(registry.hasTool("grep_code"));
        assertTrue(registry.hasTool("execute_command"));
        assertTrue(registry.hasTool("create_project"));
    }

    @Test
    void shouldKeepRemainingBuiltinToolsInDedicatedRegistrars() {
        assertInstanceOf(ToolRegistrar.class, new WebToolRegistrar());
        assertInstanceOf(ToolRegistrar.class, new BrowserToolRegistrar());
        assertInstanceOf(ToolRegistrar.class, new SkillToolRegistrar());
        assertInstanceOf(ToolRegistrar.class, new MemoryToolRegistrar());
        assertInstanceOf(ToolRegistrar.class, new SnapshotToolRegistrar());

        ToolRegistry registry = new ToolRegistry();

        assertFalse(registry.hasTool("search_code"));
        assertTrue(registry.hasTool("web_search"));
        assertTrue(registry.hasTool("web_fetch"));
        assertTrue(registry.hasTool("browser_connect"));
        assertTrue(registry.hasTool("browser_disconnect"));
        assertTrue(registry.hasTool("browser_status"));
        assertTrue(registry.hasTool("load_skill"));
        assertTrue(registry.hasTool("save_memory"));
        assertTrue(registry.hasTool("search_memory"));
        assertTrue(registry.hasTool("read_memory"));
        assertTrue(registry.hasTool("revert_turn"));
    }

    @Test
    void shouldRunCommandInProjectDirectory(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"pwd\"}");

        assertTrue(result.contains(tempDir.toString()));
    }

    @Test
    void shouldRejectBroadFilesystemScan() {
        ToolRegistry registry = new ToolRegistry();

        ToolExecution execution = registry.executeToolExecution(
                "execute_command", "{\"command\":\"find / -name \\\"pom.xml\\\" -type f | head -20\"}");

        assertEquals(ToolExecutionStatus.DENIED_BY_POLICY, execution.status());
        assertTrue(execution.output().text().contains("策略拒绝"));
        assertFalse(execution.errorMessage().isBlank());
    }

    @Test
    void unknownToolReturnsStructuredFailure() {
        ToolRegistry registry = new ToolRegistry();

        ToolExecution execution = registry.executeToolExecution("missing_tool", "{}");

        assertEquals(ToolExecutionStatus.FAILED, execution.status());
        assertEquals("未知工具: missing_tool", execution.output().text());
        assertEquals("UNKNOWN_TOOL", execution.errorCategory());
    }

    @Test
    void shouldReadRequestedLineRange(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, String.join("\n",
                "class Sample {",
                "  void first() {}",
                "  void second() {}",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"Sample.java\",\"offset\":2,\"limit\":2}");

        assertTrue(result.contains("lines 2-3 of 4"));
        assertTrue(result.contains("2 |   void first() {}"));
        assertTrue(result.contains("3 |   void second() {}"));
        assertTrue(!result.contains("class Sample {"));
    }

    @Test
    void shouldGlobFilesInsideProject(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), "class UserService {}\n");
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"**/*Service.java\"}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java"));
        assertTrue(!result.contains("README.md"));
    }

    @Test
    void shouldGlobRootFileByName(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"README.md\"}");

        assertTrue(result.contains("README.md"));
    }

    @Test
    void shouldGrepCodeWithLineNumbersAndContext(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), String.join("\n",
                "class UserService {",
                "  User getUserById(String id) {",
                "    return repository.findById(id);",
                "  }",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code",
                "{\"pattern\":\"getUserById\",\"glob\":\"**/*.java\",\"context_lines\":1}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java:2"));
        assertTrue(result.contains(">    2 |   User getUserById(String id) {"));
        assertTrue(result.contains("     3 |     return repository.findById(id);"));
    }

    @Test
    void shouldSkipCommonDependencyDirectoriesWhenGrepping(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("src/App.java"), "class App { String marker = \"targetSymbol\"; }\n");
        Files.writeString(tempDir.resolve("node_modules/pkg/Generated.java"), "class Generated { String marker = \"targetSymbol\"; }\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code", "{\"pattern\":\"targetSymbol\",\"max_results\":10}");

        assertTrue(result.contains("src/App.java:1"));
        assertTrue(!result.contains("node_modules"));
    }

    @Test
    void shouldExposePartialWhenGrepReachesHeadLimit(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("mindcli.search.disable.rg");
        System.setProperty("mindcli.search.disable.rg", "true");
        try {
            Files.writeString(tempDir.resolve("Many.java"), String.join("\n",
                    "class Many {",
                    "  String first = \"needle\";",
                    "  String second = \"needle\";",
                    "}"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            ToolExecution execution = registry.executeToolExecution("grep_code",
                    "{\"pattern\":\"needle\",\"head_limit\":1,\"max_results\":10}");
            String result = execution.output().text();

            assertEquals(ToolExecutionStatus.PARTIAL, execution.status());
            assertTrue(result.contains("Many.java:2"));
            assertTrue(!result.contains("Many.java:3"));
            assertTrue(result.contains("partial: true"));
            assertTrue(result.contains("head_limit=1"));
            assertTrue(result.contains("suggested_reads"));
            assertTrue(result.contains("read_file {\"path\":\"Many.java\""));
        } finally {
            restoreSystemProperty("mindcli.search.disable.rg", previous);
        }
    }

    @Test
    void shouldExposePartialWhenGrepResultReachesCharacterBudget(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("mindcli.search.disable.rg");
        System.setProperty("mindcli.search.disable.rg", "true");
        try {
            String longNeedleLine = "needle " + "x".repeat(1200);
            Files.writeString(tempDir.resolve("Budget.java"), String.join("\n",
                    "class Budget {",
                    "  String first = \"" + longNeedleLine + "\";",
                    "  String second = \"" + longNeedleLine + "\";",
                    "}"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("grep_code",
                    "{\"pattern\":\"needle\",\"max_results\":10,\"max_chars\":1000}");

            assertTrue(result.contains("Budget.java:2"));
            assertTrue(result.contains("partial: true"));
            assertTrue(result.contains("max_chars=1000"));
        } finally {
            restoreSystemProperty("mindcli.search.disable.rg", previous);
        }
    }

    @Test
    void shouldTimeoutLongRunningCommandWithoutHanging(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(1);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"sleep 2\"}");

        assertTrue(result.contains("命令执行超时"));
    }

    @Test
    void shouldRouteWebSearchThroughStepSearchMcpForStep37Flash() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.7-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_search", """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string"},
                    "top_k": {"type": "integer"}
                  }
                }
                """), args -> "step-result:" + args);

        String result = registry.executeTool("web_search", "{\"query\":\"Step 3.7 Flash\",\"top_k\":3}");

        assertTrue(result.contains("[StepSearch]"));
        assertTrue(result.contains("step-result"));
        assertTrue(result.contains("\"query\":\"Step 3.7 Flash\""));
        assertTrue(result.contains("\"top_k\":3"));
    }

    @Test
    void shouldRouteWebFetchThroughStepSearchMcpForStep37Flash() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.7-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_fetch", """
                {
                  "type": "object",
                  "properties": {
                    "url": {"type": "string"},
                    "max_chars": {"type": "integer"}
                  }
                }
                """), args -> "step-fetch:" + args);

        String result = registry.executeTool("web_fetch",
                "{\"url\":\"https://platform.stepfun.com/docs/zh/step-plan/integrations/search-mcp\",\"max_chars\":1200}");

        assertTrue(result.contains("[StepSearch]"));
        assertTrue(result.contains("step-fetch"));
        assertTrue(result.contains("\"url\":\"https://platform.stepfun.com/docs/zh/step-plan/integrations/search-mcp\""));
        assertTrue(result.contains("\"max_chars\":1200"));
    }

    @Test
    void shouldNotRouteStepSearchForOlderStepModel() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.5-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_search", """
                {"type": "object", "properties": {"query": {"type": "string"}}}
                """), args -> "step-result:" + args);

        String result = registry.executeTool("web_search", "{\"query\":\"Step 3.7 Flash\"}");

        assertFalse(result.contains("step-result"));
    }

    private static McpToolDescriptor stepSearchDescriptor(String name, String schema) throws Exception {
        JsonNode inputSchema = MAPPER.readTree(schema);
        return new McpToolDescriptor(
                "step_search",
                name,
                "mcp__step_search__" + name,
                "StepSearch " + name,
                inputSchema);
    }

    @Test
    void browserConnectToolUsesInjectedConnector() {
        ToolRegistry registry = new ToolRegistry();
        registry.setBrowserConnector(new BrowserConnector() {
            @Override
            public String status() {
                return "status-ok";
            }

            @Override
            public String connectDefault() {
                return "connected";
            }

            @Override
            public String disconnect() {
                return "disconnected";
            }
        });

        assertEquals("connected", registry.executeTool("browser_connect", "{}"));
        assertEquals("status-ok", registry.executeTool("browser_status", "{}"));
        assertEquals("disconnected", registry.executeTool("browser_disconnect", "{}"));
    }

    @Test
    void saveMemoryToolUsesInjectedMemorySaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setMemorySaver(saved::add);

        String result = registry.executeTool("save_memory", "{\"fact\":\"访问 yuque.com 时复用登录态\"}");

        assertEquals(List.of("访问 yuque.com 时复用登录态"), saved);
        assertTrue(result.contains("已保存到长期记忆"));
    }

    @Test
    void saveMemoryToolPassesScopeToScopedSaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setScopedMemorySaver((fact, scope) -> saved.add(scope + ":" + fact));

        String result = registry.executeTool("save_memory", "{\"fact\":\"默认用中文回答\",\"scope\":\"global\"}");

        assertEquals(List.of("global:默认用中文回答"), saved);
        assertTrue(result.contains("长期记忆(global)"));
    }

    @Test
    void saveMemoryToolReturnsPolicyAwareWriterResult() {
        ToolRegistry registry = new ToolRegistry();
        registry.setScopedMemoryWriter((fact, scope) -> MemoryWriteResult.proposed(
                null,
                "memory.global.approval",
                "已生成待确认候选记忆(global): proposal-1234，可用 /memory approve proposal-1234 批准"));

        String result = registry.executeTool("save_memory", "{\"fact\":\"默认用中文回答\",\"scope\":\"global\"}");

        assertEquals("已生成待确认候选记忆(global): proposal-1234，可用 /memory approve proposal-1234 批准", result);
    }

    @Test
    void saveMemoryToolReturnsPolicyDenialResult() {
        ToolRegistry registry = new ToolRegistry();
        registry.setScopedMemoryWriter((fact, scope) -> MemoryWriteResult.denied(
                "memory.sensitive",
                "保存长期记忆被策略拒绝: memory.sensitive - 检测到敏感信息，拒绝写入长期记忆"));

        String result = registry.executeTool("save_memory",
                "{\"fact\":\"API_KEY=sk-1234567890abcdefghijklmnopqrst\"}");

        assertEquals("保存长期记忆被策略拒绝: memory.sensitive - 检测到敏感信息，拒绝写入长期记忆", result);
    }

    @Test
    void memoryReadToolsUseInjectedCallbacks() {
        ToolRegistry registry = new ToolRegistry();
        registry.setMemorySearcher((query, limit) -> "search:" + query + ":" + limit);
        registry.setMemoryReader(id -> "read:" + id);

        assertEquals("search:项目测试命令:3",
                registry.executeTool("search_memory", "{\"query\":\"项目测试命令\",\"limit\":3}"));
        assertEquals("read:fact-a1b2c3d4",
                registry.executeTool("read_memory", "{\"id\":\"fact-a1b2c3d4\"}"));
    }

    @Test
    void forkForProject_redirectsProjectPathAndCopiesSharedConfig() {
        ToolRegistry registry = new ToolRegistry();
        ContextProfile profile = ContextProfile.custom(16_000, 8_000);
        registry.setContextProfile(profile);
        SkillRegistry skills = mock(SkillRegistry.class);
        registry.setSkillRegistry(skills);

        Path worktree = Path.of("target", "worktree-fork-test").toAbsolutePath();
        ToolRegistry fork = registry.forkForProject(worktree);

        assertNotSame(registry, fork, "fork 应是独立实例");
        assertEquals(worktree.toString(), fork.getProjectPath(), "fork 的项目路径应重定向到 worktree");
        assertNotSame(registry.getProjectPath(), fork.getProjectPath());
        assertSame(profile, fork.getContextProfile(), "fork 应共享同一个 ContextProfile 引用");
        assertSame(skills, fork.getSkillRegistry(), "fork 应共享同一个 SkillRegistry 引用");
    }

    @Test
    void forkForProject_doesNotLeakMemoryWriterToFork(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setMemorySaver(saved::add);

        ToolRegistry fork = registry.forkForProject(tempDir);

        // fork 未复制 memory saver，save_memory 在 fork 上不应写入主注册表的 saver
        fork.executeTool("save_memory", "{\"fact\":\"来自 worktree 的事实\"}");
        assertTrue(saved.isEmpty(), "worktree fork 不应污染主注册表记忆写入");
    }

    private static void restoreSystemProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
