package com.mindcli.agent;

import com.mindcli.platform.config.ConfigValueResolver;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.RunDeadline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Agent 循环的退出预算。
 *
 * 设计目标是把"是否继续下一轮"的主导权交给 LLM 自己——只要它返回 content 不再调用工具，
 * 循环就退出。本类只承担三种"保险阀"职责，避免模型在异常情况下无限重复同一动作：
 *
 * 1. Token 预算：累计 input + output token 超过阈值后强制收尾（**默认无限**，仅显式配置时生效）
 * 2. 循环提示：发现连续重复或短周期工具请求时提醒 LLM 调整，但不据此终止
 * 3. 运行时限：总运行时间超过 maxDurationSeconds 后强制收尾
 * 4. 硬轮数兜底：累计迭代轮数超过 hardMaxIterations，作为兜底防御
 *
 * 只有确定性的预算条件会结束循环；启发式循环检测只提供提示。
 *
 * 配置读取顺序：JVM system property > OS environment > 项目 .env > 用户 ~/.env > 默认值。
 *
 * 设计取舍：长上下文模型（GLM-5.1 200k / DeepSeek V4 1M）配合套餐用户的"无限 token"诉求，
 * 默认不再以 80% × window 为硬限——让 LLM 自然停在它该停的地方。需要严格成本控制的
 * 场景（CI / 自动化批跑）通过 {@code -Dmindcli.react.token.budget=N} 显式启用。
 * 失控运行由总时限和 hardMaxIterations 兜底。
 */
public class AgentBudget {

    public enum ExitReason {
        WITHIN_BUDGET,
        TOKEN_BUDGET_EXCEEDED,
        RUN_TIMEOUT,
        HARD_ITERATION_LIMIT
    }

    private static final int DEFAULT_STAGNATION_WINDOW = 3;
    private static final int DEFAULT_HARD_MAX_ITERATIONS = 50;
    private static final int DEFAULT_MAX_DURATION_SECONDS = 3_600;
    private static final int MAX_CYCLE_LENGTH = 4;

    private final int tokenBudget;
    private final int stagnationWindow;
    private final int hardMaxIterations;
    private final int maxDurationSeconds;
    private final long startedAtNanos;
    private final AgentRunContext deadlineContext;

    private int iteration;
    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalCachedInputTokens;
    private final Deque<String> recentToolRequests = new ArrayDeque<>();
    private boolean stagnationWarningPending;
    private boolean cycleWarningActive;

    public AgentBudget(int tokenBudget, int stagnationWindow, int hardMaxIterations) {
        this(tokenBudget, stagnationWindow, hardMaxIterations, 0);
    }

    public AgentBudget(int tokenBudget, int stagnationWindow, int hardMaxIterations,
                       int maxDurationSeconds) {
        this(tokenBudget, stagnationWindow, hardMaxIterations, maxDurationSeconds, null);
    }

    private AgentBudget(int tokenBudget, int stagnationWindow, int hardMaxIterations,
                        int maxDurationSeconds, AgentRunContext deadlineContext) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
        if (stagnationWindow < 2) {
            throw new IllegalArgumentException("stagnationWindow must be >= 2");
        }
        if (hardMaxIterations <= 0) {
            throw new IllegalArgumentException("hardMaxIterations must be positive");
        }
        if (maxDurationSeconds < 0) {
            throw new IllegalArgumentException("maxDurationSeconds must be >= 0");
        }
        this.tokenBudget = tokenBudget;
        this.stagnationWindow = stagnationWindow;
        this.hardMaxIterations = hardMaxIterations;
        this.maxDurationSeconds = maxDurationSeconds;
        this.startedAtNanos = System.nanoTime();
        this.deadlineContext = deadlineContext;
    }

    public static AgentBudget fromSystemProperties() {
        return fromLlmClient(null);
    }

    public static AgentBudget fromLlmClient(LlmClient llmClient) {
        return fromLlmClient(llmClient, ConfigValueResolver.current());
    }

    static AgentBudget fromLlmClient(LlmClient llmClient, ConfigValueResolver config) {
        return fromLlmClient(llmClient, config, null);
    }

    public static AgentBudget forRun(LlmClient llmClient, AgentRunContext context) {
        return fromLlmClient(llmClient, ConfigValueResolver.current(),
                java.util.Objects.requireNonNull(context, "context"));
    }

    private static AgentBudget fromLlmClient(LlmClient llmClient, ConfigValueResolver config,
                                            AgentRunContext context) {
        // ContextProfile 仍按 80% × window 计算 agentTokenBudget，用于 /context 与 token stats 的"软提示"显示；
        // 但 AgentBudget 的硬限默认走 Integer.MAX_VALUE，避免长上下文 + 套餐用户被预算墙卡住。
        // 显式 -Dmindcli.react.token.budget=N 仍可启用硬预算，覆盖默认。
        return new AgentBudget(
                readInt(config, "mindcli.react.token.budget", "MINDCLI_REACT_TOKEN_BUDGET", Integer.MAX_VALUE),
                readInt(config, "mindcli.react.stagnation.window", "MINDCLI_REACT_STAGNATION_WINDOW", DEFAULT_STAGNATION_WINDOW),
                readInt(config, "mindcli.react.hard.max.iterations", "MINDCLI_REACT_HARD_MAX_ITERATIONS", DEFAULT_HARD_MAX_ITERATIONS),
                readInt(config, "mindcli.react.max.duration.seconds", "MINDCLI_REACT_MAX_DURATION_SECONDS", DEFAULT_MAX_DURATION_SECONDS),
                context
        );
    }

    /** 进入新一轮迭代，返回当前轮次（从 1 开始）。 */
    public int beginIteration() {
        return ++iteration;
    }

    public void recordTokens(int inputTokens, int outputTokens) {
        recordTokens(inputTokens, outputTokens, 0);
    }

    public void recordTokens(int inputTokens, int outputTokens, int cachedInputTokens) {
        this.totalInputTokens += Math.max(0, inputTokens);
        this.totalOutputTokens += Math.max(0, outputTokens);
        this.totalCachedInputTokens += Math.max(0, cachedInputTokens);
    }

    /**
     * 记录本轮工具请求，检测连续重复或长度不超过 4 的短周期。
     *
     * 检测结果只触发一次提示，不作为强制退出条件。工具结果不参与判断，避免运行时猜测业务进展。
     */
    public void recordToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            resetLoopTracking();
            return;
        }
        recentToolRequests.addLast(ToolRequestFingerprint.of(toolCalls));
        long historyLimit = (long) stagnationWindow * MAX_CYCLE_LENGTH;
        while (recentToolRequests.size() > historyLimit) {
            recentToolRequests.removeFirst();
        }

        boolean cycleDetected = containsRepeatedCycle();
        if (cycleDetected && !cycleWarningActive) {
            stagnationWarningPending = true;
            cycleWarningActive = true;
        } else if (!cycleDetected) {
            cycleWarningActive = false;
        }
    }

    public boolean consumeStagnationWarning() {
        boolean pending = stagnationWarningPending;
        stagnationWarningPending = false;
        return pending;
    }

    public ExitReason check() {
        if (totalInputTokens + totalOutputTokens >= tokenBudget) {
            return ExitReason.TOKEN_BUDGET_EXCEEDED;
        }
        if (deadlineContext != null ? RunDeadline.isExpired(deadlineContext) : maxDurationSeconds > 0
                && System.nanoTime() - startedAtNanos >= TimeUnit.SECONDS.toNanos(maxDurationSeconds)) {
            return ExitReason.RUN_TIMEOUT;
        }
        if (iteration >= hardMaxIterations) {
            return ExitReason.HARD_ITERATION_LIMIT;
        }
        return ExitReason.WITHIN_BUDGET;
    }

    public int iteration() {
        return iteration;
    }

    public long totalInputTokens() {
        return totalInputTokens;
    }

    public long totalOutputTokens() {
        return totalOutputTokens;
    }

    public long totalCachedInputTokens() {
        return totalCachedInputTokens;
    }

    public int tokenBudget() {
        return tokenBudget;
    }

    public int hardMaxIterations() {
        return hardMaxIterations;
    }

    public int stagnationWindow() {
        return stagnationWindow;
    }

    public int maxDurationSeconds() {
        return maxDurationSeconds;
    }

    public String describeExit(ExitReason reason) {
        return switch (reason) {
            case WITHIN_BUDGET -> "未触发兜底条件";
            case TOKEN_BUDGET_EXCEEDED -> String.format(Locale.ROOT,
                    "Token 预算已用尽（%d / %d），任务被强制收尾",
                    totalInputTokens + totalOutputTokens, tokenBudget);
            case RUN_TIMEOUT -> deadlineContext != null ? RunDeadline.DESCRIPTION : String.format(Locale.ROOT,
                    "达到运行时限（%d 秒），已强制收尾", maxDurationSeconds);
            case HARD_ITERATION_LIMIT -> String.format(Locale.ROOT,
                    "达到硬轮数上限（%d），已强制收尾", hardMaxIterations);
        };
    }

    private boolean containsRepeatedCycle() {
        List<String> history = new ArrayList<>(recentToolRequests);
        for (int cycleLength = 1; cycleLength <= MAX_CYCLE_LENGTH; cycleLength++) {
            int required = cycleLength * stagnationWindow;
            if (history.size() < required) {
                continue;
            }
            int start = history.size() - required;
            boolean matches = true;
            for (int i = start + cycleLength; i < history.size(); i++) {
                if (!history.get(i).equals(history.get(i - cycleLength))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private void resetLoopTracking() {
        recentToolRequests.clear();
        stagnationWarningPending = false;
        cycleWarningActive = false;
    }

    private static int readInt(ConfigValueResolver config,
                               String propertyKey,
                               String environmentKey,
                               int defaultValue) {
        return config.resolveInt(propertyKey, environmentKey, defaultValue);
    }
}
