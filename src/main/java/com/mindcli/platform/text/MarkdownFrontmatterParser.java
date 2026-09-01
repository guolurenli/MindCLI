package com.mindcli.platform.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the deliberately small Markdown frontmatter subset used by MindCLI. */
public final class MarkdownFrontmatterParser {
    public record ParseResult(Map<String, Object> metadata, String body, List<String> warnings) {
        public ParseResult {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            body = body == null ? "" : body;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    private MarkdownFrontmatterParser() {
    }

    public static ParseResult parse(String fullText) {
        if (fullText == null) {
            return new ParseResult(Map.of(), "", List.of("Markdown 内容为 null"));
        }
        String normalized = fullText.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.startsWith("---\n")) {
            return new ParseResult(Map.of(), normalized, List.of("缺少 frontmatter 起始标记 ---"));
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            if (normalized.endsWith("\n---")) {
                end = normalized.length() - 4;
            } else {
                return new ParseResult(Map.of(), normalized, List.of("缺少 frontmatter 结束标记 ---"));
            }
        }
        String frontmatter = normalized.substring(4, end);
        int bodyStart = end + 4;
        if (bodyStart < normalized.length() && normalized.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        List<String> warnings = new ArrayList<>();
        return new ParseResult(parseMetadata(frontmatter, warnings),
                normalized.substring(Math.min(bodyStart, normalized.length())), warnings);
    }

    private static Map<String, Object> parseMetadata(String text, List<String> warnings) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            if (line.isBlank() || line.trim().startsWith("#")) {
                index++;
                continue;
            }
            int colon = findKeyColon(line);
            if (colon < 0) {
                warnings.add("无法解析的 frontmatter 行: " + line);
                index++;
                continue;
            }
            String key = line.substring(0, colon).trim();
            String rawValue = line.substring(colon + 1).trim();
            if (key.isEmpty()) {
                warnings.add("frontmatter 行缺少 key: " + line);
                index++;
                continue;
            }
            if (rawValue.isEmpty()) {
                warnings.add("frontmatter 字段 '" + key + "' 缺少值或使用了不支持的嵌套结构");
                index++;
                continue;
            }
            if (rawValue.startsWith("{")) {
                warnings.add("frontmatter 字段 '" + key + "' 使用了不支持的嵌套对象语法");
                index++;
                continue;
            }
            if (rawValue.startsWith("|")) {
                StringBuilder value = new StringBuilder();
                index++;
                Integer baseIndent = null;
                while (index < lines.length) {
                    String next = lines[index];
                    if (next.isBlank()) {
                        value.append('\n');
                        index++;
                        continue;
                    }
                    int indent = leadingSpaces(next);
                    if (indent == 0) break;
                    if (baseIndent == null) baseIndent = indent;
                    if (indent < baseIndent) break;
                    value.append(next.substring(baseIndent)).append('\n');
                    index++;
                }
                metadata.put(key, value.toString().replaceAll("\\s+", " ").trim());
                continue;
            }
            if (rawValue.startsWith("[") && rawValue.endsWith("]")) {
                metadata.put(key, parseArray(rawValue.substring(1, rawValue.length() - 1)));
                index++;
                continue;
            }
            metadata.put(key, stripOptionalQuotes(rawValue));
            index++;
        }
        return metadata;
    }

    private static List<String> parseArray(String inner) {
        if (inner.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String part : inner.split(",")) {
            String value = stripOptionalQuotes(part.trim());
            if (!value.isEmpty()) values.add(value);
        }
        return List.copyOf(values);
    }

    private static String stripOptionalQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static int findKeyColon(String line) {
        boolean single = false;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (value == '\'' && !quoted) single = !single;
            else if (value == '"' && !single) quoted = !quoted;
            else if (value == ':' && !single && !quoted) return i;
        }
        return -1;
    }

    private static int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == ' ') count++;
        return count;
    }
}
