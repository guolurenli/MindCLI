package com.mindcli.runtime.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** Serializes turn execution per runtime thread while keeping different threads independent. */
final class RuntimeApiTurnScheduler {
    private final Executor executor;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final Object queueMonitor = new Object();

    RuntimeApiTurnScheduler(Executor executor) {
        this.executor = executor;
    }

    CompletableFuture<Void> submit(String threadId, Runnable task) {
        synchronized (queueMonitor) {
            CompletableFuture<Void> previous = tails.get(threadId);
            CompletableFuture<Void> next = (previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((ignored, failure) -> null))
                    .thenRunAsync(task, executor);
            tails.put(threadId, next);
            next.whenComplete((ignored, failure) -> {
                synchronized (queueMonitor) {
                    tails.remove(threadId, next);
                }
            });
            return next;
        }
    }
}
