package com.mindcli.tool.builtin;

import com.mindcli.tool.ToolRegistry;
import com.mindcli.tool.registry.ToolRegistrar;
import com.mindcli.tool.registry.ToolRegistrationContext;

public class SkillToolRegistrar implements ToolRegistrar {
    @Override
    public void register(ToolRegistrationContext context) {
        ToolRegistrationContext.ToolExecutors executors = context.executors();
        context.register(new ToolRegistry.Tool(
                "load_skill",
                "Load the full SKILL.md body for an indexed skill (see the \"可用 Skills\" section in this system prompt). Call this when a skill's description or 触发场景 matches the current task. The body is returned as this tool's result and takes effect immediately. Don't reload the same skill twice in one session.",
                context.parameters(new ToolRegistrationContext.Parameter("name", "string", "the exact kebab-case skill name (e.g. web-access)", true)),
                executors::loadSkillTool
        ));
    }
}
