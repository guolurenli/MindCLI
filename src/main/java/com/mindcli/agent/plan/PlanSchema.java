package com.mindcli.agent.plan;

import java.util.List;

public record PlanSchema(int schemaVersion, String summary, List<PlanTaskSpec> tasks) {
}
