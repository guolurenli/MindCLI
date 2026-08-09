package com.mindcli.plan;

import java.util.List;

public record PlanSchema(int schemaVersion, String summary, List<PlanTaskSpec> tasks) {
}
