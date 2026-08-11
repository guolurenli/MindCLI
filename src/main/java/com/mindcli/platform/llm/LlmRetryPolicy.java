package com.mindcli.platform.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * LLM 调用瞬态错误重试策略。
 *
 * 将可恢复的瞬时错误（网络抖动、限流、服务过载）与不可恢复的永久错误
 * （认证失败、参数错误）区分开，前者使用指数退避自动重试，后者直接向上抛。
 *
 * 对齐 Claude Code 的错误分层恢复：
 * - 网络/超时错误 → 指数退避最多 3 次（1s/2s/4s）
 * - 429 限流 → 等待后重试
 * - 503 过载 → 等待后重试
 * - 401/403 等 → 不重试，立即失败
 */
public class LlmRetryPolicy {
    private static final Logger log = LoggerFactory.getLogger(LlmRetryPolicy.class);

    private static final int MAX_RETRIES = 3;

    /** 可重试错误的关键词（大小写不敏感） */
    private static final Set<String> RETRYABLE_KEYWORDS = Set.of(
            "timeout", "429", "503", "overloaded", "connection",
            "reset", "refused", "unreachable", "socket"
    );

    /** 不可重试错误关键词（即使包含上述可重试关键词，也应立即失败） */
    private static final Set<String> NON_RETRYABLE_KEYWORDS = Set.of(
            "401", "403", "invalid_request_error", "authentication",
            "invalid_api_key", "permission"
    );

    /**
     * 判断异常是否可重试。
     */
    public static boolean isRetryable(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return false;
        String lower = msg.toLowerCase(Locale.ROOT);

        // 不可重试优先判断
        if (NON_RETRYABLE_KEYWORDS.stream().anyMatch(lower::contains)) {
            return false;
        }
        return RETRYABLE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 带指数退避重试执行 LLM 调用。
     *
     * @param callable 需要重试保护的调用
     * @param callerName 调用方名称（用于日志）
     * @return 调用结果
     * @throws Exception 重试耗尽或非可重试错误时抛出
     */
    public static <T> T withRetry(Callable<T> callable, String callerName) throws Exception {
        Exception lastError = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                return callable.call();
            } catch (Exception e) {
                lastError = e;
                if (!isRetryable(e) || i >= MAX_RETRIES - 1) {
                    throw e;
                }
                long delay = (1L << i) * 1000; // 1s, 2s, 4s
                log.warn("[{}] 瞬态错误（{}/{}），{}ms 后重试: {}",
                        callerName, i + 1, MAX_RETRIES, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw lastError;
    }

    /** 最大重试次数 */
    public static int maxRetries() {
        return MAX_RETRIES;
    }
}
