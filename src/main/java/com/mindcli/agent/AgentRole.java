package com.mindcli.agent;

/**
 * Agent 角色定义 - Multi-Agent 系统中的角色分工
 */
public enum AgentRole {
    EXPLORER("探索者", "负责只读探索代码库和项目上下文，收集证据并输出分析结论"),
    WORKER("执行者", "负责执行具体任务步骤，调用工具完成文件操作、命令执行等操作"),
    CUSTOM("自定义", "用户通过 .mindcli/agents/*.toml 定义的自定义子代理");

    private final String displayName;
    private final String description;

    AgentRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
