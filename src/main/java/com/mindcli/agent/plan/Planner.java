package com.mindcli.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.LlmRetryPolicy;
import com.mindcli.platform.llm.LlmTraceLogger;
import com.mindcli.platform.prompt.PromptAssembler;
import com.mindcli.platform.prompt.PromptContext;
import com.mindcli.platform.prompt.PromptMode;
import com.mindcli.platform.prompt.ProjectMemoryLoader;
import com.mindcli.platform.render.terminal.AnsiStyle;
import com.mindcli.platform.render.terminal.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 规划器 - 使用 LLM 将复杂任务分解为执行计划。
 */
public class Planner {
    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private final LlmClient llmClient;
    private final PrintStream out;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlanSchemaParser schemaParser = new PlanSchemaParser(mapper);
    private final PlanSchemaValidator schemaValidator = new PlanSchemaValidator();
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private Supplier<String> projectMemorySupplier = () ->
            ProjectMemoryLoader.createDefault(Path.of(".").toAbsolutePath().normalize()).loadForPrompt();
    private Supplier<String> sessionContextSupplier = () -> "";

    public Planner(LlmClient llmClient) {
        this(llmClient, System.out);
    }

    public Planner(LlmClient llmClient, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
    }

    public void setProjectMemorySupplier(Supplier<String> projectMemorySupplier) {
        this.projectMemorySupplier = projectMemorySupplier == null ? () -> "" : projectMemorySupplier;
    }

    public void setSessionContextSupplier(Supplier<String> sessionContextSupplier) {
        this.sessionContextSupplier = sessionContextSupplier == null ? () -> "" : sessionContextSupplier;
    }

    public ExecutionPlan createPlan(String goal) throws IOException {
        out.println("📋 正在规划任务: " + goal + "\n");

        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        List<LlmClient.Message> messages = Arrays.asList(
                LlmClient.Message.system(promptAssembler.assemble(PromptMode.PLANNER, PromptContext.builder()
                        .projectMemoryContext(buildProjectMemoryContext())
                        .memoryContext(sessionContextSupplier.get())
                        .build())),
                LlmClient.Message.user("请为以下任务制定执行计划：\n" + goal)
        );

        PlanningStreamRenderer streamRenderer = new PlanningStreamRenderer(out);
        LlmClient.ChatResponse response;
        try {
            response = LlmRetryPolicy.withRetry(() -> llmClient.chat(messages, null, streamRenderer), "planner");
        } catch (Exception e) {
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("规划 LLM 调用失败: " + e.getMessage(), e);
        }
        LlmTraceLogger.logReasoning(log, "planner", llmClient, response.reasoningContent());
        streamRenderer.finish();

        return buildPlanFromRaw(goal, response.content());
    }

    public ExecutionPlan buildPlanFromRaw(String goal, String rawPlan) throws IOException {
        PlanSchema schema = parseSchemaWithRepair(goal, rawPlan);
        return buildExecutionPlan(goal, schema);
    }

    public ExecutionPlan buildExecutionPlan(String goal, PlanSchema schema) throws IOException {
        PlanValidationResult validation = schemaValidator.validate(schema);
        if (!validation.isValid()) {
            throw validation.toIOException();
        }

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(schema.summary());

        Map<String, String> idMapping = new LinkedHashMap<>();
        int taskIndex = 1;
        for (PlanTaskSpec spec : schema.tasks()) {
            String newId = "task_" + taskIndex++;
            idMapping.put(spec.id(), newId);
            Task task = new Task(newId, spec.description(), spec.type());
            task.setCritical(spec.critical());
            task.setMaxRetries(spec.maxRetries());
            task.setDegradation(spec.degradation());
            task.setExpectedEvidence(spec.expectedEvidence());
            plan.addTask(task);
        }

        taskIndex = 1;
        for (PlanTaskSpec spec : schema.tasks()) {
            Task task = plan.getTask("task_" + taskIndex++);
            for (String depId : spec.dependencies()) {
                String mapped = idMapping.get(depId);
                if (mapped == null) {
                    throw new IOException("计划依赖不存在: " + depId);
                }
                task.addDependency(mapped);
                Task dep = plan.getTask(mapped);
                if (dep != null) {
                    dep.addDependent(task.getId());
                }
            }
        }

        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖或缺失依赖");
        }
        return plan;
    }

    private PlanSchema parseSchemaWithRepair(String goal, String rawPlan) throws IOException {
        try {
            PlanSchema schema = schemaParser.parse(rawPlan);
            PlanValidationResult validation = schemaValidator.validate(schema);
            if (validation.isValid()) {
                return schema;
            }
            if (validation.hasFatalIssues()) {
                throw validation.toIOException();
            }
            return repairAndParse(goal, rawPlan, validation.repairableIssues());
        } catch (PlanParseException e) {
            return repairAndParse(goal, rawPlan, List.of(
                    new PlanIssue("PLAN_PARSE_ERROR", "json", PlanIssueSeverity.REPAIRABLE, e.getMessage())
            ));
        } catch (IOException e) {
            if (isUnknownTypeFailure(e)) {
                throw e;
            }
            return repairAndParse(goal, rawPlan, List.of(
                    new PlanIssue("PLAN_PARSE_ERROR", "json", PlanIssueSeverity.REPAIRABLE, e.getMessage())
            ));
        }
    }

    private PlanSchema repairAndParse(String goal, String rawPlan, List<PlanIssue> issues) throws IOException {
        PlanRepairer repairer = new PlanRepairer(llmClient, mapper);
        String repaired = repairer.repair(goal, rawPlan, issues);
        PlanSchema repairedSchema = schemaParser.parse(repaired);
        PlanValidationResult validation = schemaValidator.validate(repairedSchema);
        if (!validation.isValid()) {
            throw validation.toIOException();
        }
        return repairedSchema;
    }

    private boolean isUnknownTypeFailure(IOException e) {
        String message = e.getMessage();
        return message != null && message.contains("PLAN_UNKNOWN_TASK_TYPE");
    }

    private String buildProjectMemoryContext() {
        try {
            String context = projectMemorySupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to load MIND.md project memory for planner", e);
            return "";
        }
    }

    private boolean isSimpleGoal(String goal) {
        if (goal == null) {
            return false;
        }

        String normalized = goal.trim();
        if (normalized.isEmpty()) {
            return false;
        }

        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("同时")
                || normalized.contains("先")
                || normalized.contains("之后")
                || normalized.contains("接着")
                || normalized.contains("以及");
        if (hasMultiStepCue) {
            return false;
        }

        if (normalized.length() > 30) {
            return false;
        }

        return normalized.contains("列出")
                || normalized.contains("查看")
                || normalized.contains("读取")
                || normalized.contains("显示")
                || normalized.contains("执行")
                || normalized.contains("运行")
                || normalized.contains("搜索")
                || normalized.contains("当前目录")
                || normalized.contains("文件");
    }

    private ExecutionPlan createMinimalPlan(String goal) throws IOException {
        PlanTaskSpec spec = new PlanTaskSpec(
                "task_1",
                goal == null ? "" : goal.trim(),
                inferSimpleTaskType(goal),
                List.of(),
                true,
                3,
                "REPLAN",
                List.of()
        );
        PlanSchema schema = new PlanSchema(1, buildMinimalSummary(goal), List.of(spec));
        return buildExecutionPlan(goal, schema);
    }

    private String buildMinimalSummary(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isEmpty()) {
            return "执行简单任务";
        }
        return "直接执行简单任务：" + normalized;
    }

    private Task.TaskType inferSimpleTaskType(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.contains("读取") || normalized.contains("打开") || normalized.contains("查看")
                && normalized.contains("文件")) {
            return Task.TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("修改") || normalized.contains("创建文件")) {
            return Task.TaskType.FILE_WRITE;
        }
        if (normalized.contains("分析") || normalized.contains("总结") || normalized.contains("解释")) {
            return Task.TaskType.ANALYSIS;
        }
        if (normalized.contains("验证") || normalized.contains("检查")) {
            return Task.TaskType.VERIFICATION;
        }
        return Task.TaskType.COMMAND;
    }

    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    public ExecutionPlan replanSubtree(ExecutionPlan plan, Task failedTask, String failureReason) throws IOException {
        out.println("🔄 局部重规划子树，失败任务: " + failedTask.getId() + "\n");

        List<Task> affected = new java.util.ArrayList<>();
        collectDependents(failedTask, plan, new java.util.HashSet<>(), affected);

        StringBuilder context = new StringBuilder();
        context.append("原目标: ").append(plan.getGoal()).append("\n");
        context.append("失败任务: ").append(failedTask.getId())
                .append(" - ").append(failedTask.getDescription()).append("\n");
        context.append("失败原因: ").append(failureReason).append("\n");

        try {
            List<LlmClient.Message> analysisReq = List.of(
                    LlmClient.Message.system("你是一个故障分析助手，只输出简洁的根因分析和修复策略。"),
                    LlmClient.Message.user("任务执行失败。\n目标: " + plan.getGoal()
                            + "\n失败任务: " + failedTask.getDescription()
                            + "\n失败信息: " + failureReason
                            + "\n已完成任务: " + summarizeCompletedTasks(plan)
                            + "\n\n请分析失败根因，并给出绕过该问题的具体策略（不超过3句话）。")
            );
            String analysis = llmClient.chat(analysisReq, null).content();
            if (analysis != null && !analysis.isBlank()) {
                context.append("\n失败根因分析: ").append(analysis.trim()).append("\n");
            }
        } catch (Exception e) {
            log.warn("根因分析 LLM 调用失败，跳过: {}", e.getMessage());
        }

        if (!affected.isEmpty()) {
            context.append("\n受影响的下游任务（需重新规划）:\n");
            for (Task t : affected) {
                context.append("- ").append(t.getId()).append(": ").append(t.getDescription()).append("\n");
            }
        }
        context.append("\n请制定绕过上述失败的新执行计划，已完成的任务保持不动。");

        return createPlan(context.toString());
    }

    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        out.println("🔄 重新规划，原因: " + failureReason + "\n");

        StringBuilder context = new StringBuilder();
        context.append("原任务: ").append(failedPlan.getGoal()).append("\n");
        context.append("失败原因: ").append(failureReason).append("\n");
        context.append("已完成的任务:\n");

        for (Task task : failedPlan.getAllTasks()) {
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId())
                        .append(": ").append(task.getDescription())
                        .append("\n");
            }
        }

        context.append("\n请制定新的执行计划，避开之前的问题。");
        return createPlan(context.toString());
    }

    private void collectDependents(Task task, ExecutionPlan plan,
                                   java.util.Set<String> visited, List<Task> result) {
        if (!visited.add(task.getId())) {
            return;
        }
        for (String depId : task.getDependents()) {
            Task dep = plan.getTask(depId);
            if (dep != null) {
                result.add(dep);
                collectDependents(dep, plan, visited, result);
            }
        }
    }

    private String summarizeCompletedTasks(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (Task t : plan.getAllTasks()) {
            if (t.getStatus() == Task.TaskStatus.COMPLETED) {
                sb.append(t.getId()).append(": ").append(t.getDescription()).append("; ");
            }
        }
        return sb.isEmpty() ? "(无)" : sb.toString().trim();
    }

    private static final class PlanningStreamRenderer implements LlmClient.StreamListener {
        private final PrintStream out;
        private TerminalMarkdownRenderer reasoningRenderer;
        private boolean reasoningStarted;
        private boolean streamed;

        private PlanningStreamRenderer(PrintStream out) {
            this.out = out == null ? System.out : out;
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!reasoningStarted) {
                out.println(AnsiStyle.heading("🧠 规划思考"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningStarted = true;
                streamed = true;
            }
            reasoningRenderer.append(delta);
            out.flush();
        }

        private void finish() {
            if (streamed) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                out.println("\n");
            }
        }
    }
}
