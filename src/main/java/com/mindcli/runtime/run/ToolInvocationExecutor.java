package com.mindcli.runtime.run;

import com.mindcli.capability.tool.ToolExecution;
import com.mindcli.capability.tool.ToolRegistry;

@FunctionalInterface
interface ToolInvocationExecutor {
    ToolExecution execute(ToolRegistry.ToolInvocation invocation);
}
