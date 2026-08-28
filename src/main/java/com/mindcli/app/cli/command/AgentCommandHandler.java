package com.mindcli.app.cli.command;

import com.mindcli.agent.AgentRole;
import com.mindcli.agent.profile.AgentProfile;
import com.mindcli.agent.profile.AgentToolPolicy;
import org.jline.reader.LineReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /agent 命令组的展示、创建与直连执行解析逻辑。
 * 抽出独立类便于单测；Main.java 只负责 dispatch + 直连运行（需要 llmClient / toolRegistry 等运行时对象）。
 */
public final class AgentCommandHandler {

    private static final Set<String> SANDBOX_MODES =
            Set.of("workspace-write", "read-only", "danger-full-access");
    private static final Set<String> APPROVAL_POLICIES =
            Set.of("on-request", "never", "untrusted");

    private AgentCommandHandler() {
    }

    /** /agent 负载解析结果：create 为交互创建，name 为子代理名，task 为直连任务。 */
    public record AgentCommandTarget(boolean create, String name, String task) {
    }

    /**
     * 解析 /agent 的负载：
     * - 空            → 列表
     * - "create"      → 交互创建
     * - "name"        → 查看详情
     * - "name 任务"   → 直连执行
     */
    public static AgentCommandTarget parse(String payload) {
        String trimmed = payload == null ? "" : payload.trim();
        if (trimmed.isEmpty()) {
            return new AgentCommandTarget(false, null, null);
        }
        if (trimmed.equals("create")) {
            return new AgentCommandTarget(true, null, null);
        }
        int space = trimmed.indexOf(' ');
        String name = space < 0 ? trimmed : trimmed.substring(0, space);
        String task = space < 0 ? null : trimmed.substring(space + 1).trim();
        if (task != null && task.isEmpty()) {
            task = null;
        }
        return new AgentCommandTarget(false, name, task);
    }

    public static AgentProfile find(List<AgentProfile> profiles, String name) {
        if (profiles == null || name == null) {
            return null;
        }
        return profiles.stream()
                .filter(profile -> profile.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    public static String list(List<AgentProfile> profiles) {
        List<AgentProfile> list = profiles == null ? List.of() : profiles;
        if (list.isEmpty()) {
            return "🤖 子代理: 未发现可用子代理\n   /agent create 创建自定义子代理";
        }
        StringBuilder sb = new StringBuilder("🤖 子代理（" + list.size() + " 个）\n");
        for (AgentProfile profile : list) {
            sb.append(String.format("  %-20s %-8s %s%n",
                    profile.name(), roleLabel(profile.role()), abbreviate(profile.description(), 60)));
        }
        sb.append('\n')
                .append("提示：\n")
                .append("  /agent <name> 查看详情\n")
                .append("  /agent <name> <任务> 直连执行\n")
                .append("  /agent create 交互式创建自定义子代理");
        return sb.toString();
    }

    public static String detail(List<AgentProfile> profiles, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供子代理名，例如 /agent code-reviewer";
        }
        AgentProfile profile = find(profiles, name);
        if (profile == null) {
            return "❌ 未找到子代理: " + name + "（用 /agent 查看列表）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🤖 子代理: ").append(profile.name())
                .append(" (").append(roleLabel(profile.role())).append(")\n");
        sb.append("  description: ").append(profile.description()).append('\n');
        sb.append("  permissionMode: ").append(profile.permissionMode()).append('\n');
        sb.append("  tools: ").append(AgentToolPolicy.formatTools(profile.tools())).append('\n');
        if (!profile.deniedTools().isEmpty()) {
            sb.append("  deniedTools: ").append(AgentToolPolicy.formatTools(profile.deniedTools())).append('\n');
        }
        if (!profile.commandAllowlist().isEmpty()) {
            sb.append("  commandAllowlist: ").append(AgentToolPolicy.formatTools(profile.commandAllowlist())).append('\n');
        }
        sb.append("  approvalPolicy: ").append(profile.approvalPolicy()).append('\n');
        sb.append("  model: ").append(profile.model()).append('\n');
        sb.append("  maxConcurrency: ").append(profile.maxConcurrency()).append('\n');
        if (!profile.developerInstructions().isBlank()) {
            sb.append("\n--- developer_instructions ---\n");
            sb.append(profile.developerInstructions()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 交互式创建自定义子代理并写入 .mindcli/agents/&lt;name&gt;.toml。
     */
    public static String create(Path projectRoot, LineReader lineReader) {
        if (lineReader == null) {
            return "❌ 交互式创建需要可用的输入流";
        }
        String name = prompt(lineReader, "name（小写字母/数字/连字符，例如 code-reviewer）: ");
        if (name == null || name.isBlank()) {
            return "❌ 已取消：name 不能为空";
        }
        if (!isValidName(name)) {
            return "❌ 无效 name: " + name + "（仅允许 [a-z0-9][a-z0-9-]*）";
        }
        String description = prompt(lineReader, "description（一句话说明，供 /team 规划选人）: ");
        if (description == null || description.isBlank()) {
            return "❌ 已取消：description 不能为空";
        }
        String instructions = promptMultiline(lineReader,
                "developer_instructions（系统提示词，可多行，输入空行结束）: ");
        if (instructions == null || instructions.isBlank()) {
            return "❌ 已取消：developer_instructions 不能为空";
        }
        String sandboxMode = prompt(lineReader,
                "sandbox_mode [workspace-write / read-only / danger-full-access]，默认 workspace-write: ");
        if (sandboxMode == null || sandboxMode.isBlank()) {
            sandboxMode = "workspace-write";
        }
        sandboxMode = sandboxMode.trim().toLowerCase(Locale.ROOT);
        if (!SANDBOX_MODES.contains(sandboxMode)) {
            return "❌ 无效 sandbox_mode: " + sandboxMode;
        }
        String approvalPolicy = prompt(lineReader,
                "approval_policy [on-request / never / untrusted]，默认 on-request: ");
        if (approvalPolicy == null || approvalPolicy.isBlank()) {
            approvalPolicy = "on-request";
        }
        approvalPolicy = approvalPolicy.trim().toLowerCase(Locale.ROOT);
        if (!APPROVAL_POLICIES.contains(approvalPolicy)) {
            return "❌ 无效 approval_policy: " + approvalPolicy;
        }
        String model = prompt(lineReader, "model（默认 auto）: ");
        if (model == null || model.isBlank()) {
            model = "auto";
        }

        String toml = renderToml(name, description, instructions, sandboxMode, approvalPolicy, model);
        Path agentsDir = projectRoot == null
                ? Path.of(".mindcli", "agents")
                : projectRoot.resolve(".mindcli").resolve("agents");
        try {
            Files.createDirectories(agentsDir);
            Path file = agentsDir.resolve(name + ".toml");
            if (Files.exists(file)) {
                return "❌ 已存在: " + file.toAbsolutePath() + "（/agent create 不会覆盖）";
            }
            Files.writeString(file, toml);
            return "✅ 已创建自定义子代理 " + name
                    + "\n   路径: " + file.toAbsolutePath()
                    + "\n   /agent " + name + " 查看详情；/agent " + name + " <任务> 直连执行"
                    + "\n   提示：/team 规划会在下一次进入时读到该子代理";
        } catch (IOException e) {
            return "❌ 写入失败: " + e.getMessage();
        }
    }

    static String renderToml(String name, String description, String instructions,
                             String sandboxMode, String approvalPolicy, String model) {
        return """
                name = "%s"
                description = "%s"
                developer_instructions = "%s"
                sandbox_mode = "%s"
                approval_policy = "%s"
                model = "%s"
                """.formatted(
                escapeToml(name),
                escapeToml(description),
                escapeToml(instructions),
                escapeToml(sandboxMode),
                escapeToml(approvalPolicy),
                escapeToml(model));
    }

    private static String prompt(LineReader lineReader, String prompt) {
        try {
            String value = lineReader.readLine(prompt);
            return value == null ? null : value.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 交互式收集多行文本，直到用户输入空行（或 EOF）结束。首行即空返回空串，交由调用方拒绝。
     */
    private static String promptMultiline(LineReader lineReader, String firstPrompt) {
        if (lineReader == null) {
            return null;
        }
        try {
            String first = lineReader.readLine(firstPrompt);
            if (first == null) {
                return null;
            }
            if (first.isBlank()) {
                return "";
            }
            java.util.List<String> lines = new java.util.ArrayList<>();
            lines.add(first.trim());
            while (true) {
                String line = lineReader.readLine("  ... ");
                if (line == null || line.isBlank()) {
                    break;
                }
                lines.add(line.trim());
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isValidName(String name) {
        return name != null && name.matches("[a-z0-9][a-z0-9-]*");
    }

    private static String roleLabel(AgentRole role) {
        return switch (role) {
            case EXPLORER -> "explorer";
            case WORKER -> "worker";
            case CUSTOM -> "custom";
        };
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String escapeToml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
