package com.mindcli.runtime.run;

import com.mindcli.capability.tool.ToolRegistry;

import java.util.List;

@FunctionalInterface
interface ToolBatchExecutor {
    List<ToolRegistry.ToolExecutionResult> execute(List<ToolRegistry.ToolInvocation> invocations);
}
