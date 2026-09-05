package com.mindcli.capability.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.capability.tool.ToolOutput;
import com.mindcli.capability.mcp.protocol.McpToolDescriptor;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.ToolExecution;
import com.mindcli.capability.tool.ToolExecutionStatus;
import com.mindcli.capability.tool.namespace.McpToolNamespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolRegistry 的 MCP 工具注册 / 反注册 / 调用路由。
 *
 * MCP 工具在 ToolRegistry 内部由 {@link McpToolNamespace} 持有，executeTool 检测到 mcp__ 前缀后
 * 会路由到注册时提供的 invoker 函数，绕过 Map<String,String> 这个旧入口。
 */
class McpToolRegistrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void namespaceRegistersMcpToolIntoBothDynamicAndVisibleToolViews() throws Exception {
        Map<String, ToolRegistry.Tool> tools = new ConcurrentHashMap<>();
        McpToolNamespace namespace = new McpToolNamespace(tools);

        namespace.registerToolOutput(sampleDescriptor(), args -> ToolOutput.text("ok:" + args));

        assertTrue(namespace.contains("mcp__demo__echo"));
        assertTrue(tools.containsKey("mcp__demo__echo"));
        assertEquals("Echo input (MCP server: demo, tool: echo)", tools.get("mcp__demo__echo").description());
        assertEquals("MCP 工具不应通过 Map<String,String> 入口执行",
                tools.get("mcp__demo__echo").executor().execute(Map.of()));
    }

    @Test
    void registersAndRoutesMcpToolToInvoker(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            McpToolDescriptor descriptor = sampleDescriptor();
            registry.registerMcpTool(descriptor, args -> "echo:" + args);

            assertTrue(registry.hasTool("mcp__demo__echo"));
            assertTrue(registry.getToolDefinitions().stream().anyMatch(t -> t.name().equals("mcp__demo__echo")));
            assertEquals("echo:{\"text\":\"hi\"}", registry.executeTool("mcp__demo__echo", "{\"text\":\"hi\"}"));
        });
    }

    @Test
    void structuredMcpOutputSurvivesRegistryExecution(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            registry.registerMcpToolOutput(sampleDescriptor(), args -> new ToolOutput(
                    "screenshot",
                    List.of(LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png"))));

            ToolExecution execution = registry.executeToolExecution("mcp__demo__echo", "{}");

            assertEquals("screenshot", execution.output().text());
            assertTrue(execution.output().hasImageParts());
            assertEquals("image/png", execution.output().imageParts().get(0).mimeType());
        });
    }

    @Test
    void structuredMcpErrorSurvivesRegistryRouting(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            registry.registerMcpToolExecution(sampleDescriptor(), args -> ToolExecution.failed(
                    ToolOutput.text("MCP 工具返回错误: upstream rejected"),
                    args,
                    "upstream rejected",
                    "MCP_TOOL_ERROR"));

            ToolExecution execution = registry.executeToolExecution("mcp__demo__echo", "{}");

            assertEquals(ToolExecutionStatus.FAILED, execution.status());
            assertEquals("MCP_TOOL_ERROR", execution.errorCategory());
            assertTrue(execution.output().text().contains("upstream rejected"));
        });
    }

    @Test
    void unregisterRemovesMcpToolFromBothViews(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            McpToolDescriptor descriptor = sampleDescriptor();
            registry.registerMcpTool(descriptor, args -> "echo:" + args);
            registry.unregisterMcpTool("mcp__demo__echo");

            assertFalse(registry.hasTool("mcp__demo__echo"));
            assertTrue(registry.getToolDefinitions().stream().noneMatch(t -> t.name().equals("mcp__demo__echo")));
        });
    }

    @Test
    void invokerExceptionsAreReportedAsToolErrorWithoutCrashingRegistry(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            registry.registerMcpTool(sampleDescriptor(), args -> {
                throw new RuntimeException("upstream broke");
            });

            String result = registry.executeTool("mcp__demo__echo", "{}");
            assertTrue(result.contains("upstream broke"), "结果应包含 invoker 抛出的错误信息: " + result);
        });
    }

    @Test
    void registerMcpToolRejectsNullArgs(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            assertThrows(NullPointerException.class,
                    () -> registry.registerMcpTool(null, args -> "x"));
            assertThrows(NullPointerException.class,
                    () -> registry.registerMcpTool(sampleDescriptor(), null));
        });
    }

    @Test
    void replaceMcpToolsForServerAtomicallyReplacesOnlyThatServer(@TempDir Path tempDir) throws Exception {
        withAuditDir(tempDir, () -> {
            ToolRegistry registry = new ToolRegistry();
            registry.registerMcpTool(sampleDescriptor("demo", "old"), args -> "old");
            registry.registerMcpTool(sampleDescriptor("other", "keep"), args -> "keep");

            registry.replaceMcpToolsForServer("demo",
                    List.of(sampleDescriptor("demo", "new")),
                    descriptor -> args -> "new:" + descriptor.name());

            assertFalse(registry.hasTool("mcp__demo__old"));
            assertTrue(registry.hasTool("mcp__demo__new"));
            assertTrue(registry.hasTool("mcp__other__keep"));
            assertEquals("new:new", registry.executeTool("mcp__demo__new", "{}"));
        });
    }

    private static McpToolDescriptor sampleDescriptor() throws Exception {
        return sampleDescriptor("demo", "echo");
    }

    private static McpToolDescriptor sampleDescriptor(String server, String name) throws Exception {
        return new McpToolDescriptor(
                server,
                name,
                "mcp__" + server + "__" + name,
                "Echo input",
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
        );
    }

    private static void withAuditDir(Path tempDir, ThrowingRunnable body) throws Exception {
        String previous = System.getProperty("mindcli.audit.dir");
        System.setProperty("mindcli.audit.dir", tempDir.resolve("audit").toString());
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty("mindcli.audit.dir");
            } else {
                System.setProperty("mindcli.audit.dir", previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
