package com.mindcli.runtime.run;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunContextDeadlineTest {
    @Test
    void contextCreatesDeadlineFromOriginalStartTime() {
        String key = "mindcli.react.max.duration.seconds";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "60");
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            AgentRunContext context = new AgentRunContext("run-deadline", AgentMode.PLAN,
                    "goal", "workspace", start, Map.of());
            assertEquals(Long.toString(start.toEpochMilli() + 60_000),
                    context.metadata().get("runDeadlineEpochMillis"));
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @Test
    void copiedContextKeepsDeadlineEvenWithNewStartTime() {
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, "goal", "workspace",
                Map.of("runDeadlineEpochMillis", "12345"));
        AgentRunContext child = AgentRunContext.create(context.mode(), context.input(),
                context.workspace(), context.metadata());
        assertEquals("12345", child.metadata().get("runDeadlineEpochMillis"));
    }
}
