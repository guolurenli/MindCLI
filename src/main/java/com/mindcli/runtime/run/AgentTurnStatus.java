package com.mindcli.runtime.run;

public enum AgentTurnStatus {
    COMPLETED,
    TOOL_CALLS,
    FAILED,
    CANCELLED,
    BUDGET_EXHAUSTED
}
