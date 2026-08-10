package com.mindcli.runtime.run;

import java.util.List;

public interface RunStore {
    void append(AgentRunEvent event);

    List<AgentRunEvent> events(String runId);
}
