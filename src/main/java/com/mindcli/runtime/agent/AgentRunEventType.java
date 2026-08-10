package com.mindcli.runtime.agent;

public enum AgentRunEventType {
    RUN_STARTED,
    MODE_SELECTED,
    AGENT_SELECTED,
    SNAPSHOT_CREATED,
    LLM_RESPONSE,
    TOOL_CALL_REQUESTED,
    TOOL_OUTCOME,
    BUDGET_EXHAUSTED,
    RUN_CANCELLED,
    RUN_FINISHED,
    RUN_FAILED
}
