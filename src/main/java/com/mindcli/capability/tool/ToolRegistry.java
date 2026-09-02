package com.mindcli.capability.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindcli.capability.browser.BrowserAuditMetadata;
import com.mindcli.capability.browser.BrowserCheckResult;
import com.mindcli.capability.browser.BrowserGuard;
import com.mindcli.platform.llm.context.ContextProfile;
import com.mindcli.capability.lsp.LspDiagnosticReport;
import com.mindcli.capability.lsp.LspManager;
import com.mindcli.capability.memory.MemoryWriteResult;
import com.mindcli.capability.mcp.protocol.McpToolDescriptor;
import com.mindcli.platform.security.AuditLog;
import com.mindcli.platform.security.PathGuard;
import com.mindcli.platform.security.PolicyException;
import com.mindcli.runtime.CancellationContext;
import com.mindcli.platform.snapshot.RestoreResult;
import com.mindcli.platform.snapshot.SnapshotService;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.capability.tool.builtin.CodeToolRegistrar;
import com.mindcli.capability.tool.builtin.FileToolExecutor;
import com.mindcli.capability.tool.builtin.FileToolRegistrar;
import com.mindcli.capability.tool.builtin.ProjectToolExecutor;
import com.mindcli.capability.tool.builtin.SkillToolExecutor;
import com.mindcli.capability.tool.builtin.MemoryToolRegistrar;
import com.mindcli.capability.tool.builtin.ShellToolRegistrar;
import com.mindcli.capability.tool.builtin.ShellCommandExecutor;
import com.mindcli.capability.tool.builtin.SkillToolRegistrar;
import com.mindcli.capability.tool.builtin.SnapshotToolRegistrar;
import com.mindcli.capability.tool.builtin.WebToolRegistrar;
import com.mindcli.capability.tool.namespace.McpToolNamespace;
import com.mindcli.capability.tool.registry.ToolRegistrar;
import com.mindcli.capability.tool.registry.ToolRegistrationContext;
import com.mindcli.capability.web.HtmlExtractor;
import com.mindcli.capability.web.NetworkPolicy;
import com.mindcli.capability.web.SearchProvider;
import com.mindcli.capability.web.SearchProviderFactory;
import com.mindcli.capability.web.WebFetcher;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.Locale;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    // 需要审计的内置工具（与 ApprovalPolicy 的 DANGEROUS_TOOLS 保持一致）；MCP 工具按前缀动态纳入审计。
    private static final Set<String> AUDIT_TOOLS = Set.of("write_file", "execute_command", "create_project", "revert_turn");
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final McpToolNamespace mcpToolNamespace = new McpToolNamespace(tools);
    private final long commandTimeoutSeconds;
    private final long toolBatchTimeoutSeconds;
    private static final int DEFAULT_FETCH_MAX_CHARS = 8_000;
    private String projectPath = System.getProperty("user.dir");
    private PathGuard pathGuard = new PathGuard(projectPath);
    private final AuditLog auditLog = new AuditLog();
    private SearchProvider searchProvider;
    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;
    private NetworkPolicy networkPolicy;
    private ContextProfile contextProfile = ContextProfile.from(null);
    private BrowserGuard browserGuard;
    private BiFunction<String, String, MemoryWriteResult> memorySaver;
    private BiFunction<String, Integer, String> memorySearcher;
    private Function<String, String> memoryReader;
    private SkillRegistry skillRegistry;
    private java.util.function.BiConsumer<String, String[]> writeFileObserver = (p, ba) -> {};
    private LspManager lspManager = new LspManager(projectPath);
    private SnapshotService snapshotService = SnapshotService.forProject(Path.of(projectPath));
    private boolean customSnapshotService;
    private volatile String currentProvider = "";
    private volatile String currentModel = "";

    public ToolRegistry() {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    ToolRegistry(long commandTimeoutSeconds) {
        this(commandTimeoutSeconds, Math.max(commandTimeoutSeconds + 5, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS));
    }

    ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        // 注册内置工具
        registerTools(new FileToolRegistrar());
        registerTools(new ShellToolRegistrar());
        registerTools(new CodeToolRegistrar());
        registerTools(new WebToolRegistrar());
        registerTools(new MemoryToolRegistrar());
        registerTools(new SkillToolRegistrar());
        registerTools(new SnapshotToolRegistrar());
    }

    void registerTools(ToolRegistrar registrar) {
        Objects.requireNonNull(registrar, "registrar").register(registrationContext());
    }

    private ToolRegistrationContext registrationContext() {
        return new ToolRegistrationContext() {
            @Override
            public ToolExecutors executors() {
                return toolExecutors();
            }

            @Override
            public void register(Tool tool) {
                Objects.requireNonNull(tool, "tool");
                validateRegistrarToolName(tool.name());
                Tool existing = tools.putIfAbsent(tool.name(), tool);
                if (existing != null) {
                    throw new IllegalArgumentException("工具已注册: " + tool.name());
                }
            }

            @Override
            public JsonNode parameters(Parameter... parameters) {
                Param[] params = Arrays.stream(parameters)
                        .map(parameter -> new Param(
                                parameter.name(),
                                parameter.type(),
                                parameter.description(),
                                parameter.required()))
                        .toArray(Param[]::new);
                return createParameters(params);
            }
        };
    }

    private ToolRegistrationContext.ToolExecutors toolExecutors() {
        return new ToolRegistrationContext.ToolExecutors() {
            @Override
            public String readFileTool(Map<String, String> args) {
                return ToolRegistry.this.readFileTool(args);
            }

            @Override
            public String writeFileTool(Map<String, String> args) {
                return ToolRegistry.this.writeFileTool(args);
            }

            @Override
            public String listDirTool(Map<String, String> args) {
                return ToolRegistry.this.listDirTool(args);
            }

            @Override
            public String globFilesTool(Map<String, String> args) {
                return ToolRegistry.this.globFilesTool(args);
            }

            @Override
            public String grepCodeTool(Map<String, String> args) {
                return ToolRegistry.this.grepCodeTool(args);
            }

            @Override
            public String executeCommandTool(Map<String, String> args) {
                return ToolRegistry.this.executeCommandTool(args);
            }

            @Override
            public String createProjectTool(Map<String, String> args) {
                return ToolRegistry.this.createProjectTool(args);
            }

            @Override
            public String webSearchTool(Map<String, String> args) {
                return ToolRegistry.this.webSearchTool(args);
            }

            @Override
            public String webFetchTool(Map<String, String> args) {
                return ToolRegistry.this.webFetchTool(args);
            }

            @Override
            public String loadSkillTool(Map<String, String> args) {
                return ToolRegistry.this.loadSkillTool(args);
            }

            @Override
            public String saveMemoryTool(Map<String, String> args) {
                return ToolRegistry.this.saveMemoryTool(args);
            }

            @Override
            public String searchMemoryTool(Map<String, String> args) {
                return ToolRegistry.this.searchMemoryTool(args);
            }

            @Override
            public String readMemoryTool(Map<String, String> args) {
                return ToolRegistry.this.readMemoryTool(args);
            }

            @Override
            public String revertTurnTool(Map<String, String> args) {
                return ToolRegistry.this.revertTurnTool(args);
            }
        };
    }

    private void validateRegistrarToolName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        if (name.startsWith("mcp__")) {
            throw new IllegalArgumentException("工具名称前缀 mcp__ 保留给 MCP 动态工具: " + name);
        }
    }

    /**
     * 设置代码检索的项目路径
     */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
        this.lspManager.setProjectPath(projectPath);
        if (!customSnapshotService) {
            this.snapshotService.close();
            this.snapshotService = SnapshotService.forProject(Path.of(projectPath));
        }
    }

    /**
     * 获取代码检索的项目路径
     */
    public String getProjectPath() {
        return projectPath;
    }

    /**
     * 创建一个指向指定项目目录的独立 ToolRegistry 副本（用于 worktree 隔离）。
     *
     * 复制与路径无关的共享配置（上下文画像、模型、skill、浏览器、写文件观察者），
     * 并把项目路径重定向到 worktree 目录。不复制 memory writer（worktree worker 不写记忆，
     * 避免污染主 MemoryManager 的 project 键）与 MCP 工具（v1 能力降级）。
     */
    public ToolRegistry forkForProject(Path projectPath) {
        ToolRegistry fork = newInstance();
        fork.setProjectPath(projectPath.toString());
        fork.setContextProfile(this.contextProfile);
        fork.setCurrentModel(this.currentProvider, this.currentModel);
        fork.setSkillRegistry(this.skillRegistry);
        fork.setBrowserGuard(this.browserGuard);
        fork.setWriteFileObserver(this.writeFileObserver);
        fork.setMemorySearcher(this.memorySearcher);
        fork.setMemoryReader(this.memoryReader);
        return fork;
    }

    /** 创建同类型的新注册表实例，供 {@link #forkForProject(Path)} 复用。 */
    protected ToolRegistry newInstance() {
        return new ToolRegistry();
    }

    public void setContextProfile(ContextProfile contextProfile) {
        if (contextProfile != null) {
            this.contextProfile = contextProfile;
        }
    }

    public ContextProfile getContextProfile() {
        return contextProfile;
    }

    public void setCurrentModel(String provider, String model) {
        this.currentProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        this.currentModel = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    public void setBrowserGuard(BrowserGuard browserGuard) {
        this.browserGuard = browserGuard;
    }

    protected BrowserGuard getBrowserGuard() {
        return browserGuard;
    }


    public void setScopedMemoryWriter(BiFunction<String, String, MemoryWriteResult> memorySaver) {
        this.memorySaver = memorySaver;
    }

    public void setMemorySearcher(BiFunction<String, Integer, String> memorySearcher) {
        this.memorySearcher = memorySearcher;
    }

    public void setMemoryReader(Function<String, String> memoryReader) {
        this.memoryReader = memoryReader;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }


    /**
     * 注册 write_file 写入观察者：参数 (path, [before, after])，
     * before == null 表示新建文件或读不出原文。
     * 用于把 write_file 接到行内 diff 渲染等只读副作用里；
     * 观察者抛异常不影响 write_file 主路径。
     */
    public void setWriteFileObserver(java.util.function.BiConsumer<String, String[]> observer) {
        this.writeFileObserver = observer == null ? (p, ba) -> {} : observer;
    }

    public void setLspManager(LspManager lspManager) {
        this.lspManager = lspManager == null ? new LspManager(projectPath) : lspManager;
        this.lspManager.setProjectPath(projectPath);
    }

    public LspDiagnosticReport flushPendingLspDiagnostics() {
        return lspManager == null ? LspDiagnosticReport.EMPTY : lspManager.flushPendingDiagnostics();
    }

    public SnapshotService getSnapshotService() {
        return snapshotService;
    }

    public void setSnapshotService(SnapshotService snapshotService) {
        this.snapshotService = snapshotService == null ? SnapshotService.forProject(Path.of(projectPath)) : snapshotService;
        this.customSnapshotService = snapshotService != null;
    }

    String readFileTool(Map<String, String> args) {
        return fileToolExecutor().read(args);
    }

    String writeFileTool(Map<String, String> args) {
        return fileToolExecutor().write(args);
    }

    String listDirTool(Map<String, String> args) {
        return fileToolExecutor().listDirectory(args);
    }

    private FileToolExecutor fileToolExecutor() {
        return new FileToolExecutor(pathGuard, writeFileObserver, this::runPostEditLspHook);
    }

    String globFilesTool(Map<String, String> args) {
        return new CodeSearchToolExecutor(pathGuard).glob(args);
    }

    String grepCodeTool(Map<String, String> args) {
        return new CodeSearchToolExecutor(pathGuard).grep(args);
    }

    String executeCommandTool(Map<String, String> args) {
        return ShellCommandExecutor.execute(args.get("command"), projectPath, commandTimeoutSeconds);
    }

    String createProjectTool(Map<String, String> args) {
        return new ProjectToolExecutor(pathGuard).create(args);
    }

    String webSearchTool(Map<String, String> args) {
        return webToolExecutor().search(args.get("query"), parseInt(args.get("top_k"), 5));
    }

    String webFetchTool(Map<String, String> args) {
        return webToolExecutor().fetch(args.get("url"), parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS));
    }

    String loadSkillTool(Map<String, String> args) {
        return new SkillToolExecutor(skillRegistry).load(args);
    }

    String saveMemoryTool(Map<String, String> args) {
        return memoryToolExecutor().save(args);
    }

    String searchMemoryTool(Map<String, String> args) {
        return memoryToolExecutor().search(args);
    }

    String readMemoryTool(Map<String, String> args) {
        return memoryToolExecutor().read(args);
    }

    private MemoryToolExecutor memoryToolExecutor() {
        return new MemoryToolExecutor(memorySaver, memorySearcher, memoryReader);
    }

    String revertTurnTool(Map<String, String> args) {
        int offset = parseInt(args.get("offset"), 1);
        try {
            RestoreResult result = snapshotService.restorePreTurn(Math.max(1, offset));
            return result.formatForCli();
        } catch (Exception e) {
            return "恢复快照失败: " + e.getMessage();
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private synchronized SearchProvider searchProvider() {
        if (searchProvider == null) {
            searchProvider = SearchProviderFactory.create();
        }
        return searchProvider;
    }

    private synchronized WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private synchronized HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }

    private synchronized NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    private void runPostEditLspHook(String displayPath, Path safePath) {
        try {
            if (lspManager != null) {
                lspManager.runPostEditLspHook(displayPath, safePath);
            }
        } catch (Exception ignored) {
            // LSP 诊断是 post-edit 辅助信号，失败不能影响工具主结果。
        }
    }

    private WebToolExecutor webToolExecutor() {
        return new WebToolExecutor(searchProvider(), webFetcher(), htmlExtractor(), networkPolicy(),
                currentProvider, currentModel, this::executeToolOutput, mcpToolNamespace::inputSchema);
    }

    /**
     * 创建参数定义
     */
    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    /**
     * 获取所有工具定义（用于LLM）
     */
    public List<com.mindcli.platform.llm.LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> new com.mindcli.platform.llm.LlmClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * 注册一个 MCP 工具到 ToolRegistry。
     *
     * @param descriptor 工具描述（含 namespacedName 如 mcp__filesystem__read_file）
     * @param invoker    工具执行器：输入 JSON 参数字符串，输出给 LLM 看的字符串结果。
     *                   typically lambda 在内部调用 McpClient.callTool 并处理异常 → 字符串。
     */
    public synchronized void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        registerMcpToolOutput(descriptor, args -> ToolOutput.text(invoker.apply(args)));
    }

    public synchronized void registerMcpToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
        mcpToolNamespace.registerToolOutput(descriptor, invoker);
    }

    public synchronized void registerMcpToolExecution(McpToolDescriptor descriptor,
                                                      Function<String, ToolExecution> invoker) {
        mcpToolNamespace.registerToolExecution(descriptor, invoker);
    }

    public synchronized void unregisterMcpTool(String toolName) {
        mcpToolNamespace.unregisterTool(toolName);
    }

    public synchronized void replaceMcpToolsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                      Function<McpToolDescriptor, Function<String, String>> invokerFactory) {
        replaceMcpToolOutputsForServer(serverName, newTools,
                descriptor -> args -> ToolOutput.text(invokerFactory.apply(descriptor).apply(args)));
    }

    public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        mcpToolNamespace.replaceToolOutputsForServer(serverName, newTools, invokerFactory);
    }

    public synchronized void replaceMcpToolExecutionsForServer(
            String serverName,
            List<McpToolDescriptor> newTools,
            Function<McpToolDescriptor, Function<String, ToolExecution>> invokerFactory) {
        mcpToolNamespace.replaceToolExecutionsForServer(serverName, newTools, invokerFactory);
    }

    /**
     * 执行工具调用
     *
     * 危险工具（write_file / execute_command / create_project）会写一行审计：
     * - 策略拦截（PathGuard / CommandGuard / 文件大小上限）→ deny
     * - 普通异常 → error
     * - 其他情况 → allow（仅表示工具调用真的发生过，工具内部的业务错误仍以返回字符串呈现给 LLM）
     */
    public String executeTool(String name, String argumentsJson) {
        return executeToolExecution(name, argumentsJson).output().text();
    }

    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        if (isLegacyExecuteToolOverride()) {
            return ToolOutput.text(executeTool(name, argumentsJson));
        }
        return executeToolExecution(name, argumentsJson).output();
    }

    public ToolExecution executeToolExecution(String name, String argumentsJson) {
        if (isLegacyExecuteToolOverride()) {
            return ToolExecution.completed(ToolOutput.text(executeTool(name, argumentsJson)), argumentsJson);
        }
        return doExecuteToolExecution(name, argumentsJson);
    }

    protected ToolOutput doExecuteTool(String name, String argumentsJson) {
        return doExecuteToolExecution(name, argumentsJson).output();
    }

    protected ToolExecution doExecuteToolExecution(String name, String argumentsJson) {
        if (CancellationContext.isCancelled()) {
            return ToolExecution.cancelled("用户取消了此次工具调用", argumentsJson, "用户取消");
        }
        Tool tool = tools.get(name);
        if (tool == null) {
            String text = "未知工具: " + name;
            return ToolExecution.failed(ToolOutput.text(text), argumentsJson, text, "UNKNOWN_TOOL");
        }

        boolean shouldAudit = shouldAudit(name);
        long start = System.nanoTime();
        BrowserAuditMetadata auditMetadata = null;

        try {
            McpToolNamespace.RegisteredTool mcpTool = mcpToolNamespace.get(name);
            if (mcpTool != null) {
                BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, false);
                auditMetadata = browserCheck.metadata();
                if (browserCheck.blocked()) {
                    throw new PolicyException(browserCheck.reason());
                }
                ToolExecution execution = mcpTool.invoker().apply(argumentsJson);
                if (execution == null) {
                    execution = ToolExecution.failed(
                            ToolOutput.text("工具执行失败: MCP invoker returned null"),
                            argumentsJson,
                            "MCP invoker returned null",
                            "MCP_TOOL_NULL");
                }
                ToolOutput output = execution.output();
                if (browserGuard != null) {
                    browserGuard.applyAfterExecution(name, argumentsJson, output.text());
                }
                if (shouldAudit) {
                    auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
                }
                return execution;
            }

            JsonNode args = mapper.readTree(argumentsJson);
            Map<String, String> argMap = new HashMap<>();
            args.fields().forEachRemaining(entry ->
                    argMap.put(entry.getKey(), entry.getValue().asText()));
            String result = tool.executor().execute(argMap);
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
            }
            ToolOutput output = ToolOutput.text(result);
            return isPartialToolResult(name, result)
                    ? ToolExecution.partial(output, argumentsJson)
                    : ToolExecution.completed(output, argumentsJson);
        } catch (PolicyException e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return ToolExecution.deniedByPolicy(
                    "🛡️ 策略拒绝: " + e.getMessage(), argumentsJson, e.getMessage());
        } catch (Exception e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.error(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolExecution.failed(
                    ToolOutput.text("工具执行失败: " + message), argumentsJson, message, "TOOL_FAILED");
        }
    }

    private static boolean isPartialToolResult(String name, String text) {
        return "grep_code".equals(name) && text != null && text.contains("partial: true");
    }

    private boolean isLegacyExecuteToolOverride() {
        try {
            return getClass()
                    .getMethod("executeTool", String.class, String.class)
                    .getDeclaringClass() != ToolRegistry.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    protected BrowserCheckResult checkBrowserTool(String name, String argumentsJson, boolean previewOnly) {
        if (browserGuard == null || !BrowserGuard.isChromeTool(name)) {
            return BrowserCheckResult.allow(null);
        }
        return browserGuard.check(name, argumentsJson, !previewOnly);
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }

    public long getToolBatchTimeoutSeconds() {
        return toolBatchTimeoutSeconds;
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    private static boolean shouldAudit(String name) {
        return AUDIT_TOOLS.contains(name) || (name != null && name.startsWith("mcp__"));
    }


    // 记录定义
    private record Param(String name, String type, String description, boolean required) {}

    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    public record ToolInvocation(String id, String name, String argumentsJson) {}

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }
}
