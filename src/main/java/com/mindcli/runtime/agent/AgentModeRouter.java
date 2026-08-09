package com.mindcli.runtime.agent;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class AgentModeRouter {
    private final AgentRuntime runtime;
    private final Map<AgentMode, ModeAdapter> adapters;
    private final String workspace;

    public AgentModeRouter(AgentRuntime runtime, Collection<? extends ModeAdapter> adapters, String workspace) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.adapters = new EnumMap<>(AgentMode.class);
        if (adapters != null) {
            for (ModeAdapter adapter : adapters) {
                Objects.requireNonNull(adapter, "adapter");
                this.adapters.put(adapter.mode(), adapter);
            }
        }
        this.workspace = workspace == null || workspace.isBlank()
                ? System.getProperty("user.dir", "")
                : workspace;
    }

    public AgentRunResult submit(String input, AgentMode mode) {
        Objects.requireNonNull(mode, "mode");
        AgentRunContext context = AgentRunContext.create(mode, input, workspace);
        ModeAdapter adapter = adapters.get(mode);
        if (adapter == null) {
            return AgentRunResult.failed(context, "Unsupported mode: " + mode);
        }
        return runtime.run(context, adapter);
    }
}
