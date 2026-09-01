package com.mindcli.capability.mcp;

import com.mindcli.capability.image.ImageReferenceParser;
import com.mindcli.capability.mcp.resources.McpResourceContent;
import com.mindcli.capability.mcp.resources.McpResourceDescriptor;
import com.mindcli.capability.tool.ToolOutput;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientTest {

    @Test
    void facadeUsesOnlyOfficialSdkClientAndTransport() {
        assertEquals(1, McpClient.class.getDeclaredConstructors().length);
        assertTrue(Arrays.stream(McpClient.class.getDeclaredFields())
                .noneMatch(field -> field.getName().equals("rpc") || field.getName().equals("transport")));
        assertTrue(Arrays.stream(McpClient.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(McpSyncClient.class)));
        assertTrue(Arrays.stream(McpClient.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(McpClientTransport.class)));
    }

    @Test
    void transportNameFallsBackWhenOfficialTransportIsUnavailable() {
        McpClient client = new McpClient("demo", null, null, null);

        assertEquals("unknown", client.transportName());
    }

    @Test
    void initializeTimeoutCanBeOverriddenBySystemProperty() {
        String previous = System.getProperty("mindcli.mcp.initialize.timeout.seconds");
        try {
            System.setProperty("mindcli.mcp.initialize.timeout.seconds", "17");
            assertEquals(17, McpClient.initializeTimeoutSeconds());
        } finally {
            if (previous == null) {
                System.clearProperty("mindcli.mcp.initialize.timeout.seconds");
            } else {
                System.setProperty("mindcli.mcp.initialize.timeout.seconds", previous);
            }
        }
    }

    @Test
    void formatsResourceIndexWithoutEmbeddingContent() {
        McpResourceDescriptor resource = new McpResourceDescriptor(
                "docs", "file://README.md", "README", null,
                "project guide", "text/markdown", 42L);

        String formatted = McpClient.formatResources(List.of(resource));

        assertTrue(formatted.contains("file://README.md | README | text/markdown"));
        assertTrue(formatted.contains("project guide"));
    }

    @Test
    void formatsTextAndBinaryResourceContents() {
        List<McpResourceContent> contents = List.of(
                new McpResourceContent("file://README.md", "text/markdown", "hello", null),
                new McpResourceContent("file://logo.png", "image/png", null, "aGVsbG8="));

        String formatted = McpClient.formatResourceContents(contents);

        assertTrue(formatted.contains("<resource uri=\"file://README.md\" mimeType=\"text/markdown\">"));
        assertTrue(formatted.contains("hello"));
        assertTrue(formatted.contains("base64 length=8"));
    }

    @Test
    void formatResourceContentsEscapesXmlAttributes() {
        McpResourceContent content = new McpResourceContent(
                "file://a&\"<b>", "text/plain&unsafe", "body", null);

        String formatted = McpClient.formatResourceContents(List.of(content));

        assertTrue(formatted.contains("uri=\"file://a&amp;&quot;&lt;b&gt;\""));
        assertTrue(formatted.contains("mimeType=\"text/plain&amp;unsafe\""));
        assertFalse(formatted.contains("file://a&\"<b>"));
    }

    @Test
    void officialSdkImageContentBecomesImagePart() {
        String base64 = "aGVsbG8=";
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.ImageContent(null, base64, "image/png")),
                false, null, null);

        ToolOutput output = McpClient.toToolOutput(result);

        assertTrue(output.hasImageParts(), "小图片应进入 imageParts");
        assertEquals(1, output.imageParts().size());
        assertEquals(base64, output.imageParts().get(0).imageBase64());
        assertTrue(output.text().contains("base64Length=" + base64.length()));
        assertFalse(output.text().contains("超过"));
    }

    @Test
    void oversizedOfficialSdkImageFallsBackToTextOnly() {
        int approxBytes = (int) (ImageReferenceParser.MAX_IMAGE_BYTES + 1024);
        int base64Length = (approxBytes * 4 / 3) + 4;
        String base64 = "A".repeat(base64Length);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.ImageContent(null, base64, "image/png")),
                false, null, null);

        ToolOutput output = McpClient.toToolOutput(result);

        assertFalse(output.hasImageParts(), "超过上限的图片不应进入 imageParts");
        assertTrue(output.text().contains("超过"));
        assertTrue(output.text().contains("take_snapshot"));
    }

    @Test
    void emptyOfficialSdkImageKeepsFallbackOnly() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.ImageContent(null, "", "image/png")),
                false, null, null);

        ToolOutput output = McpClient.toToolOutput(result);

        assertFalse(output.hasImageParts());
        assertTrue(output.text().contains("base64Length=0"));
    }

    @Test
    void officialToolCallForwardsArgumentsAndCombinesText() throws Exception {
        McpSyncClient sdkClient = mock(McpSyncClient.class);
        when(sdkClient.callTool(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("first"), new McpSchema.TextContent("second")),
                        false, null, null));
        McpClient client = new McpClient("demo", sdkClient, null, null);

        String output = client.callTool("echo", "{\"text\":\"hi\",\"count\":2}");

        ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(sdkClient).callTool(captor.capture());
        assertEquals("echo", captor.getValue().name());
        assertEquals("hi", captor.getValue().arguments().get("text"));
        assertEquals(2, captor.getValue().arguments().get("count"));
        assertEquals("first\n\nsecond", output);
    }

    @Test
    void officialToolErrorUsesExplicitPrefix() throws Exception {
        McpSyncClient sdkClient = mock(McpSyncClient.class);
        when(sdkClient.callTool(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("no such file")),
                        true, null, null));
        McpClient client = new McpClient("demo", sdkClient, null, null);

        String output = client.callTool("read_file", "{}");

        assertTrue(output.startsWith("MCP 工具返回错误"));
        assertTrue(output.contains("no such file"));
    }
}
