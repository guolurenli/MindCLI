package com.mindcli.runtime.run.dispatch;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolOutcomeEventFactory {
    private ToolOutcomeEventFactory() {
    }

    public static AgentRunEvent create(AgentRunContext context, ToolOutcome outcome,
                                       Map<String, String> extraAttributes) {
        return AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, attributes(outcome, extraAttributes));
    }

    public static Map<String, String> attributes(ToolOutcome outcome, Map<String, String> extraAttributes) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (outcome != null) {
            attributes.putAll(outcome.metadata());
            attributes.put("toolId", outcome.id());
            attributes.put("toolName", outcome.name());
            attributes.put("status", outcome.status().name());
            attributes.put("elapsedMillis", String.valueOf(outcome.elapsedMillis()));
            attributes.put("textChars", String.valueOf(outcome.text().length()));
            attributes.put("hasImages", String.valueOf(outcome.hasImageParts()));
            if (!outcome.errorMessage().isBlank()) {
                attributes.put("errorMessage", outcome.errorMessage());
            }
            if (!outcome.errorCategory().isBlank()) {
                attributes.put("errorCategory", outcome.errorCategory());
            }
        }
        if (extraAttributes != null) {
            attributes.putAll(extraAttributes);
        }
        return attributes;
    }
}
