package com.mindcli.runtime.run;

public enum ToolOutcomeStatus {
    COMPLETED,
    PARTIAL,
    DENIED_BY_POLICY,
    DENIED_BY_USER,
    TIMED_OUT,
    FAILED,
    CANCELLED
}
