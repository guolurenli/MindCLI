package com.mindcli.tool.builtin;

import com.mindcli.tool.ToolRegistry;
import com.mindcli.tool.registry.ToolRegistrar;
import com.mindcli.tool.registry.ToolRegistrationContext;

public class CodeToolRegistrar implements ToolRegistrar {
    @Override
    public void register(ToolRegistrationContext context) {
        ToolRegistrationContext.ToolExecutors executors = context.executors();
        context.register(new ToolRegistry.Tool(
                "create_project",
                "创建新项目结构",
                context.parameters(
                        new ToolRegistrationContext.Parameter("name", "string", "项目名称", true),
                        new ToolRegistrationContext.Parameter("type", "string", "项目类型 (java/python/node)", true)
                ),
                executors::createProjectTool
        ));
    }
}
