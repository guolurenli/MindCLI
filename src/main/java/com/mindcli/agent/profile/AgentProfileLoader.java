package com.mindcli.agent.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.agent.AgentRole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AgentProfileLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentProfileLoader() {
    }

    public static List<AgentProfile> load(Path projectRoot) {
        Path config = configuredPath(projectRoot);
        if (config != null && Files.isRegularFile(config)) {
            try {
                List<AgentProfile> profiles = parse(config);
                validate(profiles);
                return profiles;
            } catch (Exception e) {
                return compatDefaults();
            }
        }
        return compatDefaults();
    }

    public static List<AgentProfile> compatDefaults() {
        return List.of(
                AgentProfile.legacy("planner", AgentRole.PLANNER),
                AgentProfile.legacy("worker-1", AgentRole.WORKER),
                AgentProfile.legacy("worker-2", AgentRole.WORKER),
                AgentProfile.legacy("reviewer", AgentRole.REVIEWER));
    }

    private static Path configuredPath(Path projectRoot) {
        String explicit = System.getProperty("mindcli.agents.file");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("MINDCLI_AGENTS_FILE");
        }
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        if (projectRoot == null) {
            return null;
        }
        return projectRoot.resolve(".mindcli").resolve("agents.json");
    }

    private static List<AgentProfile> parse(Path config) throws Exception {
        JsonNode root = MAPPER.readTree(config.toFile());
        JsonNode profilesNode = root.path("profiles");
        if (!profilesNode.isArray()) {
            profilesNode = root.path("agents");
        }
        if (!profilesNode.isArray()) {
            throw new IllegalArgumentException("profiles must be an array");
        }
        List<AgentProfile> profiles = new ArrayList<>();
        for (JsonNode node : profilesNode) {
            profiles.add(new AgentProfile(
                    text(node, "name", ""),
                    AgentRole.valueOf(text(node, "role", "WORKER").toUpperCase(Locale.ROOT)),
                    text(node, "description", ""),
                    stringList(node.path("tools")),
                    stringList(node.path("deniedTools")),
                    stringList(node.path("commandAllowlist")),
                    text(node, "model", "auto"),
                    node.path("maxConcurrency").asInt(1),
                    text(node, "permissionMode", "CUSTOM"),
                    text(node, "memoryScope", "PARENT_SUMMARY"),
                    text(node, "contextMode", "balanced")));
        }
        return List.copyOf(profiles);
    }

    private static void validate(List<AgentProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException("no profiles configured");
        }
        Set<String> names = new HashSet<>();
        boolean hasWorker = false;
        for (AgentProfile profile : profiles) {
            if (!names.add(profile.name())) {
                throw new IllegalArgumentException("duplicated profile: " + profile.name());
            }
            if (profile.role() == AgentRole.WORKER) {
                hasWorker = true;
            }
        }
        if (!hasWorker) {
            throw new IllegalArgumentException("no WORKER profile configured");
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(fallback);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }
}
