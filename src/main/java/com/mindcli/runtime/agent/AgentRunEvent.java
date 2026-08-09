package com.mindcli.runtime.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record AgentRunEvent(
        String runId,
        AgentRunEventType type,
        Instant timestamp,
        Map<String, String> attributes
) {
    public AgentRunEvent {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        type = Objects.requireNonNull(type, "type");
        timestamp = timestamp == null ? Instant.now() : timestamp;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static AgentRunEvent of(AgentRunContext context, AgentRunEventType type) {
        return of(context, type, Map.of());
    }

    public static AgentRunEvent of(AgentRunContext context, AgentRunEventType type,
                                   Map<String, String> attributes) {
        Objects.requireNonNull(context, "context");
        Map<String, String> eventAttributes = new LinkedHashMap<>();
        eventAttributes.put("mode", context.mode().name());
        eventAttributes.put("workspace", context.workspace());
        if (attributes != null) {
            eventAttributes.putAll(attributes);
        }
        return new AgentRunEvent(context.runId(), type, Instant.now(), eventAttributes);
    }
}
