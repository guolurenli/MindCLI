package com.mindcli.platform.hitl;

import java.util.Locale;
import java.util.Set;

/**
 * 危险操作识别策略 - 基于静态规则判断哪些工具调用需要人工确认
 *
 * 设计原则：
 * - 读取类操作（read_file、list_dir、glob_files、grep_code）不需要确认，无副作用
 * - 写入/执行类操作（write_file、execute_command）需要确认，有潜在破坏性
 * - create_project 属于写入操作，默认需要确认
 * - revert_turn 会批量回写工作区文件，默认需要确认
 * - MCP 工具来自外部 server，默认都需要确认
 */
public class ApprovalPolicy {

    // 需要人工确认的工具集合
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "write_file",
            "execute_command",
            "create_project",
            "revert_turn"
    );

    private static final ThreadLocal<String> CURRENT_POLICY = new ThreadLocal<>();

    private ApprovalPolicy() {
    }

    /**
     * 判断该工具调用是否需要人工确认。
     *
     * 优先使用当前线程上由 {@link #applyApprovalPolicy(String)} 绑定的 agent 策略；
     * 未绑定时回退 on-request。这样 HITL 审批链路可以按每个子代理的 approval_policy
     * 差异化生效，而无需把策略一路传进 ToolRegistry。
     */
    public static boolean requiresApproval(String toolName) {
        return requiresApproval(toolName, CURRENT_POLICY.get());
    }

    /**
     * 在当前线程内绑定 agent 的 approval_policy（工具分发前调用，分发结束后 clearApprovalPolicy）。
     */
    public static void applyApprovalPolicy(String approvalPolicy) {
        CURRENT_POLICY.set(approvalPolicy);
    }

    /**
     * 清除当前线程绑定的 approval_policy。
     */
    public static void clearApprovalPolicy() {
        CURRENT_POLICY.remove();
    }

    /**
     * 判断该工具调用是否需要人工确认（按 agent 的 approval_policy 覆盖）
     *
     * - never      从不审批
     * - untrusted  每步都审批（连只读都问）
     * - on-request 默认：危险工具 / MCP 才审批
     */
    public static boolean requiresApproval(String toolName, String approvalPolicy) {
        String policy = approvalPolicy == null ? "on-request" : approvalPolicy.trim().toLowerCase(Locale.ROOT);
        return switch (policy) {
            case "never" -> false;
            case "untrusted" -> true;
            default -> DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);
        };
    }

    /**
     * 获取危险等级描述
     */
    public static String getDangerLevel(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "🔴 高危";
            case "revert_turn" -> "🔴 高危";
            case "write_file" -> "🟡 中危";
            case "create_project" -> "🟡 中危";
            default -> isMcpTool(toolName) ? "🟡 MCP" : "🟢 安全";
        };
    }

    /**
     * 获取危险操作的风险说明
     */
    public static String getRiskDescription(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态";
            case "revert_turn" -> "将按 Side-Git 快照批量恢复工作区文件，可能覆盖当前未保存修改";
            case "write_file" -> "将写入或覆盖文件内容，原有内容将丢失";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            default -> isMcpTool(toolName)
                    ? "将调用外部 MCP server 提供的工具，可能访问网络、文件或第三方服务"
                    : "安全的只读操作";
        };
    }

    /**
     * 获取所有需要审批的工具名集合（用于测试和展示）
     */
    public static Set<String> getDangerousTools() {
        return DANGEROUS_TOOLS;
    }

    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    public static String mcpServerName(String toolName) {
        if (!isMcpTool(toolName)) {
            return null;
        }
        String[] parts = toolName.split("__", 3);
        return parts.length >= 2 ? parts[1] : null;
    }
}
