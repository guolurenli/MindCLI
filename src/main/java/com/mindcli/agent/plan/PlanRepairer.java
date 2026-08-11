package com.mindcli.agent.plan;

import com.mindcli.platform.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

public class PlanRepairer {
    private final LlmClient llmClient;
    private final ObjectMapper mapper;

    public PlanRepairer(LlmClient llmClient, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    public String repair(String goal, String rawPlanJson, List<PlanIssue> issues) throws IOException {
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system("""
                        你是一个计划 JSON 修复器。只输出修复后的 JSON，不要解释，不要加代码块。
                        输出必须符合 canonical schema: schemaVersion, summary, tasks.
                        tasks 中每个任务必须包含 id, description, type, dependencies.
                        """),
                LlmClient.Message.user(buildPrompt(goal, rawPlanJson, issues))
        );

        LlmClient.ChatResponse response = llmClient.lightQuery(messages, 1024);
        String repaired = response == null ? null : response.content();
        if (repaired == null || repaired.isBlank()) {
            throw new IOException("计划修复失败：模型未返回内容");
        }
        return repaired;
    }

    private String buildPrompt(String goal, String rawPlanJson, List<PlanIssue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标: ").append(goal == null ? "" : goal).append("\n");
        sb.append("原始输出:\n").append(rawPlanJson == null ? "" : rawPlanJson).append("\n");
        sb.append("问题列表:\n");
        if (issues != null && !issues.isEmpty()) {
            for (PlanIssue issue : issues) {
                sb.append("- ").append(issue.code()).append(" [").append(issue.field()).append("]: ")
                        .append(issue.message()).append("\n");
            }
        } else {
            sb.append("- PLAN_PARSE_ERROR\n");
        }
        sb.append("\n请返回修复后的 canonical JSON。");
        return sb.toString();
    }
}
