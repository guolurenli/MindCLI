package com.mindcli.app.cli;

import com.mindcli.agent.Agent;
import com.mindcli.platform.hitl.SwitchableHitlHandler;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.capability.mcp.McpServerManager;
import com.mindcli.capability.mcp.McpServerStatus;
import com.mindcli.platform.render.StatusInfo;
import com.mindcli.capability.skill.Skill;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.platform.render.terminal.AnsiStyle;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class CliStartupView {

    private CliStartupView() {
    }

    record StartupScreenInfo(
            String model,
            String provider,
            long mcpReady,
            int mcpTotal,
            int mcpTools,
            int skillsEnabled,
            int skillsTotal,
            String skillsSummary,
            String note
    ) {
    }

    static void printStartupHints(PrintStream out, List<String> hints) {
        out.println("💡 提示:");
        for (String hint : hints) {
            out.println("   - " + hint);
        }
        out.println();
    }

    static StartupScreenInfo startupScreenInfo(LlmClient llmClient,
                                               McpServerManager mcpServerManager,
                                               SkillRegistry skillRegistry,
                                               String note) {
        long ready = mcpServerManager.servers().stream()
                .filter(server -> server.status() == McpServerStatus.READY)
                .count();
        int total = mcpServerManager.servers().size();
        int tools = mcpServerManager.servers().stream()
                .mapToInt(server -> server.tools().size())
                .sum();
        int skillTotal = skillRegistry.allSkills().size();
        int skillEnabled = skillRegistry.enabledSkills().size();
        String skillsSummary = skillEnabled <= 2
                ? skillRegistry.enabledSkills().stream().map(Skill::name).collect(Collectors.joining(","))
                : "";
        return new StartupScreenInfo(
                llmClient.getModelName(),
                llmClient.getProviderName(),
                ready,
                total,
                tools,
                skillEnabled,
                skillTotal,
                skillsSummary,
                note == null ? "" : note.trim()
        );
    }

    static StatusInfo statusInfo(LlmClient llmClient,
                                 SwitchableHitlHandler hitlHandler,
                                 String phase,
                                 McpServerManager mcpServerManager,
                                 SkillRegistry skillRegistry) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        StatusInfo base = "idle".equals(normalizedPhase)
                ? StatusInfo.idle(llmClient.getModelName(), llmClient.maxContextWindow(), hitlHandler.isEnabled())
                : StatusInfo.active(llmClient.getModelName(), llmClient.maxContextWindow(),
                hitlHandler.isEnabled(), normalizedPhase);
        return base.withEnvironment(mcpStatusSummary(mcpServerManager), skillStatusSummary(skillRegistry));
    }

    static StatusInfo statusInfo(Agent reactAgent,
                                 McpServerManager mcpServerManager,
                                 SkillRegistry skillRegistry,
                                 String phase) {
        StatusInfo base = reactAgent.currentStatus(phase);
        return base.withEnvironment(mcpStatusSummary(mcpServerManager), skillStatusSummary(skillRegistry));
    }

    static String mcpStatusSummary(McpServerManager mcpServerManager) {
        if (mcpServerManager == null || mcpServerManager.servers().isEmpty()) {
            return "MCP 0";
        }
        long ready = mcpServerManager.servers().stream()
                .filter(server -> server.status() == McpServerStatus.READY)
                .count();
        return "MCP " + ready + "/" + mcpServerManager.servers().size();
    }

    static String skillStatusSummary(SkillRegistry skillRegistry) {
        if (skillRegistry == null || skillRegistry.allSkills().isEmpty()) {
            return "Skill 0";
        }
        return "Skill " + skillRegistry.enabledSkills().size() + "/" + skillRegistry.allSkills().size();
    }

    static void printStartupScreen(PrintStream out, String version, StartupScreenInfo info) {
        for (String line : startupScreenLines(version, info)) {
            out.println(line);
        }
    }

    static List<String> startupScreenLines(String version, StartupScreenInfo info) {
        List<String> lines = new ArrayList<>(startupBannerLines(version, info));
        lines.add("");
        return lines;
    }

    static List<String> startupBannerLines(String version) {
        return startupBannerLines(version, new StartupScreenInfo(
                "auto",
                "model",
                0,
                0,
                0,
                0,
                0,
                "",
                ""));
    }

    static List<String> startupBannerLines(String version, StartupScreenInfo info) {
        String model = info.model() == null || info.model().isBlank() ? "auto" : info.model();
        String provider = info.provider() == null || info.provider().isBlank() ? "model" : info.provider();
        String mcp = info.mcpTotal() <= 0
                ? "0/0 offline"
                : info.mcpReady() + "/" + info.mcpTotal() + " online · " + info.mcpTools() + " tools";
        String skills = info.skillsTotal() <= 0
                ? "0/0 idle"
                : info.skillsEnabled() + "/" + info.skillsTotal() + " armed"
                  + (info.skillsSummary().isEmpty() ? "" : " · " + info.skillsSummary());
        List<String> lines = new ArrayList<>(List.of(
                "",
                "   " + AnsiStyle.section("MindCLI //") + " " + AnsiStyle.heading("v" + version),
                startupInfoLine("Model", model + " / " + provider, "Runtime", "ReAct"),
                startupInfoLine("Mcp", mcp),
                startupInfoLine("Skills", skills),
                "",
                "   " + startupHint("Command", "/ palette")
                        + "    " + startupHint("Context", "@path")
                        + "    " + startupHint("Image", "@image:")
                        + "    " + startupHint("Ctrl+O", "expand")
        ));
        if (info.note() != null && !info.note().isBlank()) {
            lines.add("");
            lines.add(AnsiStyle.subtle(info.note().replace('\n', ' ')));
        }
        return lines;
    }

    private static String startupInfoLine(String label, String value) {
        return "   " + AnsiStyle.codeLabel(padLabel(label)) + " " + AnsiStyle.heading(value);
    }

    private static String startupInfoLine(String firstLabel, String firstValue, String secondLabel, String secondValue) {
        return "   " + AnsiStyle.codeLabel(padLabel(firstLabel)) + " " + AnsiStyle.heading(firstValue)
                + "        " + AnsiStyle.codeLabel(secondLabel) + "  " + AnsiStyle.section(secondValue);
    }

    private static String startupHint(String label, String value) {
        return AnsiStyle.codeLabel(label) + " " + AnsiStyle.subtle(value);
    }

    private static String padLabel(String label) {
        String safe = label == null ? "" : label;
        return safe.length() >= 7 ? safe : safe + " ".repeat(7 - safe.length());
    }
}
