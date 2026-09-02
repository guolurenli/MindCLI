package com.mindcli.runtime.run.hook;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import com.mindcli.capability.tool.ToolRegistry;

import java.util.Map;
import java.util.Objects;

public record HookEvent(
        HookType type,
        ToolRegistry.ToolInvocation invocation,
        AgentRunContext context,
        ToolOutcome outcome,
        Throwable error,
        Map<String, String> metadata
) {
    public HookEvent {
        type = Objects.requireNonNull(type, "type");
        invocation = Objects.requireNonNull(invocation, "invocation");
        context = Objects.requireNonNull(context, "context");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static HookEvent of(HookType type, ToolRegistry.ToolInvocation invocation,
                               AgentRunContext context) {
        return new HookEvent(type, invocation, context, null, null, Map.of());
    }

    public static HookEvent withOutcome(HookType type, ToolRegistry.ToolInvocation invocation,
                                        AgentRunContext context, ToolOutcome outcome) {
        return new HookEvent(type, invocation, context, outcome, null, Map.of());
    }

    public static HookEvent withError(ToolRegistry.ToolInvocation invocation,
                                      AgentRunContext context, Throwable error) {
        return new HookEvent(HookType.TOOL_ERROR, invocation, context, null, error, Map.of());
    }
}
