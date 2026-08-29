package com.mindcli.capability.mcp;

import com.mindcli.capability.mcp.config.McpConfigLoader;
import com.mindcli.capability.mcp.config.McpServerConfig;
import com.mindcli.capability.mcp.notifications.NotificationRouter;
import com.mindcli.capability.mcp.protocol.McpToolDescriptor;
import com.mindcli.capability.mcp.resources.McpResourceCache;
import com.mindcli.capability.mcp.resources.McpResourceContent;
import com.mindcli.capability.mcp.resources.McpResourceDescriptor;
import com.mindcli.capability.mcp.resources.McpResourceTool;
import com.mindcli.platform.security.AuditLog;
import com.mindcli.capability.mcp.transport.MindCliStdioClientTransport;
import com.mindcli.capability.tool.ToolOutput;
import com.mindcli.capability.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

public class McpServerManager implements AutoCloseable {
    private static final Duration STARTUP_PROGRESS_INTERVAL = Duration.ofSeconds(5);

    private final ToolRegistry toolRegistry;
    private final Path projectDir;
    private final McpConfigLoader configLoader;
    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();
    private final McpResourceCache resourceCache = new McpResourceCache();

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir) {
        this(toolRegistry, projectDir, new McpConfigLoader(projectDir));
    }

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir, McpConfigLoader configLoader) {
        this.toolRegistry = toolRegistry;
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.configLoader = configLoader;
    }

    public void loadConfiguredServers() throws IOException {
        Map<String, McpServerConfig> configs = configLoader.load();
        servers.clear();
        configs.forEach((name, config) -> servers.put(name, new McpServer(name, config)));
    }

    public void startAll() {
        startAll(null);
    }

    public void startAll(PrintStream progressOut) {
        startAll(progressOut, null);
    }

    /**
     * Start all configured servers.
     *
     * <p>When {@code maxWait} is {@code null}, this method preserves the historical
     * blocking behavior and waits until every server reaches READY/ERROR. When a
     * bounded wait is supplied, unfinished servers continue starting on daemon
     * threads so the CLI can render the first prompt instead of being held hostage
     * by a slow stdio/http server.
     */
    public void startAll(PrintStream progressOut, Duration maxWait) {
        List<McpServer> targets = servers.values().stream()
                .filter(server -> !server.config().isDisabled())
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        // 用专属 daemon executor，避免 npx/uvx 冷启动期间占满 ForkJoinPool.commonPool 影响其他并发任务。
        AtomicInteger threadId = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(targets.size(), 8),
                r -> {
                    Thread t = new Thread(r, "mindcli-mcp-startup-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        boolean boundedWait = maxWait != null && !maxWait.isZero() && !maxWait.isNegative();
        Thread progressPrinter = boundedWait ? null : startProgressPrinter(targets, progressOut, STARTUP_PROGRESS_INTERVAL);
        try {
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(server -> CompletableFuture.runAsync(() -> start(server), executor))
                    .toList();
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            if (!boundedWait) {
                all.join();
            } else {
                try {
                    all.get(Math.max(1, maxWait.toMillis()), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (Exception e) {
                    all.join();
                }
            }
        } finally {
            if (progressPrinter != null) {
                progressPrinter.interrupt();
            }
            executor.shutdown();
        }
    }

    private void printStartupTimeout(List<McpServer> targets, PrintStream out, Duration maxWait) {
        if (out == null) {
            return;
        }
        String line = startupNotice(targets, maxWait);
        if (line.isBlank()) {
            return;
        }
        out.println(line);
        out.flush();
    }

    public String startupNotice(Duration maxWait) {
        return startupNotice(servers.values().stream()
                .filter(server -> !server.config().isDisabled())
                .toList(), maxWait);
    }

    private String startupNotice(List<McpServer> targets, Duration maxWait) {
        List<McpServer> stillStarting = targets.stream()
                .filter(server -> server.status() == McpServerStatus.STARTING)
                .sorted(Comparator.comparing(McpServer::name))
                .toList();
        if (stillStarting.isEmpty()) {
            return "";
        }
        String names = stillStarting.stream()
                .map(McpServer::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        long displaySeconds = Math.max(1, (long) Math.ceil(maxWait.toMillis() / 1000.0));
        return "Mcp 后台继续启动: " + names + "（超过 " + displaySeconds
                + "s，可用 /mcp 查看，/mcp logs <name> 看日志）";
    }

    private Thread startProgressPrinter(List<McpServer> targets, PrintStream out, Duration interval) {
        if (out == null || targets.isEmpty()) {
            return null;
        }
        Map<String, Instant> startedAt = new ConcurrentHashMap<>();
        targets.forEach(server -> startedAt.put(server.name(), Instant.now()));
        Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    TimeUnit.MILLISECONDS.sleep(interval.toMillis());
                    List<McpServer> starting = targets.stream()
                            .filter(server -> server.status() == McpServerStatus.STARTING)
                            .sorted(Comparator.comparing(McpServer::name))
                            .toList();
                    if (starting.isEmpty()) {
                        continue;
                    }
                    for (McpServer server : starting) {
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

    public synchronized String restart(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        unregisterTools(server);
        server.close();
        server.config().setDisabled(false);
        start(server);
        return server.status() == McpServerStatus.READY
                ? "✅ MCP server 已重启: " + name
                : "❌ MCP server 重启失败: " + name + " - " + server.errorMessage();
    }

    public synchronized String restartWithArgs(String name, List<String> args) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.config().setArgs(args);
        return restart(name);
    }

    public McpServer server(String name) {
        return servers.get(name);
    }

    public synchronized String disable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        unregisterTools(server);
        server.close();
        server.config().setDisabled(true);
        server.status(McpServerStatus.DISABLED);
        server.errorMessage(null);
        return "⏸️ MCP server 已禁用: " + name;
    }

    public synchronized String enable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.config().setDisabled(false);
        start(server);
        return server.status() == McpServerStatus.READY
                ? "▶️ MCP server 已启用: " + name
                : "❌ MCP server 启用失败: " + name + " - " + server.errorMessage();
    }

    public String logs(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        List<String> lines = server.logs();
        if (lines.isEmpty()) {
            return "📭 MCP server 暂无 stderr 日志: " + name;
        }
        return String.join(System.lineSeparator(), lines);
    }

    public Collection<McpServer> servers() {
        return servers.values().stream()
                .sorted(java.util.Comparator.comparing(McpServer::name))
                .toList();
    }

    public String formatStatus() {
        StringBuilder sb = new StringBuilder("🔌 MCP Servers\n");
        if (servers.isEmpty()) {
            sb.append("  未配置 MCP server。配置文件: ~/.mindcli/mcp.json 或 .mindcli/mcp.json");
            return sb.toString();
        }
        for (McpServer server : servers()) {
            String status = switch (server.status()) {
                case READY -> "● ready";
                case STARTING -> "… starting";
                case DISABLED -> "○ disabled";
                case ERROR -> "✗ error";
            };
            String tools = server.status() == McpServerStatus.READY
                    ? server.tools().size() + (server.tools().size() == 1 ? " tool" : " tools")
                    : "—";
            String uptime = server.status() == McpServerStatus.READY ? "uptime " + formatDuration(server.uptime()) : "";
            String pid = server.processId() == null ? "" : "pid " + server.processId();
            String error = server.status() == McpServerStatus.ERROR && server.errorMessage() != null
                    ? server.errorMessage()
                    : "";
            sb.append(String.format("  %-14s %-11s %-6s %-9s %-10s %s %s%n",
                    server.name(), status, server.transportName(), tools, uptime, pid, error));
        }
        return sb.toString().trim();
    }

    public String startupSummary() {
        if (servers.isEmpty()) {
            return "🔌 MCP server：未配置（可创建 ~/.mindcli/mcp.json 或 .mindcli/mcp.json）";
        }
        long ready = servers.values().stream().filter(s -> s.status() == McpServerStatus.READY).count();
        int tools = servers.values().stream().mapToInt(s -> s.tools().size()).sum();
        StringBuilder sb = new StringBuilder("🔌 启动 MCP server（" + servers.size() + " 个）...\n");
        for (McpServer server : servers()) {
            if (server.status() == McpServerStatus.READY) {
                sb.append(String.format("   ✓ %-14s %-6s %3d 工具%n",
                        server.name(), server.transportName(), server.tools().size()));
            } else if (server.status() == McpServerStatus.DISABLED) {
                sb.append(String.format("   ○ %-14s %-6s disabled%n", server.name(), server.transportName()));
            } else if (server.status() == McpServerStatus.STARTING) {
                sb.append(String.format("   … %-14s %-6s starting%n", server.name(), server.transportName()));
            } else {
                sb.append(String.format("   ✗ %-14s %-6s 启动失败: %s%n",
                        server.name(), server.transportName(), server.errorMessage()));
            }
        }
        sb.append("   ").append(ready).append("/").append(servers.size())
                .append(" 就绪，共 ").append(tools).append(" 个 MCP 工具");
        return sb.toString();
    }

    public List<McpResourceDescriptor> resourceCandidates() {
        return resourceCache.all();
    }

    public String resourceIndexForPrompt() {
        List<McpResourceDescriptor> resources = resourceCache.all().stream()
                .sorted(Comparator.comparing(McpResourceDescriptor::serverName)
                        .thenComparing(McpResourceDescriptor::uri))
                .limit(200)
                .toList();
        if (resources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## MCP Resources 索引（仅 URI / 描述，不含正文）\n\n");
        sb.append("长上下文模式下可参考以下资源索引判断是否需要读取 resource；需要正文时再调用对应 MCP resource 工具或使用用户显式 @-mention。\n\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- @").append(resource.serverName()).append(':').append(resource.uri());
            String displayName = resource.displayName();
            if (!displayName.equals(resource.uri())) {
                sb.append(" — ").append(displayName);
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("：").append(resource.description());
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" [").append(resource.mimeType()).append(']');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public String resources(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return "未找到 MCP server: " + serverName;
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            return "MCP server 未就绪: " + serverName + " (" + server.status() + ")";
        }
        try {
            List<McpResourceDescriptor> resources = refreshResources(server);
            return McpClient.formatResources(resources);
        } catch (Exception e) {
            return "读取 MCP resources 失败: " + e.getMessage();
        }
    }

    public String prompts(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return "未找到 MCP server: " + serverName;
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            return "MCP server 未就绪: " + serverName + " (" + server.status() + ")";
        }
        try {
            List<String> prompts = server.client().listPrompts();
            if (prompts.isEmpty()) {
                return "📭 该 MCP server 暂无 prompts: " + serverName;
            }
            StringBuilder sb = new StringBuilder("🧩 MCP prompts - ").append(serverName).append('\n');
            for (String prompt : prompts) {
                sb.append("- ").append(prompt).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "读取 MCP prompts 失败: " + e.getMessage();
        }
    }

    public ResourceReadResult readResourceForMention(String serverName, String uri) throws IOException {
        McpServer server = servers.get(serverName);
        if (server == null) {
            throw new IOException("未找到 MCP server: " + serverName);
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            throw new IOException("MCP server 未就绪: " + serverName + " (" + server.status() + ")");
        }
        long start = System.nanoTime();
        String toolName = McpToolDescriptor.namespaced(serverName, McpResourceTool.READ_RESOURCE);
        String args = "{\"uri\":\"" + uri.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        try {
            if (resourceCache.isServerStale(serverName)) {
                refreshResources(server);
            }
            List<McpResourceContent> contents = server.client().readResource(uri);
            resourceCache.markResourceFresh(serverName, uri);
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.allowByMention(
                    toolName, args, elapsedMillis(start)));
            return ResourceReadResult.from(contents);
        } catch (Exception e) {
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.error(
                    toolName, args, e.getMessage(), elapsedMillis(start)));
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    private void start(McpServer server) {
        unregisterTools(server);
        server.close();
        if (server.config().isDisabled()) {
            server.status(McpServerStatus.DISABLED);
            return;
        }
        server.status(McpServerStatus.STARTING);
        server.errorMessage(null);
        try {
            // 在单 server 启动路径里展开 ${VAR} 与校验 transport，
            // 单个失败仅标 ERROR，不会阻塞其他 server。
            configLoader.prepare(server.config());
            com.mindcli.capability.mcp.McpClient client = createOfficialClient(server);
            client.initialize();
            List<McpToolDescriptor> tools = buildToolList(server, client);
            replaceTools(server, client, tools);
            server.client(client);
            server.tools(tools);
            server.markStarted();
            server.status(McpServerStatus.READY);
        } catch (Exception e) {
            server.close();
            server.errorMessage(e.getMessage());
            server.status(McpServerStatus.ERROR);
        }
    }

    private List<McpToolDescriptor> buildToolList(McpServer server, McpClient client) throws IOException {
        List<McpToolDescriptor> tools = new ArrayList<>(client.listTools());
        if (client.supportsResources()) {
            List<McpResourceDescriptor> resources = client.listResources();
            resourceCache.put(server.name(), resources);
            tools.addAll(McpResourceTool.descriptors(server.name()));
        }
        validateNoDuplicateTools(server.name(), tools);
        return tools;
    }

    private void replaceTools(McpServer server, McpClient client, List<McpToolDescriptor> tools) {
        toolRegistry.replaceMcpToolOutputsForServer(server.name(), tools,
                descriptor -> isResourceVirtualTool(descriptor)
                        ? args -> ToolOutput.text(McpResourceTool.invoker(client, descriptor).apply(args))
                        : args -> invokeMcpToolOutput(client, descriptor, args));
    }

    private boolean isResourceVirtualTool(McpToolDescriptor descriptor) {
        return McpResourceTool.LIST_RESOURCES.equals(descriptor.name())
                || McpResourceTool.READ_RESOURCE.equals(descriptor.name());
    }

    private void registerNotificationHandlers(McpServer server, McpClient client) {
        NotificationRouter router = new NotificationRouter();
        router.on("notifications/tools/list_changed", ignored -> {
            try {
                List<McpToolDescriptor> tools = buildToolList(server, client);
                replaceTools(server, client, tools);
                server.tools(tools);
            } catch (Exception e) {
                server.errorMessage("tools/list_changed 处理失败: " + e.getMessage());
            }
        });
        router.on("notifications/resources/list_changed", ignored -> resourceCache.invalidateServer(server.name()));
        router.on("notifications/resources/updated", params -> {
            String uri = params.path("uri").asText("");
            if (!uri.isBlank()) {
                resourceCache.invalidateResource(server.name(), uri);
            }
        });
        client.onNotification(router);
    }

    private List<McpResourceDescriptor> refreshResources(McpServer server) throws IOException {
        List<McpResourceDescriptor> resources = server.client().listResources();
        resources = resources.stream()
                .sorted(Comparator.comparing(McpResourceDescriptor::uri))
                .toList();
        resourceCache.put(server.name(), resources);
        return resources;
    }

    /**
     * MCP 工具执行入口：把 LLM 给的 JSON 参数透传给 server 的 tools/call，并把异常转成 LLM 可读字符串。
     * 提取成独立方法是为了让 server 维度的错误信息（serverName/toolName）在堆栈和日志里清晰可见。
     */
    private static ToolOutput invokeMcpToolOutput(McpClient client, McpToolDescriptor descriptor, String argumentsJson) {
        try {
            return client.callToolOutput(descriptor.name(), argumentsJson);
        } catch (Exception e) {
            return ToolOutput.text("MCP 工具调用失败 (" + descriptor.serverName() + "/" + descriptor.name() + "): "
                    + e.getMessage());
        }
    }

    private com.mindcli.capability.mcp.McpClient createOfficialClient(McpServer server) {
        McpServerConfig config = server.config();
        var mapper = new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper());
        AtomicReference<com.mindcli.capability.mcp.McpClient> facade = new AtomicReference<>();
        McpSyncClient sdkClient;
        io.modelcontextprotocol.spec.McpClientTransport sdkTransport;
        if (config.isHttp()) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json");
            config.getHeaders().forEach(requestBuilder::header);
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(config.getUrl())
                    .jsonMapper(mapper)
                    .requestBuilder(requestBuilder)
                    .connectTimeout(Duration.ofSeconds(30))
                    .customizeClient(builder -> builder.connectTimeout(Duration.ofSeconds(30)))
                    .openConnectionOnStartup(false)
                    .build();
            sdkTransport = transport;
            sdkClient = io.modelcontextprotocol.client.McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(60))
                    .initializationTimeout(Duration.ofSeconds(com.mindcli.capability.mcp.McpClient.initializeTimeoutSeconds()))
                    .clientInfo(new McpSchema.Implementation("MindCLI", "1.0"))
                    .toolsChangeConsumer(ignored -> refreshToolsAfterNotification(server, facade.get()))
                    .resourcesChangeConsumer(ignored -> resourceCache.invalidateServer(server.name()))
                    .resourcesUpdateConsumer(contents -> {
                        if (contents != null) contents.forEach(content -> resourceCache.invalidateResource(server.name(), content.uri()));
                    })
                    .build();
        } else {
            String command = resolveCommand(config.getCommand());
            ServerParameters parameters = ServerParameters.builder(command)
                    .args(config.getArgs() == null ? List.of() : config.getArgs())
                    .env(config.getEnv() == null ? Map.of() : config.getEnv())
                    .build();
            MindCliStdioClientTransport transport = new MindCliStdioClientTransport(parameters, mapper, projectDir);
            sdkTransport = transport;
            sdkClient = io.modelcontextprotocol.client.McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(60))
                    .initializationTimeout(Duration.ofSeconds(com.mindcli.capability.mcp.McpClient.initializeTimeoutSeconds()))
                    .clientInfo(new McpSchema.Implementation("MindCLI", "1.0"))
                    .toolsChangeConsumer(ignored -> refreshToolsAfterNotification(server, facade.get()))
                    .resourcesChangeConsumer(ignored -> resourceCache.invalidateServer(server.name()))
                    .resourcesUpdateConsumer(contents -> {
                        if (contents != null) contents.forEach(content -> resourceCache.invalidateResource(server.name(), content.uri()));
                    })
                    .build();
        }
        com.mindcli.capability.mcp.McpClient result = new com.mindcli.capability.mcp.McpClient(
                server.name(), sdkClient, sdkTransport,
                sdkTransport instanceof MindCliStdioClientTransport stdio ? () -> stdio.stderrLines() : List::of);
        facade.set(result);
        return result;
    }

    private void refreshToolsAfterNotification(McpServer server, com.mindcli.capability.mcp.McpClient client) {
        if (client == null) return;
        try {
            List<McpToolDescriptor> tools = buildToolList(server, client);
            replaceTools(server, client, tools);
            server.tools(tools);
        } catch (Exception e) {
            server.errorMessage("tools/list_changed 处理失败: " + e.getMessage());
        }
    }

    private static String resolveCommand(String command) {
        if (command == null || !System.getProperty("os.name", "").toLowerCase().contains("win")
                || command.contains("/") || command.contains("\\")) return command;
        String lower = command.toLowerCase();
        if (lower.endsWith(".cmd") || lower.endsWith(".exe") || lower.endsWith(".bat")) return command;
        return command + ".cmd";
    }

    private void validateNoDuplicateTools(String serverName, List<McpToolDescriptor> tools) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (McpToolDescriptor tool : tools) {
            counts.merge(tool.name(), 1, Integer::sum);
        }
        List<String> duplicates = new ArrayList<>();
        counts.forEach((name, count) -> {
            if (count > 1) duplicates.add(name);
        });
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("MCP server " + serverName + " 返回重复工具名: " + duplicates);
        }
    }

    private void unregisterTools(McpServer server) {
        for (McpToolDescriptor tool : server.tools()) {
            toolRegistry.unregisterMcpTool(tool.namespacedName());
        }
        server.tools(List.of());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public record ResourceReadResult(String content, String mimeType) {
        static ResourceReadResult from(List<McpResourceContent> contents) {
            if (contents == null || contents.isEmpty()) {
                return new ResourceReadResult("", "text/plain");
            }
            StringBuilder text = new StringBuilder();
            String firstMimeType = null;
            for (McpResourceContent content : contents) {
                if (firstMimeType == null || firstMimeType.isBlank()) {
                    firstMimeType = content.mimeType();
                }
                if (content.isText()) {
                    text.append(content.text());
                } else {
                    text.append("[binary resource blob omitted, base64 length=")
                            .append(content.blob() == null ? 0 : content.blob().length())
                            .append(']');
                }
                text.append(System.lineSeparator());
            }
            return new ResourceReadResult(text.toString().trim(), firstMimeType);
        }
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h";
    }

    @Override
    public void close() {
        for (McpServer server : servers.values()) {
            unregisterTools(server);
            server.close();
        }
    }
}
