package com.mindcli.tool.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindcli.tool.ToolRegistry;

import java.util.Map;

public interface ToolRegistrationContext {
    ToolExecutors executors();

    void register(ToolRegistry.Tool tool);

    JsonNode parameters(Parameter... parameters);

    record Parameter(String name, String type, String description, boolean required) {}

    interface ToolExecutors {
        String readFileTool(Map<String, String> args);

        String writeFileTool(Map<String, String> args);

        String listDirTool(Map<String, String> args);

        String globFilesTool(Map<String, String> args);

        String grepCodeTool(Map<String, String> args);

        String executeCommandTool(Map<String, String> args);

        String createProjectTool(Map<String, String> args);

        String searchCodeTool(Map<String, String> args);

        String webSearchTool(Map<String, String> args);

        String webFetchTool(Map<String, String> args);

        String browserConnectTool(Map<String, String> args);

        String browserDisconnectTool(Map<String, String> args);

        String browserStatusTool(Map<String, String> args);

        String loadSkillTool(Map<String, String> args);

        String saveMemoryTool(Map<String, String> args);

        String revertTurnTool(Map<String, String> args);
    }
}
