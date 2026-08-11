package com.mindcli.capability.tool.builtin;

import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.registry.ToolRegistrar;
import com.mindcli.capability.tool.registry.ToolRegistrationContext;

public class ShellToolRegistrar implements ToolRegistrar {
    @Override
    public void register(ToolRegistrationContext context) {
        ToolRegistrationContext.ToolExecutors executors = context.executors();
        context.register(new ToolRegistry.Tool(
                "execute_command",
                "在当前项目目录中执行短时 Shell 命令（默认 60 秒超时，不允许全盘扫描）",
                context.parameters(new ToolRegistrationContext.Parameter("command", "string", "要执行的命令", true)),
                executors::executeCommandTool
        ));
    }
}
