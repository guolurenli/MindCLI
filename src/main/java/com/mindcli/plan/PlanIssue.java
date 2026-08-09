package com.mindcli.plan;

public record PlanIssue(String code, String field, PlanIssueSeverity severity, String message) {
    public boolean isRepairable() {
        return severity == PlanIssueSeverity.REPAIRABLE;
    }
}
