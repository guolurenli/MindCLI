package com.mindcli.capability.tool.namespace;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindcli.capability.mcp.protocol.McpToolDescriptor;
import com.mindcli.capability.tool.ToolOutput;
import com.mindcli.capability.tool.ToolRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class McpToolNamespace {
    private final Map<String, ToolRegistry.Tool> visibleTools;
    private final Map<String, RegisteredTool> registeredTools = new ConcurrentHashMap<>();

    public McpToolNamespace(Map<String, ToolRegistry.Tool> visibleTools) {
        this.visibleTools = Objects.requireNonNull(visibleTools, "visibleTools");
    }

    public synchronized void registerToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        String toolName = descriptor.namespacedName();
        registeredTools.put(toolName, new RegisteredTool(descriptor, invoker));
        visibleTools.put(toolName, new ToolRegistry.Tool(
                toolName,
                description(descriptor),
                descriptor.inputSchema(),
                args -> "MCP 工具不应通过 Map<String,String> 入口执行"
        ));
    }

    public synchronized void unregisterTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        registeredTools.remove(toolName);
        visibleTools.remove(toolName);
    }

    public synchronized void replaceToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                         Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(newTools, "newTools");
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        String prefix = "mcp__" + serverName + "__";
        List<String> existing = registeredTools.keySet().stream()
                .filter(name -> name.startsWith(prefix))
                .toList();
        for (String toolName : existing) {
            registeredTools.remove(toolName);
            visibleTools.remove(toolName);
        }
        for (McpToolDescriptor descriptor : newTools) {
            registerToolOutput(descriptor, invokerFactory.apply(descriptor));
        }
    }

    public RegisteredTool get(String toolName) {
        return registeredTools.get(toolName);
    }

    public boolean contains(String toolName) {
        return registeredTools.containsKey(toolName);
    }

    public JsonNode inputSchema(String toolName) {
        RegisteredTool tool = registeredTools.get(toolName);
        return tool == null ? null : tool.descriptor().inputSchema();
    }

    private static String description(McpToolDescriptor descriptor) {
        String base = descriptor.description() == null || descriptor.description().isBlank()
                ? "MCP server 提供的外部工具"
                : descriptor.description();
        return base + " (MCP server: " + descriptor.serverName() + ", tool: " + descriptor.name() + ")";
    }

    public record RegisteredTool(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {}
}
