package com.mindcli.runtime.run.store;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RunStateProjector {
    public RunStateProjection project(List<AgentRunEvent> events) {
        if (events == null || events.isEmpty()) {
            return RunStateProjection.empty();
        }

        AgentRunEventType lastEventType = null;
        AgentRunEventType lastCompletedEventType = null;
        Map<String, String> lastCompletedAttributes = Map.of();
        Map<String, String> lastEventAttributes = Map.of();
        boolean terminal = false;
        boolean blocked = false;
        boolean resumable = false;

        for (AgentRunEvent event : events) {
            if (event == null) {
                continue;
            }
            lastEventType = event.type();
            lastEventAttributes = event.attributes();
            switch (event.type()) {
                case RUN_FINISHED -> {
                    terminal = true;
                    lastCompletedEventType = event.type();
                    lastCompletedAttributes = event.attributes();
                }
                case RUN_FAILED -> {
                    lastCompletedEventType = event.type();
                    lastCompletedAttributes = event.attributes();
                    String status = event.attributes().getOrDefault("status", "");
                    if (AgentRunStatus.BLOCKED.name().equalsIgnoreCase(status)
                            || "MANUAL".equalsIgnoreCase(event.attributes().getOrDefault("recoverability", ""))) {
                        blocked = true;
                    } else {
                        terminal = true;
                    }
                }
                case RUN_CANCELLED, BUDGET_EXHAUSTED -> {
                    resumable = true;
                }
                case TOOL_OUTCOME, LLM_RESPONSE, TOOL_CALL_REQUESTED -> {
                    resumable = true;
                    lastCompletedEventType = event.type();
                    lastCompletedAttributes = event.attributes();
                }
                default -> {
                }
            }
            if ("MANUAL".equalsIgnoreCase(event.attributes().getOrDefault("recoverability", ""))) {
                blocked = true;
            }
        }

        RunStateStatus status;
        if (terminal) {
            status = RunStateStatus.TERMINAL;
        } else if (blocked) {
            status = RunStateStatus.MANUAL;
        } else if (resumable) {
            status = RunStateStatus.RESUMABLE;
        } else {
            status = RunStateStatus.RUNNING;
        }

        return new RunStateProjection(
                status,
                lastEventType,
                lastCompletedEventType,
                lastCompletedAttributes,
                lastEventAttributes,
                events);
    }
}
