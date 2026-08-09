package com.mindcli.runtime.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryRunStore implements RunStore {
    private final ConcurrentMap<String, List<AgentRunEvent>> eventsByRunId = new ConcurrentHashMap<>();

    @Override
    public void append(AgentRunEvent event) {
        Objects.requireNonNull(event, "event");
        List<AgentRunEvent> events = eventsByRunId.computeIfAbsent(event.runId(),
                ignored -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (events) {
            long seq = event.seq() > 0 ? event.seq() : events.size() + 1L;
            events.add(event.withSeq(seq));
        }
    }

    @Override
    public List<AgentRunEvent> events(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        List<AgentRunEvent> events = eventsByRunId.get(runId);
        if (events == null) {
            return List.of();
        }
        synchronized (events) {
            return List.copyOf(events);
        }
    }
}
