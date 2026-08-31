package com.mindcli.capability.tool.builtin;

import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.registry.ToolRegistrar;
import com.mindcli.capability.tool.registry.ToolRegistrationContext;

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

        context.register(new ToolRegistry.Tool(
                "search_memory",
                "按关键词检索长期记忆目录（只读、确定性、按当前项目作用域过滤）；返回 ID、标题、摘要，不直接返回正文",
                context.parameters(
                        new ToolRegistrationContext.Parameter("query", "string", "要检索的关键词或短语", true),
                        new ToolRegistrationContext.Parameter("limit", "integer", "最多返回条数，默认 5，最大 20", false)
                ),
                executors::searchMemoryTool
        ));

        context.register(new ToolRegistry.Tool(
                "read_memory",
                "按长期记忆 ID 读取一条记忆正文（只读、自动校验当前项目作用域和治理状态）",
                context.parameters(
                        new ToolRegistrationContext.Parameter("id", "string", "search_memory 返回的记忆 ID", true)
                ),
                executors::readMemoryTool
        ));
    }
}
