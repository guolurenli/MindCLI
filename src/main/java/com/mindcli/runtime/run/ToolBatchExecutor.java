package com.mindcli.runtime.run;

import com.mindcli.platform.llm.LlmClient;

import java.util.List;

@FunctionalInterface
public interface ToolBatchExecutor {
    List<ToolOutcome> dispatch(List<LlmClient.ToolCall> toolCalls, AgentRunContext context);
}
