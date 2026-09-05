package com.mindcli.capability.skill;

import com.mindcli.platform.text.MarkdownFrontmatterParser;

import java.util.List;
import java.util.Map;

/** Compatibility facade for the shared Markdown frontmatter parser. */
public final class SkillFrontmatterParser {
    public record ParseResult(Map<String, Object> frontmatter, String body, List<String> warnings) {
    }

    private SkillFrontmatterParser() {
    }

    public static ParseResult parse(String fullText) {
        MarkdownFrontmatterParser.ParseResult result = MarkdownFrontmatterParser.parse(fullText);
        return new ParseResult(result.metadata(), result.body(), result.warnings());
    }
}
