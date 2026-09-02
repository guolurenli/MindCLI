package com.mindcli.runtime.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.platform.hitl.ApprovalPolicy;
import com.mindcli.capability.tool.ToolRegistry;

import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ToolResourceClassifier {
    private static final ObjectMapper MAPPER = com.mindcli.platform.serialization.JsonSupport.mapper();

    public List<ResourceKey> classify(ToolRegistry.ToolInvocation invocation, AgentRunContext context) {
        if (invocation == null) {
            return List.of(workspace(context, ResourceAccess.EXCLUSIVE));
        }
        AgentRunContext effectiveContext = context == null
                ? AgentRunContext.create(AgentMode.REACT, "", System.getProperty("user.dir", ""))
                : context;
        String name = invocation.name() == null ? "" : invocation.name();
        Map<String, String> args = parseArguments(invocation.argumentsJson());

        if (ApprovalPolicy.isMcpTool(name)) {
            return classifyMcp(name);
        }

        return switch (name) {
            case "read_file" -> fileAccess(effectiveContext, args.get("path"), ResourceAccess.SHARED);
            case "write_file" -> fileAccess(effectiveContext, args.get("path"), ResourceAccess.EXCLUSIVE);
            case "list_dir" -> directoryAccess(effectiveContext, args.get("path"), ResourceAccess.EXCLUSIVE);
            case "glob_files", "grep_code" ->
                    List.of(workspace(effectiveContext, ResourceAccess.SHARED));
            case "search_memory", "read_memory" ->
                    List.of(new ResourceKey(ResourceScope.MEMORY, "long-term", ResourceAccess.SHARED));
            case "save_memory" ->
                    List.of(new ResourceKey(ResourceScope.MEMORY, "long-term", ResourceAccess.EXCLUSIVE));
            case "web_search", "web_fetch" ->
                    List.of(new ResourceKey(ResourceScope.NETWORK, "web", ResourceAccess.SHARED));
            case "create_project" -> createProjectAccess(effectiveContext, args);
            case "execute_command" -> executeCommandAccess(effectiveContext, args.get("command"));
            case "revert_turn" -> List.of(workspace(effectiveContext, ResourceAccess.EXCLUSIVE));
            default -> unknownAccess(effectiveContext, name);
        };
    }

    private static List<ResourceKey> classifyMcp(String toolName) {
        String server = ApprovalPolicy.mcpServerName(toolName);
        String normalizedServer = server == null || server.isBlank() ? "unknown" : server;
        if (isBrowserMcpTool(normalizedServer, toolName)) {
            return List.of(new ResourceKey(ResourceScope.BROWSER_SESSION, normalizedServer, ResourceAccess.EXCLUSIVE));
        }
        return List.of(new ResourceKey(ResourceScope.MCP_SERVER, normalizedServer, ResourceAccess.EXCLUSIVE));
    }

    private static boolean isBrowserMcpTool(String server, String toolName) {
        String lowerServer = server.toLowerCase(Locale.ROOT);
        String lowerTool = toolName.toLowerCase(Locale.ROOT);
        return lowerServer.contains("browser")
                || lowerServer.contains("chrome")
                || lowerTool.contains("__browser_")
                || lowerTool.contains("__browser.");
    }

    private static List<ResourceKey> fileAccess(AgentRunContext context, String path, ResourceAccess fileAccess) {
        if (path == null || path.isBlank()) {
            return List.of(workspace(context, ResourceAccess.EXCLUSIVE));
        }
        Path resolved = resolvePath(context, path);
        List<ResourceKey> keys = new ArrayList<>();
        keys.add(workspace(context, ResourceAccess.SHARED));
        Path parent = resolved.getParent();
        if (parent != null) {
            keys.addAll(directoryHierarchy(context, parent, ResourceAccess.SHARED));
        }
        keys.add(new ResourceKey(ResourceScope.FILE, resolved.toString(), fileAccess));
        return List.copyOf(keys);
    }

    private static List<ResourceKey> directoryAccess(AgentRunContext context, String path,
                                                     ResourceAccess directoryAccess) {
        String effectivePath = path == null || path.isBlank() ? "." : path;
        Path resolved = resolvePath(context, effectivePath);
        List<ResourceKey> keys = new ArrayList<>();
        keys.add(workspace(context, ResourceAccess.SHARED));
        keys.addAll(directoryHierarchy(context, resolved, directoryAccess));
        return List.copyOf(keys);
    }

    private static List<ResourceKey> createProjectAccess(AgentRunContext context, Map<String, String> args) {
        String target = args.get("path");
        if (target == null || target.isBlank()) {
            target = args.get("name");
        }
        if (target == null || target.isBlank()) {
            return List.of(workspace(context, ResourceAccess.EXCLUSIVE));
        }
        Path resolved = resolvePath(context, target);
        List<ResourceKey> keys = new ArrayList<>();
        keys.add(workspace(context, ResourceAccess.SHARED));
        keys.addAll(directoryHierarchy(context, resolved, ResourceAccess.EXCLUSIVE));
        return List.copyOf(keys);
    }

    private static List<ResourceKey> executeCommandAccess(AgentRunContext context, String command) {
        return List.of(workspace(context, isKnownReadOnlyCommand(command)
                ? ResourceAccess.SHARED
                : ResourceAccess.EXCLUSIVE));
    }

    private static List<ResourceKey> unknownAccess(AgentRunContext context, String name) {
        List<ResourceKey> keys = new ArrayList<>();
        keys.add(workspace(context, ResourceAccess.EXCLUSIVE));
        keys.add(new ResourceKey(ResourceScope.UNKNOWN,
                name == null || name.isBlank() ? "unknown" : name,
                ResourceAccess.EXCLUSIVE));
        return List.copyOf(keys);
    }

    private static ResourceKey workspace(AgentRunContext context, ResourceAccess access) {
        return new ResourceKey(ResourceScope.WORKSPACE, workspaceName(context), access);
    }

    private static String workspaceName(AgentRunContext context) {
        String workspace = context == null ? "" : context.workspace();
        if (workspace == null || workspace.isBlank()) {
            workspace = System.getProperty("user.dir", "");
        }
        return canonicalize(Path.of(workspace)).toString();
    }

    private static String resolve(AgentRunContext context, String rawPath) {
        return resolvePath(context, rawPath).toString();
    }

    private static Path resolvePath(AgentRunContext context, String rawPath) {
        Path candidate = Path.of(rawPath);
        if (!candidate.isAbsolute()) {
            candidate = Path.of(workspaceName(context)).resolve(candidate);
        }
        return canonicalize(candidate);
    }

    private static Path canonicalize(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        try {
            return normalizeCase(normalized.toRealPath());
        } catch (IOException ignored) {
            Path existing = normalized;
            while (existing != null && !java.nio.file.Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                return normalizeCase(normalized);
            }
            try {
                return normalizeCase(existing.toRealPath()
                        .resolve(existing.relativize(normalized)).normalize());
            } catch (IOException ignoredAgain) {
                return normalizeCase(normalized);
            }
        }
    }

    private static Path normalizeCase(Path path) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return Path.of(path.toString().toLowerCase(Locale.ROOT));
        }
        return path;
    }

    private static List<ResourceKey> directoryHierarchy(AgentRunContext context, Path target,
                                                        ResourceAccess targetAccess) {
        if (target == null) {
            return List.of();
        }
        Path workspace = Path.of(workspaceName(context)).normalize();
        Path normalizedTarget = target.normalize();
        List<Path> directories = new ArrayList<>();
        Path cursor = normalizedTarget;
        while (cursor != null && cursor.startsWith(workspace)) {
            directories.add(cursor);
            if (cursor.equals(workspace)) {
                break;
            }
            cursor = cursor.getParent();
        }
        if (directories.isEmpty()) {
            return List.of(new ResourceKey(ResourceScope.DIRECTORY, normalizedTarget.toString(), targetAccess));
        }
        Collections.reverse(directories);
        List<ResourceKey> keys = new ArrayList<>();
        for (Path directory : directories) {
            ResourceAccess access = directory.equals(normalizedTarget) ? targetAccess : ResourceAccess.SHARED;
            keys.add(new ResourceKey(ResourceScope.DIRECTORY, directory.toString(), access));
        }
        return List.copyOf(keys);
    }

    private static boolean isKnownReadOnlyCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalized.contains(">") || normalized.contains(";") || normalized.contains("&&")
                || normalized.contains("||") || normalized.contains("|")) {
            return false;
        }
        return normalized.equals("git status")
                || normalized.startsWith("git status ")
                || isSafeGitDiffCommand(normalized)
                || normalized.equals("ls")
                || normalized.startsWith("ls ")
                || normalized.equals("dir")
                || normalized.startsWith("dir ")
                || normalized.equals("get-childitem")
                || normalized.startsWith("get-childitem ");
    }

    private static boolean isSafeGitDiffCommand(String normalized) {
        if (!normalized.equals("git diff") && !normalized.startsWith("git diff ")) {
            return false;
        }
        for (String token : normalized.split(" ")) {
            if (token.equals("--output")
                    || token.startsWith("--output=")
                    || token.equals("--ext-diff")
                    || token.equals("--textconv")) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = MAPPER.readTree(argumentsJson);
            if (root == null || !root.isObject()) {
                return Map.of();
            }
            Map<String, String> args = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                args.put(entry.getKey(), entry.getValue().asText());
            }
            return args;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
