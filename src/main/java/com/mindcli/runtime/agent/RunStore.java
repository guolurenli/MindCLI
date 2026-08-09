package com.mindcli.runtime.agent;

import java.util.List;

public interface RunStore {
    void append(AgentRunEvent event);

    List<AgentRunEvent> events(String runId);
}
