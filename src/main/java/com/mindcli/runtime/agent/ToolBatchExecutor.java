package com.mindcli.runtime.agent;

import com.mindcli.tool.ToolRegistry;

import java.util.List;

@FunctionalInterface
interface ToolBatchExecutor {
    List<ToolRegistry.ToolExecutionResult> execute(List<ToolRegistry.ToolInvocation> invocations);
}
