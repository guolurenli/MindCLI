package com.mindcli.runtime.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AgentRunEvent(
        String runId,
        AgentRunEventType type,
        Instant timestamp,
        String eventId,
        long seq,
        Map<String, String> attributes
) {
    public AgentRunEvent(String runId, AgentRunEventType type, Instant timestamp,
                         Map<String, String> attributes) {
        this(runId, type, timestamp, null, 0L, attributes);
    }

    public AgentRunEvent {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (!isSafeRunId(runId)) {
            throw new IllegalArgumentException("runId contains unsafe path characters: " + runId);
        }
        type = Objects.requireNonNull(type, "type");
        timestamp = timestamp == null ? Instant.now() : timestamp;
        eventId = eventId == null || eventId.isBlank() ? "evt_" + UUID.randomUUID() : eventId;
        if (seq < 0) {
            throw new IllegalArgumentException("seq must not be negative");
        }
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
        eventAttributes.put("runId", context.runId());
        eventAttributes.putAll(context.metadata());
        if (attributes != null) {
            eventAttributes.putAll(attributes);
        }
        return new AgentRunEvent(context.runId(), type, Instant.now(), eventAttributes);
    }

    public AgentRunEvent withSeq(long seq) {
        return new AgentRunEvent(runId, type, timestamp, eventId, seq, attributes);
    }

    private static boolean isSafeRunId(String runId) {
        return runId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                && !runId.contains("..")
                && !runId.contains("/")
                && !runId.contains("\\");
    }
}
