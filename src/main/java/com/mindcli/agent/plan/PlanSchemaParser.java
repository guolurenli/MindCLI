package com.mindcli.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlanSchemaParser {
    private final ObjectMapper mapper;

    public PlanSchemaParser(ObjectMapper mapper) {
        this.mapper = mapper == null ? com.mindcli.platform.serialization.JsonSupport.mapper() : mapper;
    }

    public PlanSchema parse(String rawJson) throws IOException {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IOException("计划内容为空");
        }

        String cleaned = stripCodeFence(rawJson);
        JsonNode root = mapper.readTree(cleaned);
        int schemaVersion = root.path("schemaVersion").asInt(2);
        String summary = root.path("summary").asText("");

        JsonNode tasksNode = root.path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            tasksNode = root.path("steps");
        }
        List<PlanTaskSpec> tasks = new ArrayList<>();
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            return new PlanSchema(schemaVersion, summary, List.of());
        }

        for (JsonNode taskNode : tasksNode) {
            tasks.add(parseTaskSpec(taskNode));
        }
        return new PlanSchema(schemaVersion, summary, List.copyOf(tasks));
    }

    private PlanTaskSpec parseTaskSpec(JsonNode node) throws IOException {
        String id = node.path("id").asText("");
        String description = node.path("description").asText("");
        String typeText = text(node, "type");
        Task.TaskType type;
        try {
            type = Task.TaskType.valueOf(typeText.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IOException("PLAN_UNKNOWN_TASK_TYPE: " + typeText, e);
        }

        List<String> dependencies = new ArrayList<>();
        JsonNode depsNode = node.path("dependencies");
        if (depsNode.isArray()) {
            for (JsonNode dep : depsNode) {
                String value = dep.asText("");
                if (!value.isBlank()) {
                    dependencies.add(value);
                }
            }
        }

        List<String> evidence = new ArrayList<>();
        JsonNode evidenceNode = node.path("expectedEvidence");
        if (evidenceNode.isArray()) {
            for (JsonNode item : evidenceNode) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    evidence.add(value);
                }
            }
        } else if (!evidenceNode.isMissingNode() && !evidenceNode.isNull()) {
            throw new PlanParseException("PLAN_INVALID_EVIDENCE: expectedEvidence");
        }

        boolean critical = !node.has("critical") || node.path("critical").asBoolean(true);
        int maxRetries = node.has("maxRetries") && node.path("maxRetries").canConvertToInt()
                ? node.path("maxRetries").asInt(3)
                : 3;
        String degradation = node.path("degradation").asText("REPLAN");
        if (degradation.isBlank()) {
            degradation = "REPLAN";
        }

        List<String> requiredTools = new ArrayList<>();
        JsonNode toolsNode = node.path("requiredTools");
        if (toolsNode.isArray()) {
            for (JsonNode item : toolsNode) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    requiredTools.add(value);
                }
            }
        } else if (!toolsNode.isMissingNode() && !toolsNode.isNull()) {
            throw new PlanParseException("PLAN_INVALID_REQUIRED_TOOLS: requiredTools");
        }
        String preferredAgent = node.path("preferredAgent").asText("");
        String riskLevel = node.path("riskLevel").asText("");

        return new PlanTaskSpec(id, description, type, List.copyOf(dependencies), critical,
                maxRetries, degradation, List.copyOf(evidence), List.copyOf(requiredTools),
                preferredAgent, riskLevel);
    }

    private String text(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IOException("PLAN_EMPTY_FIELD: " + field);
        }
        return value.trim();
    }

    private String stripCodeFence(String rawJson) {
        return rawJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
    }
}
