package com.mindcli.runtime.run.dispatch;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import com.mindcli.platform.llm.LlmClient;

import java.util.List;

@FunctionalInterface
public interface ToolBatchExecutor {
    List<ToolOutcome> dispatch(List<LlmClient.ToolCall> toolCalls, AgentRunContext context);
}
