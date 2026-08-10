package com.mindcli.tool.builtin;

import com.mindcli.tool.ToolRegistry;
import com.mindcli.tool.registry.ToolRegistrar;
import com.mindcli.tool.registry.ToolRegistrationContext;

public class MemoryToolRegistrar implements ToolRegistrar {
    @Override
    public void register(ToolRegistrationContext context) {
        ToolRegistrationContext.ToolExecutors executors = context.executors();
        context.register(new ToolRegistry.Tool(
                "save_memory",
                "当且仅当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时调用，把精炼事实写入长期记忆；scope 默认 project，跨项目偏好才用 global；不要保存一次性任务请求、临时文件名或模型猜测。",
                context.parameters(
                        new ToolRegistrationContext.Parameter("fact", "string", "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用", true),
                        new ToolRegistrationContext.Parameter("scope", "string", "记忆作用域：project 或 global。默认 project；跨项目长期偏好才用 global", false)
                ),
                executors::saveMemoryTool
        ));
    }
}
