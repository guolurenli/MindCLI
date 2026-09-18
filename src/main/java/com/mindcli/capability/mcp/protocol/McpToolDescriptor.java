package com.mindcli.capability.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpSchema;

public record McpToolDescriptor(
        String serverName,
        String name,
        String namespacedName,
        String description,
        JsonNode inputSchema,
        McpSchema.ToolAnnotations annotations
) {
    public McpToolDescriptor(String serverName,
                             String name,
                             String namespacedName,
                             String description,
                             JsonNode inputSchema) {
        this(serverName, name, namespacedName, description, inputSchema, null);
    }

    public boolean isReadOnlyForScheduling() {
        return annotations != null
                && Boolean.TRUE.equals(annotations.readOnlyHint())
                && !Boolean.TRUE.equals(annotations.destructiveHint());
    }

    public static String namespaced(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }
}
