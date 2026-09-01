package com.mindcli.platform.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownFrontmatterParserTest {

    @Test
    void parsesSupportedMetadataAndPreservesCompleteBody() {
        String markdown = """
                ---
                name: "memory: architecture"
                tags: [runtime, "tool call"]
                description: |
                  first line
                  second line
                ---
                # Full memory

                Paragraph one.

                Paragraph two: still present.
                """;

        MarkdownFrontmatterParser.ParseResult result = MarkdownFrontmatterParser.parse(markdown);

        assertEquals("memory: architecture", result.metadata().get("name"));
        assertEquals(List.of("runtime", "tool call"), result.metadata().get("tags"));
        assertEquals("first line second line", result.metadata().get("description"));
        assertEquals("# Full memory\n\nParagraph one.\n\nParagraph two: still present.\n", result.body());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void reportsMissingDelimitersWithoutDiscardingInput() {
        MarkdownFrontmatterParser.ParseResult result = MarkdownFrontmatterParser.parse("plain body\n");

        assertEquals("plain body\n", result.body());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void warnsAndSkipsUnsupportedNestedObjects() {
        MarkdownFrontmatterParser.ParseResult result = MarkdownFrontmatterParser.parse("""
                ---
                name: valid
                nested: {key: value}
                ---
                body
                """);

        assertEquals("valid", result.metadata().get("name"));
        assertFalse(result.metadata().containsKey("nested"));
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("嵌套对象")));
    }
}
