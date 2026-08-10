package com.mindcli.tool.builtin;

import com.mindcli.tool.ToolRegistry;
import com.mindcli.tool.registry.ToolRegistrar;
import com.mindcli.tool.registry.ToolRegistrationContext;

public class SnapshotToolRegistrar implements ToolRegistrar {
    @Override
    public void register(ToolRegistrationContext context) {
        ToolRegistrationContext.ToolExecutors executors = context.executors();
        context.register(new ToolRegistry.Tool(
                "revert_turn",
                "恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照。会先记录 pre-restore 快照；属于高危写入操作，必须经 HITL 审批。",
                context.parameters(new ToolRegistrationContext.Parameter("offset", "integer", "要恢复的 pre-turn 快照序号，1 表示最近一次任务开始前", false)),
                executors::revertTurnTool
        ));
    }
}
