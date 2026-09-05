package com.mindcli.capability.mcp.lifecycle;

import com.mindcli.capability.mcp.McpServer;
import com.mindcli.capability.mcp.McpServerStatus;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Owns MCP startup concurrency and bounded-wait presentation policy. */
public final class McpStartupCoordinator {
    private static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(5);

    public void startAll(Collection<McpServer> configuredServers,
                         PrintStream progressOut,
                         Duration maxWait,
                         Consumer<McpServer> startAction) {
        List<McpServer> targets = configuredServers.stream()
                .filter(server -> !server.config().isDisabled())
                .toList();
        if (targets.isEmpty()) return;

        AtomicInteger threadId = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(targets.size(), 8), runnable -> {
            Thread thread = new Thread(runnable, "mindcli-mcp-startup-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        boolean boundedWait = maxWait != null && !maxWait.isZero() && !maxWait.isNegative();
        Thread progress = boundedWait ? null : startProgressPrinter(targets, progressOut);
        try {
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(server -> CompletableFuture.runAsync(() -> startAction.accept(server), executor))
                    .toList();
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            if (!boundedWait) {
                all.join();
            } else {
                try {
                    all.get(Math.max(1, maxWait.toMillis()), TimeUnit.MILLISECONDS);
                } catch (TimeoutException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (Exception e) {
                    all.join();
                }
            }
        } finally {
            if (progress != null) progress.interrupt();
            executor.shutdown();
        }
    }

    public String startupNotice(Collection<McpServer> configuredServers, Duration maxWait) {
        List<McpServer> starting = configuredServers.stream()
                .filter(server -> !server.config().isDisabled() && server.status() == McpServerStatus.STARTING)
                .sorted(Comparator.comparing(McpServer::name))
                .toList();
        if (starting.isEmpty()) return "";
        String names = starting.stream().map(McpServer::name).reduce((a, b) -> a + ", " + b).orElse("");
        long seconds = Math.max(1, (long) Math.ceil(maxWait.toMillis() / 1000.0));
        return "Mcp 后台继续启动: " + names + "（超过 " + seconds
                + "s，可用 /mcp 查看，/mcp logs <name> 看日志）";
    }

    private void printStartupTimeout(List<McpServer> targets, PrintStream out, Duration maxWait) {
        if (out == null) return;
        String notice = startupNotice(targets, maxWait);
        if (!notice.isBlank()) {
            out.println(notice);
            out.flush();
        }
    }

    private Thread startProgressPrinter(List<McpServer> targets, PrintStream out) {
        if (out == null || targets.isEmpty()) return null;
        Map<String, Instant> startedAt = new ConcurrentHashMap<>();
        targets.forEach(server -> startedAt.put(server.name(), Instant.now()));
        Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    TimeUnit.MILLISECONDS.sleep(PROGRESS_INTERVAL.toMillis());
                    for (McpServer server : targets.stream()
                            .filter(candidate -> candidate.status() == McpServerStatus.STARTING)
                            .sorted(Comparator.comparing(McpServer::name)).toList()) {
                        long waited = Duration.between(startedAt.get(server.name()), Instant.now()).toSeconds();
                        out.printf("   ⏳ %-16s %-6s 启动中...（已等待 %ds）%n",
                                server.name(), server.transportName(), waited);
                    }
                    out.flush();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "mindcli-mcp-startup-progress");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
