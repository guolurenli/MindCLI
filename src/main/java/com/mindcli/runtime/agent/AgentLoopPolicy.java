package com.mindcli.runtime.agent;

public record AgentLoopPolicy(String traceName, boolean toolsEnabled) {
    public AgentLoopPolicy {
        traceName = traceName == null || traceName.isBlank() ? "agent-loop" : traceName;
    }
}
