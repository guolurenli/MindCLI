package com.mindcli.runtime.run.store;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;

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
