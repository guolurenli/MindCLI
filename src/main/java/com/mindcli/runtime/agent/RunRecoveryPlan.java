package com.mindcli.runtime.agent;

import java.util.List;
import java.util.Map;

public record RunRecoveryPlan(
        String runId,
        RunStateStatus stateStatus,
        boolean resumable,
        boolean terminal,
        boolean manual,
        AgentRunEventType lastEventType,
        AgentRunEventType lastCompletedEventType,
        Map<String, String> lastCompletedAttributes,
        List<AgentRunEvent> events
) {
    public RunRecoveryPlan {
        lastCompletedAttributes = lastCompletedAttributes == null ? Map.of() : Map.copyOf(lastCompletedAttributes);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
