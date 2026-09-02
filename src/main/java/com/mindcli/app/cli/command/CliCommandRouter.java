package com.mindcli.app.cli.command;

import com.mindcli.agent.Agent;
import com.mindcli.app.cli.CliCommandParser;
import com.mindcli.app.cli.ProjectMemoryInitializer;
import com.mindcli.app.cli.interaction.CliInputSupport;
import com.mindcli.capability.browser.BrowserAuditMetadata;
import com.mindcli.capability.browser.BrowserSession;
import com.mindcli.capability.mcp.McpServerManager;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.capability.skill.SkillStateStore;
import com.mindcli.platform.config.MindCliConfig;
import com.mindcli.platform.hitl.HitlToolRegistry;
import com.mindcli.platform.hitl.SwitchableHitlHandler;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.render.Renderer;
import com.mindcli.platform.security.AuditLog;
import com.mindcli.platform.hitl.ApprovalPolicy;
import com.mindcli.runtime.run.session.SessionContext;
import com.mindcli.runtime.task.DurableTaskManager;
import com.mindcli.runtime.task.TaskCommandFormatter;
import org.jline.reader.LineReader;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Routes command-only work away from {@code Main}.
 *
 * <p>The router deliberately owns no session state.  Main still controls
 * model/mode transitions and supplies the current runtime dependencies.</p>
 */
public final class CliCommandRouter {
    private static final Set<CliCommandParser.CommandType> ROUTED_TYPES =
            EnumSet.of(
                    CliCommandParser.CommandType.MEMORY_STATUS,
                    CliCommandParser.CommandType.MEMORY_POLICY,
                    CliCommandParser.CommandType.MEMORY_PROPOSALS,
                    CliCommandParser.CommandType.MEMORY_EXPORT_AUDIT,
                    CliCommandParser.CommandType.MEMORY_APPROVE,
                    CliCommandParser.CommandType.MEMORY_REJECT,
                    CliCommandParser.CommandType.MEMORY_LIST,
                    CliCommandParser.CommandType.MEMORY_SEARCH,
                    CliCommandParser.CommandType.MEMORY_DELETE,
                    CliCommandParser.CommandType.MEMORY_CLEAR,
                    CliCommandParser.CommandType.MEMORY_SAVE,
                    CliCommandParser.CommandType.CLEAR,
                    CliCommandParser.CommandType.COMPACT,
                    CliCommandParser.CommandType.HISTORY_CLEAR,
                    CliCommandParser.CommandType.INIT_PROJECT_MEMORY,
                    CliCommandParser.CommandType.CONTEXT_STATUS,
                    CliCommandParser.CommandType.SWITCH_HITL,
                    CliCommandParser.CommandType.POLICY_STATUS,
                    CliCommandParser.CommandType.AUDIT_TAIL,
                    CliCommandParser.CommandType.BROWSER,
                    CliCommandParser.CommandType.CONFIG,
                    CliCommandParser.CommandType.AGENT,
                    CliCommandParser.CommandType.RUN_INSPECT,
                    CliCommandParser.CommandType.RUN_RESUME,
                    CliCommandParser.CommandType.SNAPSHOT,
                    CliCommandParser.CommandType.RESTORE_SNAPSHOT,
                    CliCommandParser.CommandType.EXPORT,
                    CliCommandParser.CommandType.MCP_LIST,
                    CliCommandParser.CommandType.MCP_RESTART,
                    CliCommandParser.CommandType.MCP_LOGS,
                    CliCommandParser.CommandType.MCP_DISABLE,
                    CliCommandParser.CommandType.MCP_ENABLE,
                    CliCommandParser.CommandType.MCP_RESOURCES,
                    CliCommandParser.CommandType.MCP_PROMPTS,
                    CliCommandParser.CommandType.TASK,
                    CliCommandParser.CommandType.SKILL_LIST,
                    CliCommandParser.CommandType.SKILL_SHOW,
                    CliCommandParser.CommandType.SKILL_ON,
                    CliCommandParser.CommandType.SKILL_OFF,
                    CliCommandParser.CommandType.SKILL_RELOAD,
                    CliCommandParser.CommandType.WECHAT);

    public record Context(
            PrintStream out,
            Agent reactAgent,
            Renderer renderer,
            LineReader lineReader,
            SkillRegistry skillRegistry,
            SkillStateStore skillStateStore,
            McpServerManager mcpServerManager,
            DurableTaskManager taskManager,
            WechatCliCommandHandler.WechatRuntimeController wechatRuntime,
            SessionContext sessionContext,
            SwitchableHitlHandler hitlHandler,
            Consumer<String> phaseUpdater,
            MindCliConfig config,
            Supplier<LlmClient> llmClientSupplier,
            BrowserSession browserSession,
            HitlToolRegistry hitlToolRegistry,
            java.util.function.Function<String, String> runResumer) {
        public Context {
            Objects.requireNonNull(out, "out");
        }
    }

    private final Context context;

    public CliCommandRouter(Context context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public static boolean supports(CliCommandParser.CommandType type) {
        return type != null && ROUTED_TYPES.contains(type);
    }

    /** Returns true when the command was consumed by this router. */
    public boolean dispatch(CliCommandParser.ParsedCommand command) {
        if (command == null || !supports(command.type())) {
            return false;
        }
        switch (command.type()) {
            case MEMORY_STATUS -> MemoryCommandHandler.printStatus(context.out(), context.reactAgent().getMemoryManager());
            case MEMORY_POLICY -> MemoryCommandHandler.printPolicy(context.out(), context.reactAgent().getMemoryManager());
            case MEMORY_PROPOSALS -> MemoryCommandHandler.printProposals(context.out(), context.reactAgent().getMemoryManager());
            case MEMORY_EXPORT_AUDIT -> MemoryCommandHandler.printAuditExport(context.out(), context.reactAgent().getMemoryManager());
            case MEMORY_APPROVE -> MemoryCommandHandler.printApprove(context.out(), context.reactAgent().getMemoryManager(), command.payload());
            case MEMORY_REJECT -> MemoryCommandHandler.printReject(context.out(), context.reactAgent().getMemoryManager(), command.payload());
            case MEMORY_LIST -> MemoryCommandHandler.printList(context.out(), context.reactAgent().getMemoryManager());
            case MEMORY_SEARCH -> MemoryCommandHandler.printSearch(context.out(), context.reactAgent().getMemoryManager(), command.payload());
            case MEMORY_DELETE -> MemoryCommandHandler.printDelete(context.out(), context.reactAgent().getMemoryManager(), command.payload());
            case MEMORY_CLEAR -> MemoryCommandHandler.printClear(context.out(), context.reactAgent().getMemoryManager());
            case MEMORY_SAVE -> MemoryCommandHandler.printSave(context.out(), context.reactAgent().getMemoryManager(), command.payload());
            case CLEAR -> clearConversation();
            case COMPACT -> compactConversation();
            case HISTORY_CLEAR -> {
                CliInputSupport.clearLineReaderHistory(context.lineReader());
                context.out().println("🧹 输入历史已清空\n");
            }
            case INIT_PROJECT_MEMORY -> initializeProjectMemory(command.payload());
            case CONTEXT_STATUS -> {
                context.out().println("📋 上下文状态：");
                context.out().println(context.reactAgent().getContextStatus());
                context.out().println();
            }
            case SWITCH_HITL -> switchHitl(command.payload());
            case POLICY_STATUS -> printPolicyStatus();
            case AUDIT_TAIL -> printAuditTail(command.payload());
            case BROWSER -> print(BrowserCommandHandler.handle(
                    command.payload(),
                    context.browserSession(),
                    context.mcpServerManager(),
                    context.hitlToolRegistry(),
                    context.hitlHandler()));
            case CONFIG -> handleConfig(command.payload());
            case AGENT -> {
                AgentCommandHandler.AgentCommandTarget target = AgentCommandHandler.parse(command.payload());
                if (target.task() != null) {
                    return false;
                }
                String projectPath = context.reactAgent().getToolRegistry().getProjectPath();
                if (target.create()) {
                    context.out().println(AgentCommandHandler.create(Path.of(projectPath), context.lineReader()));
                } else {
                    var profiles = com.mindcli.agent.profile.AgentProfileLoader.load(Path.of(projectPath));
                    if (target.name() == null) {
                        context.out().println(AgentCommandHandler.list(profiles));
                    } else {
                        context.out().println(AgentCommandHandler.detail(profiles, target.name()));
                    }
                }
            }
            case RUN_INSPECT -> RunCommandHandler.printRunInspect(context.out(), context.reactAgent().runStore(), command.payload());
            case RUN_RESUME -> RunCommandHandler.printRunResume(context.out(), context.reactAgent().runStore(), command.payload(), context.runResumer());
            case SNAPSHOT -> SnapshotCommandHandler.printSnapshotCommand(
                    context.out(), context.reactAgent().getToolRegistry().getSnapshotService(), command.payload());
            case RESTORE_SNAPSHOT -> SnapshotCommandHandler.printRestoreCommand(
                    context.out(), context.reactAgent().getToolRegistry().getSnapshotService(), command.payload());
            case EXPORT -> ExportCommandHandler.printExportCommand(context.out(), context.reactAgent());
            case MCP_LIST -> print(context.mcpServerManager().formatStatus());
            case MCP_RESTART -> {
                print(context.mcpServerManager().restart(command.payload()));
                refreshIdleStatus();
            }
            case MCP_LOGS -> print(context.mcpServerManager().logs(command.payload()));
            case MCP_DISABLE -> {
                print(context.mcpServerManager().disable(command.payload()));
                refreshIdleStatus();
            }
            case MCP_ENABLE -> {
                print(context.mcpServerManager().enable(command.payload()));
                refreshIdleStatus();
            }
            case MCP_RESOURCES -> print(context.mcpServerManager().resources(command.payload()));
            case MCP_PROMPTS -> print(context.mcpServerManager().prompts(command.payload()));
            case TASK -> print(TaskCommandFormatter.handle(context.taskManager(), command.payload()));
            case SKILL_LIST -> context.out().println(SkillCommandHandler.list(context.skillRegistry()));
            case SKILL_SHOW -> context.out().println(SkillCommandHandler.show(context.skillRegistry(), command.payload()));
            case SKILL_ON -> {
                context.out().println(SkillCommandHandler.enable(
                        context.skillRegistry(), context.skillStateStore(), command.payload()));
                refreshIdleStatus();
            }
            case SKILL_OFF -> {
                context.out().println(SkillCommandHandler.disable(
                        context.skillRegistry(), context.skillStateStore(), command.payload()));
                refreshIdleStatus();
            }
            case SKILL_RELOAD -> {
                context.skillRegistry().reload();
                context.out().println("🔄 已重新扫描 skill 目录");
                context.out().println(SkillCommandHandler.startupSummary(context.skillRegistry()));
                context.out().println("✅ 下一轮 LLM 调用生效");
                refreshIdleStatus();
            }
            case WECHAT -> context.out().println(WechatCliCommandHandler.handleWechatCommand(
                    command.payload(), context.lineReader(), context.renderer(), context.out(), context.wechatRuntime()));
            default -> throw new IllegalStateException("Unsupported routed command: " + command.type());
        }
        return true;
    }

    private void print(String result) {
        context.out().println(result);
        context.out().println();
    }

    private void refreshIdleStatus() {
        updatePhase("idle");
    }

    private void updatePhase(String phase) {
        if (context.phaseUpdater() != null) {
            context.phaseUpdater().accept(phase);
        }
    }

    private void clearConversation() {
        context.reactAgent().clearHistory();
        context.sessionContext().clear();
        context.hitlHandler().clearApprovedAll();
        refreshIdleStatus();
        context.out().println("🗑️ 当前对话历史已清空，长期记忆保持不变\n");
    }

    private void compactConversation() {
        updatePhase("compacting");
        boolean activityPanel = context.renderer().supportsActivityPanel();
        if (activityPanel) {
            context.renderer().beginActivity("Compacting conversation", "正在整理早期对话并生成摘要");
        } else {
            context.out().println("⏳ 压缩中，等一下下哦...\n");
        }
        Agent.CompactionResult result;
        try {
            result = context.reactAgent().compactHistoryNow();
        } finally {
            if (activityPanel) {
                context.renderer().endActivity();
            }
            refreshIdleStatus();
        }
        if (result.error() != null && !result.error().isBlank()) {
            context.out().println("❌ 手动压缩失败: " + result.error() + "\n");
        } else if (result.compacted()) {
            context.out().printf("📦 已手动压缩历史上下文: %,d -> %,d tokens%n%n",
                    result.beforeTokens(), result.afterTokens());
        } else {
            context.out().println("📭 当前没有需要压缩的历史上下文\n");
        }
    }

    private void initializeProjectMemory(String payload) {
        boolean force = payload != null && payload.trim().equalsIgnoreCase("--force");
        if (payload != null && !payload.isBlank() && !force) {
            context.out().println("❌ 未知 /init 参数: " + payload);
            context.out().println("   用法: /init 或 /init --force\n");
            return;
        }
        try {
            Path projectRoot = Path.of(context.reactAgent().getToolRegistry().getProjectPath());
            ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(projectRoot, force);
            if (result.written()) {
                context.out().println("✅ " + result.message());
                context.out().println("   路径: " + result.path());
                context.out().println("   这份 MIND.md 会在后续 system prompt 的 Project Context 中注入。\n");
            } else {
                context.out().println("ℹ️ " + result.message());
                context.out().println("   路径: " + result.path() + "\n");
            }
        } catch (IOException e) {
            context.out().println("❌ 生成 MIND.md 失败: " + e.getMessage() + "\n");
        }
    }

    private void switchHitl(String payload) {
        if ("on".equals(payload)) {
            context.hitlHandler().setEnabled(true);
            context.out().println("🔒 HITL 审批已启用：write_file / execute_command / create_project 执行前将请求人工确认\n");
        } else if ("off".equals(payload)) {
            context.hitlHandler().setEnabled(false);
            context.hitlHandler().clearApprovedAll();
            context.out().println("🔓 HITL 审批已关闭：危险操作将直接执行\n");
        } else {
            String status = context.hitlHandler().isEnabled() ? "启用" : "关闭";
            context.out().println("🔒 HITL 当前状态：" + status);
            context.out().println("   /hitl on  - 启用人工审批");
            context.out().println("   /hitl off - 关闭人工审批\n");
        }
        refreshIdleStatus();
    }

    private void handleConfig(String payload) {
        if (payload == null || payload.isBlank()) {
            ConfigCommandHandler.handleConfigPalette(
                    context.renderer(),
                    context.config(),
                    context.llmClientSupplier().get(),
                    context.hitlHandler(),
                    context.skillRegistry());
        } else {
            context.out().println(ConfigCommandHandler.handleConfigCommand(context.config(), payload));
            refreshIdleStatus();
        }
    }

    private void printPolicyStatus() {
        context.out().println("🛡️ 安全策略状态：");
        context.out().println("   项目根: " + context.reactAgent().getToolRegistry().getProjectPath());
        context.out().println("   危险工具: " + String.join(", ", ApprovalPolicy.getDangerousTools()) + "，以及所有 mcp__ 前缀工具");
        context.out().println("   路径围栏: 强制限定在项目根之内（read_file / write_file / list_dir / create_project）");
        context.out().println("   命令黑名单: sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown");
        context.out().println("   写入文件上限: 5MB");
        context.out().println("   命令执行上限: 60 秒，输出 8KB（截断）");
        context.out().println("   审计目录: " + context.reactAgent().getToolRegistry().getAuditLog().getAuditDir());
        context.out().println();
    }

    private void printAuditTail(String payload) {
        int requested = parseCount(payload, 10);
        var entries = context.reactAgent().getToolRegistry().getAuditLog().readRecent(requested);
        if (entries.isEmpty()) {
            context.out().println("📭 今日尚无审计记录\n");
            return;
        }
        context.out().println("📋 最近 " + entries.size() + " 条危险工具审计：");
        for (AuditLog.AuditEntry entry : entries) {
            context.out().printf("   [%s] %s %s (%dms, approver=%s)%n",
                    entry.outcome().toUpperCase(),
                    entry.timestamp(),
                    entry.tool(),
                    entry.durationMs(),
                    entry.approver());
            if (entry.reason() != null && !entry.reason().isBlank()) {
                context.out().println("        原因: " + entry.reason());
            }
            BrowserAuditMetadata metadata = entry.metadata();
            if (metadata != null) {
                context.out().println("        浏览器: mode=" + metadata.browserMode()
                        + ", sensitive=" + metadata.sensitive()
                        + (metadata.targetUrl() == null ? "" : ", url=" + metadata.targetUrl()));
            }
        }
        context.out().println();
    }

    private static int parseCount(String payload, int defaultCount) {
        if (payload == null || payload.isBlank()) {
            return defaultCount;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(payload.trim()), 100));
        } catch (NumberFormatException e) {
            return defaultCount;
        }
    }
}
