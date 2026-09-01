package com.mindcli.capability.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.capability.image.ImageProcessor;
import com.mindcli.capability.mcp.protocol.McpSchemaSanitizer;
import com.mindcli.capability.mcp.protocol.McpToolDescriptor;
import com.mindcli.capability.mcp.resources.McpResourceContent;
import com.mindcli.capability.mcp.resources.McpResourceDescriptor;
import com.mindcli.capability.tool.ToolOutput;
import com.mindcli.platform.llm.LlmClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class McpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {
    };
    private static final int DEFAULT_INITIALIZE_TIMEOUT_SECONDS = 60;
    private static final String INITIALIZE_TIMEOUT_PROPERTY = "mindcli.mcp.initialize.timeout.seconds";
    private static final String INITIALIZE_TIMEOUT_ENV = "MINDCLI_MCP_INITIALIZE_TIMEOUT_SECONDS";

    private final String serverName;
    private final McpSyncClient officialClient;
    private final McpClientTransport officialTransport;
    private final Supplier<List<String>> officialStderr;

    public McpClient(String serverName, McpSyncClient officialClient,
                     McpClientTransport officialTransport, Supplier<List<String>> officialStderr) {
        this.serverName = serverName;
        this.officialClient = officialClient;
        this.officialTransport = officialTransport;
        this.officialStderr = officialStderr == null ? List::of : officialStderr;
    }

    public void initialize() throws IOException {
        try {
            officialClient.initialize();
        } catch (RuntimeException e) {
            throw asIoException(e);
        }
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
        McpSchema.ServerCapabilities capabilities = officialClient.getServerCapabilities();
        return capabilities != null && capabilities.resources() != null;
    }

    public boolean supportsPrompts() {
        McpSchema.ServerCapabilities capabilities = officialClient.getServerCapabilities();
        return capabilities != null && capabilities.prompts() != null;
    }

    public List<McpToolDescriptor> listTools() throws IOException {
        try {
            McpSchema.ListToolsResult result = officialClient.listTools();
            if (result == null || result.tools() == null) {
                return List.of();
            }
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

    public String callTool(String toolName, String argumentsJson) throws IOException {
        return callToolOutput(toolName, argumentsJson).text();
    }

    public ToolOutput callToolOutput(String toolName, String argumentsJson) throws IOException {
        try {
            Map<String, Object> arguments = argumentsJson == null || argumentsJson.isBlank()
                    ? Map.of()
                    : MAPPER.readValue(argumentsJson, ARGUMENTS_TYPE);
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

    public List<McpResourceDescriptor> listResources() throws IOException {
        try {
            McpSchema.ListResourcesResult result = officialClient.listResources();
            if (result == null || result.resources() == null) {
                return List.of();
            }
            return result.resources().stream()
                    .filter(resource -> resource != null && resource.uri() != null && !resource.uri().isBlank())
                    .map(resource -> new McpResourceDescriptor(serverName, resource.uri(), resource.name(),
                            resource.title(), resource.description(), resource.mimeType(), resource.size()))
                    .toList();
        } catch (RuntimeException e) {
            if (isMethodNotFound(e)) {
                return List.of();
            }
            throw asIoException(e);
        }
    }

    public List<McpResourceContent> readResource(String uri) throws IOException {
        try {
            McpSchema.ReadResourceResult result = officialClient.readResource(new McpSchema.ReadResourceRequest(uri));
            if (result == null || result.contents() == null) {
                return List.of();
            }
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

    public void subscribeResource(String uri) throws IOException {
        try {
            officialClient.subscribeResource(new McpSchema.SubscribeRequest(uri));
        } catch (RuntimeException e) {
            throw asIoException(e);
        }
    }

    public List<String> listPrompts() throws IOException {
        try {
            McpSchema.ListPromptsResult result = officialClient.listPrompts();
            if (result == null || result.prompts() == null) {
                return List.of();
            }
            return result.prompts().stream()
                    .filter(prompt -> prompt != null && prompt.name() != null && !prompt.name().isBlank())
                    .map(prompt -> {
                        String display = prompt.title() == null || prompt.title().isBlank()
                                ? prompt.name() : prompt.title() + " (" + prompt.name() + ")";
                        return prompt.description() == null || prompt.description().isBlank()
                                ? display : display + " - " + prompt.description();
                    }).toList();
        } catch (RuntimeException e) {
            if (isMethodNotFound(e)) {
                return List.of();
            }
            throw asIoException(e);
        }
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

    public List<String> stderrLines() {
        return List.copyOf(officialStderr.get());
    }

    public Long processId() {
        return null;
    }

    public String transportName() {
        if (officialTransport == null) {
            return "unknown";
        }
        return officialTransport.getClass().getSimpleName().contains("Stdio") ? "stdio" : "http";
    }

    @Override
    public void close() {
        officialClient.close();
    }

    static ToolOutput toToolOutput(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return ToolOutput.text(Boolean.TRUE.equals(result == null ? null : result.isError())
                    ? "MCP 工具返回错误，但没有错误正文" : "");
        }
        List<LlmClient.ContentPart> imageParts = new ArrayList<>();
        String text = result.content().stream()
                .map(item -> {
                    if (item instanceof McpSchema.TextContent textContent) {
                        return textContent.text() == null ? "" : textContent.text();
                    }
                    if (item instanceof McpSchema.ImageContent imageContent) {
                        return formatImage(imageContent, imageParts);
                    }
                    String type = item == null || item.type() == null || item.type().isBlank()
                            ? "unknown" : item.type();
                    return "[此工具返回了 " + type + "，请向用户描述结果]";
                })
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining("\n\n"));
        return new ToolOutput(text, imageParts);
    }

    private static String formatImage(McpSchema.ImageContent image, List<LlmClient.ContentPart> imageParts) {
        String mimeType = image.mimeType() == null || image.mimeType().isBlank()
                ? "image/png"
                : image.mimeType();
        int base64Length = image.data() == null ? 0 : image.data().length();
        boolean hasData = image.data() != null && !image.data().isBlank();
        ImageProcessor.ProcessedImage processed = null;
        String error = null;

        if (hasData) {
            try {
                processed = ImageProcessor.fromBase64(image.data(), mimeType);
                imageParts.add(ImageProcessor.toContentPart(processed));
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }

        StringBuilder fallback = new StringBuilder();
        fallback.append("[此工具返回了 image: mimeType=").append(mimeType)
                .append(", base64Length=").append(base64Length);
        if (!hasData) {
            fallback.append("，图片数据为空，未作为图片附件附加。]");
        } else if (processed == null) {
            fallback.append("，图片处理失败: ").append(error)
                    .append("，未作为图片附件附加；请缩小视口或改用 take_snapshot 获取 DOM 文本快照。]");
        } else {
            String metadataText = ImageProcessor.createMetadataText(processed);
            if (metadataText != null) {
                fallback.append(", ").append(metadataText);
            }
            fallback.append("。MindCLI 会在下一轮把图片作为图片附件附加；"
                    + "如果模型无法稳定识别该图片，请优先调用 take_snapshot 获取 DOM 文本快照。]");
        }
        return fallback.toString();
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

    private static JsonNode sanitizeSchema(Map<String, Object> schema) {
        return McpSchemaSanitizer.sanitize(MAPPER.valueToTree(schema == null ? Map.of() : schema));
    }

    private static IOException asIoException(RuntimeException exception) {
        Throwable cause = exception.getCause();
        String message = exception.getMessage();
        if (cause != null && (message == null || message.isBlank())) {
            message = cause.getMessage();
        }
        return new IOException(message == null ? exception.getClass().getSimpleName() : message, exception);
    }

    private static boolean isMethodNotFound(RuntimeException exception) {
        String message = exception.getMessage();
        return message != null && (message.contains("-32601") || message.toLowerCase().contains("method not found"));
    }
}
