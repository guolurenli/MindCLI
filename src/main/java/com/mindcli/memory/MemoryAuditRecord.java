package com.mindcli.memory;

import java.util.Map;

public record MemoryAuditRecord(
        String eventId,
        String type,
        String timestamp,
        Map<String, String> attributes
) {
    public MemoryAuditRecord {
        eventId = eventId == null ? "" : eventId;
        type = type == null ? "" : type;
        timestamp = timestamp == null ? "" : timestamp;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
