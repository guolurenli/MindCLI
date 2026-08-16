package com.mindcli.agent.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AgentProfileLoader {
    private static final Logger log = LoggerFactory.getLogger(AgentProfileLoader.class);

    private AgentProfileLoader() {
    }

    /**
     * 内置 worker/explorer 硬编码 + 追加 .mindcli/agents/*.toml 自定义子代理。
     *
     * 坏文件 / 重名 agent 采用 fail-soft：跳过并 warn，不阻塞 /team 启动。
     */
    public static List<AgentProfile> load(Path projectRoot) {
        List<AgentProfile> profiles = new ArrayList<>(builtinDefaults());
        Path agentsDir = resolveAgentsDir(projectRoot);
        if (agentsDir != null && Files.isDirectory(agentsDir)) {
            for (Path file : listTomlFiles(agentsDir)) {
                AgentProfile profile = parseCustomAgent(file);
                if (profile == null) {
                    continue;
                }
                if (profiles.stream().anyMatch(p -> p.name().equals(profile.name()))) {
                    log.warn("duplicate agent name skipped: {}", profile.name());
                    continue;
                }
                profiles.add(profile);
            }
        }
        return profiles;
    }

    public static List<AgentProfile> builtinDefaults() {
        return List.of(
                AgentProfile.builtinExplorer("explorer#1"),
                AgentProfile.builtinExplorer("explorer#2"),
                AgentProfile.builtinWorker("worker#1"));
    }

    private static Path resolveAgentsDir(Path projectRoot) {
        if (projectRoot == null) {
            return null;
        }
        try {
            return projectRoot.resolve(".mindcli").resolve("agents");
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Path> listTomlFiles(Path agentsDir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(agentsDir, "*.toml")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        } catch (IOException e) {
            log.warn("failed to list agent dir {}: {}", agentsDir, e.getMessage());
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    private static AgentProfile parseCustomAgent(Path file) {
        try {
            TomlParseResult parsed = Toml.parse(file);
            if (parsed.hasErrors()) {
                log.warn("skip invalid agent config {}: {}", file, parsed.errors());
                return null;
            }
            String name = parsed.getString("name");
            if (name == null || name.isBlank()) {
                name = filenameWithoutToml(file);
            }
            if (!isValidName(name)) {
                log.warn("skip agent {}: invalid name {}", file, name);
                return null;
            }
            String description = parsed.getString("description");
            if (description == null || description.isBlank()) {
                log.warn("skip agent {}: missing description", file);
                return null;
            }
            String developerInstructions = parsed.getString("developer_instructions");
            if (developerInstructions == null || developerInstructions.isBlank()) {
                log.warn("skip agent {}: missing developer_instructions", file);
                return null;
            }
            String sandboxMode = parsed.getString("sandbox_mode");
            String approvalPolicy = parsed.getString("approval_policy");
            String model = parsed.getString("model");
            return AgentProfile.custom(name, description, developerInstructions, sandboxMode, approvalPolicy, model);
        } catch (Exception e) {
            log.warn("skip invalid agent config {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static String filenameWithoutToml(Path file) {
        String filename = file.getFileName().toString();
        return filename.endsWith(".toml") ? filename.substring(0, filename.length() - 5) : filename;
    }

    private static boolean isValidName(String name) {
        return name != null && name.matches("[a-z0-9][a-z0-9-]*");
    }
}
