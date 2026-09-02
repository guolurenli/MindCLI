package com.mindcli.runtime.run.loop;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import com.mindcli.platform.llm.LlmClient;

import java.util.List;

public record AgentTurnResult(
        AgentTurnStatus status,
        int iteration,
        LlmClient.ChatResponse response,
        List<ToolOutcome> toolOutcomes,
        String errorMessage,
        String exitDescription,
        com.mindcli.agent.AgentBudget.ExitReason exitReason
) {
    public AgentTurnResult {
        status = status == null ? AgentTurnStatus.FAILED : status;
        toolOutcomes = toolOutcomes == null ? List.of() : List.copyOf(toolOutcomes);
        errorMessage = errorMessage == null ? "" : errorMessage;
        exitDescription = exitDescription == null ? "" : exitDescription;
        exitReason = exitReason == null ? com.mindcli.agent.AgentBudget.ExitReason.WITHIN_BUDGET : exitReason;
    }

    public boolean completed() {
        return status == AgentTurnStatus.COMPLETED;
    }

    public boolean hasToolCalls() {
        return status == AgentTurnStatus.TOOL_CALLS;
    }
}
