package com.mindcli.agent.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.agent.AgentRole;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.capability.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class AgentToolPolicy {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> READ_GROUP = List.of(
            "read_file", "list_dir", "glob_files", "grep_code", "search_code");
    private static final List<String> WRITE_GROUP = List.of("write_file", "create_project");
    private static final List<String> WEB_GROUP = List.of("web_search", "web_fetch");

    private AgentToolPolicy() {
    }

    public static Decision evaluate(AgentProfile profile, ToolRegistry.ToolInvocation invocation) {
        if (profile == null || invocation == null) {
            return Decision.allow(Map.of());
        }
        String toolName = invocation.name() == null ? "" : invocation.name();
        Map<String, String> metadata = metadata(profile);
        if (isDenied(profile.deniedTools(), toolName)) {
            return Decision.deny("profile " + profile.name() + " denies " + toolName, metadata);
        }
        if (isReadOnlyWrite(profile, toolName)) {
            return Decision.deny("profile " + profile.name() + " is read-only and cannot use " + toolName, metadata);
        }
        if (!toolAllowed(profile, toolName)) {
            return Decision.deny("profile " + profile.name() + " does not allow " + toolName, metadata);
        }
        if ("execute_command".equals(toolName) && !commandAllowed(profile, invocation.argumentsJson())) {
            return Decision.deny("profile " + profile.name() + " does not allow this command", metadata);
        }
        return Decision.allow(metadata);
    }

    public static Decision evaluate(AgentRunContext context, ToolRegistry.ToolInvocation invocation) {
        if (context == null || context.metadata().getOrDefault("allowedTools", "").isBlank()) {
            return Decision.allow(Map.of());
        }
        Map<String, String> data = context.metadata();
        AgentRole role = parseRole(data.getOrDefault("profileRole", data.getOrDefault("role", "WORKER")));
        AgentProfile profile = new AgentProfile(
                data.getOrDefault("profileName", data.getOrDefault("agentName", "agent")),
                role,
                "",
                splitCsv(data.get("allowedTools")),
                splitCsv(data.get("deniedTools")),
                splitCsv(data.get("commandAllowlist")),
                data.getOrDefault("model", "auto"),
                1,
                data.getOrDefault("permissionMode", "CUSTOM"),
                data.getOrDefault("memoryScope", "PARENT_SUMMARY"),
                data.getOrDefault("contextMode", "balanced"));
        return evaluate(profile, invocation);
    }

    public static boolean toolAllowed(AgentProfile profile, String toolName) {
        if (profile == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        for (String pattern : profile.tools()) {
            if (matchesTool(pattern, toolName)) {
                return true;
            }
        }
        return false;
    }

    public static String formatTools(List<String> tools) {
        return tools == null || tools.isEmpty() ? "" : String.join(",", tools);
    }

    private static boolean isDenied(List<String> deniedTools, String toolName) {
        for (String denied : deniedTools) {
            if (matchesTool(denied, toolName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReadOnlyWrite(AgentProfile profile, String toolName) {
        return "READ_ONLY".equalsIgnoreCase(profile.permissionMode()) && WRITE_GROUP.contains(toolName);
    }

    private static boolean matchesTool(String pattern, String toolName) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        return switch (pattern) {
            case "*" -> true;
            case "@read" -> READ_GROUP.contains(toolName);
            case "@write" -> WRITE_GROUP.contains(toolName);
            case "@web" -> WEB_GROUP.contains(toolName);
            default -> wildcardMatches(pattern, toolName);
        };
    }

    private static boolean commandAllowed(AgentProfile profile, String argumentsJson) {
        String command = command(argumentsJson);
        if (command.isBlank() || profile.commandAllowlist().isEmpty()) {
            return false;
        }
        for (String allowed : profile.commandAllowlist()) {
            if (wildcardMatches(allowed, command)) {
                return true;
            }
        }
        return false;
    }

    private static String command(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            return node.path("command").asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean wildcardMatches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if (!pattern.contains("*")) {
            return pattern.equals(value);
        }
        String regex = Pattern.quote(pattern).replace("\\*", "\\E.*\\Q");
        return value.matches(regex);
    }

    private static AgentRole parseRole(String raw) {
        try {
            return AgentRole.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return AgentRole.WORKER;
        }
    }

    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split(",");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, String> metadata(AgentProfile profile) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("profileName", profile.name());
        metadata.put("profileRole", profile.role().name());
        metadata.put("permissionMode", profile.permissionMode());
        metadata.put("allowedTools", formatTools(profile.tools()));
        metadata.put("policyDecision", "ALLOW");
        return metadata;
    }

    public record Decision(boolean allowed, String reason, Map<String, String> metadata) {
        public Decision {
            reason = reason == null ? "" : reason;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        static Decision allow(Map<String, String> metadata) {
            return new Decision(true, "", withDecision(metadata, "ALLOW", ""));
        }

        static Decision deny(String reason, Map<String, String> metadata) {
            return new Decision(false, reason, withDecision(metadata, "DENY", reason));
        }

        private static Map<String, String> withDecision(Map<String, String> metadata, String decision, String reason) {
            Map<String, String> merged = new LinkedHashMap<>();
            if (metadata != null) {
                merged.putAll(metadata);
            }
            merged.put("policyDecision", decision);
            if (reason != null && !reason.isBlank()) {
                merged.put("policyReason", reason);
            }
            return merged;
        }
    }
}
