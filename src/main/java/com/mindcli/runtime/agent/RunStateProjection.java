package com.mindcli.runtime.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record RunStateProjection(
        RunStateStatus status,
        AgentRunEventType lastEventType,
        AgentRunEventType lastCompletedEventType,
        Map<String, String> lastCompletedAttributes,
        Map<String, String> lastEventAttributes,
        List<AgentRunEvent> events
) {
    public RunStateProjection {
        status = status == null ? RunStateStatus.MANUAL : status;
        lastCompletedAttributes = lastCompletedAttributes == null ? Map.of() : Map.copyOf(lastCompletedAttributes);
        lastEventAttributes = lastEventAttributes == null ? Map.of() : Map.copyOf(lastEventAttributes);
        events = events == null ? List.of() : List.copyOf(events);
    }

    public boolean isTerminal() {
        return status == RunStateStatus.TERMINAL;
    }

    public static RunStateProjection empty() {
        return new RunStateProjection(RunStateStatus.MANUAL, null, null, Map.of(), Map.of(), List.of());
    }
}
