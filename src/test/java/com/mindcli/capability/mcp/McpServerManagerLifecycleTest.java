package com.mindcli.capability.mcp;

import com.mindcli.capability.mcp.config.McpConfigLoader;
import com.mindcli.capability.mcp.config.McpServerConfig;
import com.mindcli.capability.mcp.protocol.McpToolDescriptor;
import com.mindcli.capability.mcp.resources.McpResourceDescriptor;
import com.mindcli.capability.mcp.resources.McpResourceCache;
import com.mindcli.capability.tool.ToolRegistry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerManagerLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void reloadingConfigurationCleansUpOldToolsAndResources() throws Exception {
        Path userConfig = tempDir.resolve("user.json");
        Path projectConfig = tempDir.resolve("project.json");
        Files.writeString(userConfig, "{\"mcpServers\":{}}");
        Files.writeString(projectConfig, "{\"mcpServers\":{}}");

        ToolRegistry registry = new ToolRegistry();
        McpServerManager manager = new McpServerManager(registry, tempDir,
                new McpConfigLoader(userConfig, projectConfig, tempDir));
        McpToolDescriptor tool = descriptor("old", "echo");
        registry.registerMcpTool(tool, ignored -> "old");
        McpServer oldServer = new McpServer("old", new McpServerConfig());
        oldServer.tools(List.of(tool));
        putServer(manager, oldServer);
        resourceCache(manager).put("old", List.of(resource("old")));

        manager.loadConfiguredServers();

        assertFalse(registry.hasTool(tool.namespacedName()));
        assertTrue(manager.resourceCandidates().isEmpty());
        assertTrue(manager.servers().isEmpty());
    }

    @Test
    void disablingServerRemovesItsResourceIndex() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        McpServerManager manager = new McpServerManager(registry, tempDir,
                new McpConfigLoader(tempDir.resolve("user.json"), tempDir.resolve("project.json"), tempDir));
        McpServer server = new McpServer("demo", new McpServerConfig());
        putServer(manager, server);
        resourceCache(manager).put("demo", List.of(resource("demo")));

        manager.disable("demo");

        assertTrue(manager.resourceCandidates().isEmpty());
    }

    private static McpToolDescriptor descriptor(String server, String name) {
        return new McpToolDescriptor(server, name, McpToolDescriptor.namespaced(server, name),
                "test", JsonNodeFactory.instance.objectNode());
    }

    private static McpResourceDescriptor resource(String server) {
        return new McpResourceDescriptor(server, "file://README.md", "README.md", "", "", "text/plain", null);
    }

    @SuppressWarnings("unchecked")
    private static void putServer(McpServerManager manager, McpServer server) throws Exception {
        Field field = McpServerManager.class.getDeclaredField("servers");
        field.setAccessible(true);
        ((Map<String, McpServer>) field.get(manager)).put(server.name(), server);
    }

    private static McpResourceCache resourceCache(McpServerManager manager) throws Exception {
        Field field = McpServerManager.class.getDeclaredField("resourceCache");
        field.setAccessible(true);
        return (McpResourceCache) field.get(manager);
    }
}
