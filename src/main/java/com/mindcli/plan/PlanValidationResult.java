package com.mindcli.plan;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

public record PlanValidationResult(PlanSchema schema, List<PlanIssue> issues) {

    public PlanValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static PlanValidationResult valid(PlanSchema schema) {
        return new PlanValidationResult(schema, List.of());
    }

    public static PlanValidationResult invalid(PlanSchema schema, List<PlanIssue> issues) {
        return new PlanValidationResult(schema, issues);
    }

    public boolean isValid() {
        return issues.isEmpty();
    }

    public boolean hasFatalIssues() {
        return issues.stream().anyMatch(issue -> issue.severity() == PlanIssueSeverity.FATAL);
    }

    public boolean isRepairable() {
        return !issues.isEmpty() && issues.stream().allMatch(PlanIssue::isRepairable);
    }

    public List<PlanIssue> repairableIssues() {
        List<PlanIssue> repairable = new ArrayList<>();
        for (PlanIssue issue : issues) {
            if (issue.isRepairable()) {
                repairable.add(issue);
            }
        }
        return Collections.unmodifiableList(repairable);
    }

    public IOException toIOException() {
        if (issues.isEmpty()) {
            return new IOException("计划校验失败");
        }
        StringJoiner joiner = new StringJoiner("; ");
        for (PlanIssue issue : issues) {
            joiner.add(issue.code() + "[" + issue.field() + "]: " + issue.message());
        }
        return new IOException(joiner.toString());
    }
}
