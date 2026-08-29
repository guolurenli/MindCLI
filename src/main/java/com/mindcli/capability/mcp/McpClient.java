package com.mindcli.capability.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindcli.capability.mcp.jsonrpc.JsonRpcClient;
import com.mindcli.capability.mcp.jsonrpc.JsonRpcException;
import com.mindcli.capability.mcp.protocol.McpCallToolRequest;
import com.mindcli.capability.mcp.protocol.McpCallToolResult;
import com.mindcli.capability.mcp.protocol.McpContent;
import com.mindcli.capability.mcp.protocol.McpInitializeRequest;
import com.mindcli.capability.mcp.protocol.McpSchemaSanitizer;
import com.mindcli.capability.mcp.protocol.McpToolDescriptor;
import com.mindcli.capability.mcp.resources.McpResourceContent;
import com.mindcli.capability.mcp.resources.McpResourceDescriptor;
import com.mindcli.capability.mcp.transport.McpTransport;
import com.mindcli.capability.tool.ToolOutput;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class McpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_INITIALIZE_TIMEOUT_SECONDS = 60;
    private static final String INITIALIZE_TIMEOUT_PROPERTY = "mindcli.mcp.initialize.timeout.seconds";
    private static final String INITIALIZE_TIMEOUT_ENV = "MINDCLI_MCP_INITIALIZE_TIMEOUT_SECONDS";

    private final String serverName;
    private final JsonRpcClient rpc;
    private final McpTransport transport;
    private final McpSyncClient officialClient;
    private final McpClientTransport officialTransport;
    private final Supplier<List<String>> officialStderr;
    private volatile JsonNode serverCapabilities = JsonNodeFactory.instance.objectNode();

    /**
     * Legacy constructor retained for compatibility with embedders that provide
     * an in-memory transport. Production server startup uses the official SDK
     * constructor below via {@link McpServerManager#createOfficialClient(McpServer)}.
     */
    @Deprecated(forRemoval = false)
    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(transport);
        this.officialClient = null;
        this.officialTransport = null;
        this.officialStderr = List::of;
    }

    public McpClient(String serverName, McpSyncClient officialClient,
                     McpClientTransport officialTransport, Supplier<List<String>> officialStderr) {
        this.serverName = serverName;
        this.rpc = null;
        this.transport = null;
        this.officialClient = officialClient;
        this.officialTransport = officialTransport;
        this.officialStderr = officialStderr == null ? List::of : officialStderr;
    }

    public void initialize() throws IOException {
        if (officialClient != null) {
            try {
                officialClient.initialize();
                return;
            } catch (RuntimeException e) {
                throw asIoException(e);
            }
        }
        JsonNode result = rpc.request("initialize", McpInitializeRequest.toJson(), initializeTimeoutSeconds());
        serverCapabilities = result == null ? JsonNodeFactory.instance.objectNode() : result.path("capabilities");
        rpc.sendNotification("notifications/initialized", JsonNodeFactory.instance.objectNode());
    }

    static int initializeTimeoutSeconds() {
        String configured = System.getProperty(INITIALIZE_TIMEOUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(INITIALIZE_TIMEOUT_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(configured.trim());
            return seconds > 0 ? seconds : DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
    }

    public boolean supportsResources() {
        if (officialClient != null) {
            McpSchema.ServerCapabilities capabilities = officialClient.getServerCapabilities();
            return capabilities != null && capabilities.resources() != null;
        }
        return serverCapabilities.has("resources");
    }

    public boolean supportsPrompts() {
        if (officialClient != null) {
            McpSchema.ServerCapabilities capabilities = officialClient.getServerCapabilities();
            return capabilities != null && capabilities.prompts() != null;
        }
        return serverCapabilities.has("prompts");
    }

    public List<McpToolDescriptor> listTools() throws IOException {
        if (officialClient != null) {
            try {
                McpSchema.ListToolsResult result = officialClient.listTools();
                if (result == null || result.tools() == null) return List.of();
                return result.tools().stream()
                        .filter(tool -> tool != null && tool.name() != null && !tool.name().isBlank())
                        .map(tool -> new McpToolDescriptor(serverName, tool.name(),
                                McpToolDescriptor.namespaced(serverName, tool.name()),
                                tool.description() == null ? "" : tool.description(),
                                sanitizeSchema(tool.inputSchema())))
                        .toList();
            } catch (RuntimeException e) {
                throw asIoException(e);
            }
        }
        JsonNode result = rpc.request("tools/list", JsonNodeFactory.instance.objectNode(), 30);
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String description = tool.path("description").asText("");
            JsonNode schema = McpSchemaSanitizer.sanitize(tool.path("inputSchema"));
            descriptors.add(new McpToolDescriptor(
                    serverName,
                    name,
                    McpToolDescriptor.namespaced(serverName, name),
                    description,
                    schema
            ));
        }
        return descriptors;
    }

    public String callTool(String toolName, String argumentsJson) throws IOException {
        return callToolOutput(toolName, argumentsJson).text();
    }

    public ToolOutput callToolOutput(String toolName, String argumentsJson) throws IOException {
        if (officialClient != null) {
            try {
                Map<String, Object> arguments = argumentsJson == null || argumentsJson.isBlank()
                        ? java.util.Map.of() : MAPPER.readValue(argumentsJson, java.util.Map.class);
                McpSchema.CallToolResult result = officialClient.callTool(
                        new McpSchema.CallToolRequest(toolName, arguments));
                ToolOutput output = toToolOutput(result);
                if (Boolean.TRUE.equals(result.isError())) {
                    return new ToolOutput("MCP 工具返回错误: " + output.text(), output.imageParts());
                }
                return output;
            } catch (RuntimeException e) {
                throw asIoException(e);
            }
        }
        JsonNode args;
        if (argumentsJson == null || argumentsJson.isBlank()) {
            args = JsonNodeFactory.instance.objectNode();
        } else {
            args = MAPPER.readTree(argumentsJson);
        }
        ObjectNode params = McpCallToolRequest.toJson(toolName, args);
        JsonNode result = rpc.request("tools/call", params, 60);
        McpCallToolResult callResult = MAPPER.treeToValue(result, McpCallToolResult.class);
        ToolOutput output = callResult.toToolOutput();
        if (callResult.isError()) {
            return new ToolOutput("MCP 工具返回错误: " + output.text(), output.imageParts());
        }
        return output;
    }

    public List<McpResourceDescriptor> listResources() throws IOException {
        if (officialClient != null) {
            try {
                McpSchema.ListResourcesResult result = officialClient.listResources();
                if (result == null || result.resources() == null) return List.of();
                return result.resources().stream()
                        .filter(resource -> resource != null && resource.uri() != null && !resource.uri().isBlank())
                        .map(resource -> new McpResourceDescriptor(serverName, resource.uri(), resource.name(),
                                resource.title(), resource.description(), resource.mimeType(), resource.size()))
                        .toList();
            } catch (RuntimeException e) {
                if (isMethodNotFound(e)) return List.of();
                throw asIoException(e);
            }
        }
        try {
            JsonNode result = rpc.request("resources/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode resources = result.path("resources");
            if (!resources.isArray()) {
                return List.of();
            }
            List<McpResourceDescriptor> descriptors = new ArrayList<>();
            for (JsonNode resource : resources) {
                McpResourceDescriptor descriptor = McpResourceDescriptor.fromJson(serverName, resource);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            }
            return descriptors;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {
                return List.of();
            }
            throw e;
        }
    }

    public List<McpResourceContent> readResource(String uri) throws IOException {
        if (officialClient != null) {
            try {
                McpSchema.ReadResourceResult result = officialClient.readResource(new McpSchema.ReadResourceRequest(uri));
                if (result == null || result.contents() == null) return List.of();
                return result.contents().stream().map(content -> {
                    if (content instanceof McpSchema.TextResourceContents text) {
                        return new McpResourceContent(text.uri(), text.mimeType(), text.text(), null);
                    }
                    if (content instanceof McpSchema.BlobResourceContents blob) {
                        return new McpResourceContent(blob.uri(), blob.mimeType(), null, blob.blob());
                    }
                    return new McpResourceContent(content.uri(), content.mimeType(), null, null);
                }).toList();
            } catch (RuntimeException e) {
                throw asIoException(e);
            }
        }
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        JsonNode result = rpc.request("resources/read", params, 60);
        JsonNode contents = result.path("contents");
        if (!contents.isArray()) {
            return List.of();
        }
        List<McpResourceContent> resourceContents = new ArrayList<>();
        for (JsonNode content : contents) {
            McpResourceContent resourceContent = McpResourceContent.fromJson(content);
            if (resourceContent != null) {
                resourceContents.add(resourceContent);
            }
        }
        return resourceContents;
    }

    public void subscribeResource(String uri) throws IOException {
        if (officialClient != null) {
            try {
                officialClient.subscribeResource(new McpSchema.SubscribeRequest(uri));
                return;
            } catch (RuntimeException e) {
                throw asIoException(e);
            }
        }
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        rpc.request("resources/subscribe", params, 30);
    }

    public List<String> listPrompts() throws IOException {
        if (officialClient != null) {
            try {
                McpSchema.ListPromptsResult result = officialClient.listPrompts();
                if (result == null || result.prompts() == null) return List.of();
                return result.prompts().stream().filter(prompt -> prompt != null && prompt.name() != null && !prompt.name().isBlank())
                        .map(prompt -> {
                            String display = prompt.title() == null || prompt.title().isBlank()
                                    ? prompt.name() : prompt.title() + " (" + prompt.name() + ")";
                            return prompt.description() == null || prompt.description().isBlank()
                                    ? display : display + " - " + prompt.description();
                        }).toList();
            } catch (RuntimeException e) {
                if (isMethodNotFound(e)) return List.of();
                throw asIoException(e);
            }
        }
        try {
            JsonNode result = rpc.request("prompts/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode prompts = result.path("prompts");
            if (!prompts.isArray()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode prompt : prompts) {
                String name = prompt.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String title = prompt.path("title").asText("");
                String description = prompt.path("description").asText("");
                String display = title.isBlank() ? name : title + " (" + name + ")";
                lines.add(description.isBlank() ? display : display + " - " + description);
            }
            return lines;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {
                return List.of();
            }
            throw e;
        }
    }

    public void onNotification(Consumer<JsonNode> listener) {
        if (rpc != null) rpc.onNotification(listener);
    }

    public static String formatResources(List<McpResourceDescriptor> resources) {
        if (resources == null || resources.isEmpty()) {
            return "📭 该 MCP server 暂无 resources";
        }
        StringBuilder sb = new StringBuilder("📚 MCP resources（").append(resources.size()).append("）\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- ").append(resource.uri());
            String name = resource.displayName();
            if (name != null && !name.isBlank() && !name.equals(resource.uri())) {
                sb.append(" | ").append(name);
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" | ").append(resource.mimeType());
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("\n  ").append(resource.description());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public static String formatResourceContents(List<McpResourceContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return "📭 MCP resource 内容为空";
        }
        StringBuilder sb = new StringBuilder();
        for (McpResourceContent content : contents) {
            String mimeType = content.mimeType() == null || content.mimeType().isBlank()
                    ? "application/octet-stream"
                    : content.mimeType();
            sb.append("<resource uri=\"").append(escapeXml(content.uri()))
                    .append("\" mimeType=\"").append(escapeXml(mimeType)).append("\">\n");
            if (content.isText()) {
                sb.append(content.text());
            } else {
                sb.append("[binary resource blob omitted, base64 length=")
                        .append(content.blob() == null ? 0 : content.blob().length())
                        .append(']');
            }
            sb.append("\n</resource>\n");
        }
        return sb.toString().trim();
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public List<String> stderrLines() {
        return transport != null ? transport.stderrLines() : List.copyOf(officialStderr.get());
    }

    public Long processId() {
        return transport == null ? null : transport.processId();
    }

    public String transportName() {
        if (transport != null) return transport.transportName();
        return officialTransport == null ? "unknown" : officialTransport.getClass().getSimpleName().contains("Stdio") ? "stdio" : "http";
    }

    @Override
    public void close() {
        // 直接走 transport-level 关闭信号：stdio 通过 stdin EOF + 进程销毁；HTTP 通过 DELETE session。
        // 之前会先发 shutdown notification，但当 server 卡死 / 队列堵塞时这条通知会让 close 阻塞 60 秒。
        // 移除后退出更快、行为更可预期；shutdown 语义改由 transport 层承担。
        if (officialClient != null) {
            officialClient.close();
        } else {
            rpc.close();
        }
    }

    private static JsonNode sanitizeSchema(Map<String, Object> schema) {
        return McpSchemaSanitizer.sanitize(MAPPER.valueToTree(schema == null ? java.util.Map.of() : schema));
    }

    private static ToolOutput toToolOutput(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return ToolOutput.text(Boolean.TRUE.equals(result == null ? null : result.isError())
                    ? "MCP 工具返回错误，但没有错误正文" : "");
        }
        List<McpContent> content = result.content().stream().map(item -> {
            if (item instanceof McpSchema.TextContent text) return new McpContent("text", text.text(), null, null);
            if (item instanceof McpSchema.ImageContent image) return new McpContent("image", null, image.data(), image.mimeType());
            return new McpContent(item.type(), null, null, null);
        }).toList();
        return new McpCallToolResult(content, Boolean.TRUE.equals(result.isError())).toToolOutput();
    }

    private static IOException asIoException(RuntimeException exception) {
        Throwable cause = exception.getCause();
        String message = exception.getMessage();
        if (cause != null && (message == null || message.isBlank())) message = cause.getMessage();
        return new IOException(message == null ? exception.getClass().getSimpleName() : message, exception);
    }

    private static boolean isMethodNotFound(RuntimeException exception) {
        String message = exception.getMessage();
        return message != null && (message.contains("-32601") || message.toLowerCase().contains("method not found"));
    }
}
