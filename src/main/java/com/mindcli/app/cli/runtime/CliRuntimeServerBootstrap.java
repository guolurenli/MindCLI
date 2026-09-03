package com.mindcli.app.cli.runtime;

import com.mindcli.agent.Agent;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.platform.config.MindCliConfig;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.LlmClientFactory;
import com.mindcli.runtime.api.RuntimeApiServer;
import com.mindcli.runtime.api.RuntimeThreadStore;
import com.mindcli.runtime.task.DurableTaskManager;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bootstrap helpers for the headless Runtime API and durable task runner.
 * The interactive CLI remains owned by {@code Main}; this class only handles
 * the non-interactive startup path and its small compatibility helpers.
 */
public final class CliRuntimeServerBootstrap {
    private CliRuntimeServerBootstrap() {
    }

    public static boolean isRuntimeServeCommand(String[] args) {
        return args != null
                && args.length >= 1
                && "serve".equalsIgnoreCase(args[0])
                && Arrays.stream(args).anyMatch("--http"::equalsIgnoreCase);
    }

    public static void startRuntimeApiAndBlock(String[] args) {
        MindCliConfig config = MindCliConfig.load();
        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.exit(1);
        }
        int port = parseServePort(args, 8080);
        try {
            RuntimeThreadStore store = new RuntimeThreadStore(RuntimeThreadStore.defaultDbPath());
            RuntimeApiServer server = new RuntimeApiServer(
                    store,
                    prompt -> runHeadlessTask(prompt, client),
                    port,
                    RuntimeApiServer.configuredApiKey());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                store.close();
            }, "mindcli-runtime-api-shutdown"));
            server.start();
            System.out.println("✅ MindCLI Runtime API 已启动: http://127.0.0.1:" + server.port());
            System.out.println("   认证: Authorization: Bearer <MINDCLI_RUNTIME_API_KEY>");
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("❌ Runtime API 启动失败: " + e.getMessage());
            System.exit(1);
        }
    }

    public static int parseServePort(String[] args, int defaultPort) {
        if (args == null) {
            return defaultPort;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equalsIgnoreCase(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                    return defaultPort;
                }
            }
        }
        return defaultPort;
    }

    public static String runHeadlessTask(String prompt, LlmClient llmClient) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(Path.of(".").toAbsolutePath().normalize().toString());
        Agent agent = new Agent(llmClient, registry);
        return agent.run(prompt);
    }

    public static DurableTaskManager openTaskManager(AtomicReference<LlmClient> llmClientRef) {
        try {
            return DurableTaskManager.openDefault(prompt -> runHeadlessTask(prompt, llmClientRef.get()));
        } catch (Exception e) {
            throw new IllegalStateException("后台任务管理器初始化失败: " + e.getMessage(), e);
        }
    }
}
