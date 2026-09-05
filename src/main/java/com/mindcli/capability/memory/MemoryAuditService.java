package com.mindcli.capability.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.runtime.run.AgentRunEventType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MemoryAuditService {
    private static final ObjectMapper MAPPER = com.mindcli.platform.serialization.JsonSupport.mapper();
    private static final DateTimeFormatter EXPORT_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private final Path auditFile;

    public MemoryAuditService(Path auditFile) {
        this.auditFile = auditFile.toAbsolutePath().normalize();
    }

    public synchronized void record(AgentRunEventType type, Map<String, String> attributes) {
        if (type != null) {
            record(type.name(), attributes);
        }
    }

    public synchronized void record(String type, Map<String, String> attributes) {
        String normalizedType = type == null || type.isBlank() ? "MEMORY_UNKNOWN" : type.trim();
        MemoryAuditRecord record = new MemoryAuditRecord(
                "mem_evt_" + UUID.randomUUID(),
                normalizedType,
                Instant.now().toString(),
                normalizeAttributes(attributes));
        try {
            Files.createDirectories(auditFile.getParent());
            Files.writeString(auditFile, MAPPER.writeValueAsString(toMap(record)) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append memory audit event: " + e.getMessage(), e);
        }
    }

    public synchronized List<MemoryAuditRecord> list() {
        if (!Files.exists(auditFile)) {
            return List.of();
        }
        List<MemoryAuditRecord> records = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(auditFile, StandardCharsets.UTF_8)) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                MemoryAuditRecord record = fromJson(trimmed);
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read memory audit events: " + e.getMessage(), e);
        }
        return List.copyOf(records);
    }

    public synchronized Path exportMarkdown(Path exportDir, LocalDateTime exportedAt) throws IOException {
        LocalDateTime effectiveExportedAt = exportedAt == null ? LocalDateTime.now() : exportedAt;
        Path directory = exportDir.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path exportFile = directory.resolve("memory-audit-" + effectiveExportedAt.format(EXPORT_STAMP) + ".md");
        record(AgentRunEventType.MEMORY_EXPORTED, Map.of(
                "exportPath", exportFile.toString(),
                "auditPath", auditFile.toString()));
        Files.writeString(exportFile, renderMarkdown(list(), effectiveExportedAt), StandardCharsets.UTF_8);
        return exportFile;
    }

    public Path auditFile() {
        return auditFile;
    }

    private String renderMarkdown(List<MemoryAuditRecord> records, LocalDateTime exportedAt) {
        StringBuilder md = new StringBuilder();
        md.append("# MindCLI 记忆审计导出\n\n");
        md.append("**导出时间**: ")
                .append(exportedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n\n");
        md.append("**审计源**: ").append(auditFile).append("\n\n");
        md.append("**事件数量**: ").append(records.size()).append("\n\n");
        md.append("| Time | Type | Target | Policy | Source | Run |\n");
        md.append("| --- | --- | --- | --- | --- | --- |\n");
        for (MemoryAuditRecord record : records) {
            Map<String, String> attrs = record.attributes();
            md.append("| ")
                    .append(escape(record.timestamp()))
                    .append(" | ")
                    .append(escape(record.type()))
                    .append(" | ")
                    .append(escape(firstNonBlank(attrs.get("memoryId"), attrs.get("proposalId"), attrs.get("exportPath"))))
                    .append(" | ")
                    .append(escape(attrs.getOrDefault("policyId", "")))
                    .append(" | ")
                    .append(escape(attrs.getOrDefault("source", "")))
                    .append(" | ")
                    .append(escape(attrs.getOrDefault("runId", "")))
                    .append(" |\n");
        }
        return md.toString();
    }

    private static MemoryAuditRecord fromJson(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return new MemoryAuditRecord(
                    node.path("eventId").asText(""),
                    node.path("type").asText(""),
                    node.path("timestamp").asText(""),
                    attributesFrom(node.path("attributes")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> attributesFrom(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            attributes.put(field.getKey(), field.getValue().asText(""));
        }
        return Map.copyOf(attributes);
    }

    private static Map<String, Object> toMap(MemoryAuditRecord record) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", record.eventId());
        out.put("type", record.type());
        out.put("timestamp", record.timestamp());
        out.put("attributes", record.attributes());
        return out;
    }

    private static Map<String, String> normalizeAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                normalized.put(key, value == null ? "" : value);
            }
        });
        return Map.copyOf(normalized);
    }

    private static String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return third == null ? "" : third;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }
}
