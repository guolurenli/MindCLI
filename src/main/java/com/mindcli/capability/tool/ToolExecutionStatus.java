package com.mindcli.capability.tool;

/** Structured status produced by one tool execution before runtime metadata is attached. */
public enum ToolExecutionStatus {
    COMPLETED,
    PARTIAL,
    DENIED_BY_POLICY,
    DENIED_BY_USER,
    TIMED_OUT,
    CANCELLED,
    FAILED
}
