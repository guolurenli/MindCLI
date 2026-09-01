package com.mindcli.capability.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindcli.capability.browser.BrowserAuditMetadata;
import com.mindcli.capability.browser.BrowserCheckResult;
import com.mindcli.capability.browser.BrowserConnector;
import com.mindcli.capability.browser.BrowserGuard;
import com.mindcli.platform.llm.context.ContextProfile;
import com.mindcli.capability.lsp.LspDiagnosticReport;
import com.mindcli.capability.lsp.LspManager;
import com.mindcli.capability.memory.MemoryWriteResult;
import com.mindcli.capability.mcp.protocol.McpToolDescriptor;
import com.mindcli.platform.security.AuditLog;
import com.mindcli.platform.security.CommandGuard;
import com.mindcli.platform.security.PathGuard;
import com.mindcli.platform.security.PolicyException;
import com.mindcli.runtime.CancellationContext;
import com.mindcli.platform.snapshot.RestoreResult;
import com.mindcli.platform.snapshot.SnapshotService;
import com.mindcli.capability.skill.Skill;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.capability.tool.builtin.BrowserToolRegistrar;
import com.mindcli.capability.tool.builtin.CodeToolRegistrar;
import com.mindcli.capability.tool.builtin.FileToolRegistrar;
import com.mindcli.capability.tool.builtin.MemoryToolRegistrar;
import com.mindcli.capability.tool.builtin.ShellToolRegistrar;
import com.mindcli.capability.tool.builtin.SkillToolRegistrar;
import com.mindcli.capability.tool.builtin.SnapshotToolRegistrar;
import com.mindcli.capability.tool.builtin.WebToolRegistrar;
import com.mindcli.capability.tool.namespace.McpToolNamespace;
import com.mindcli.capability.tool.registry.ToolRegistrar;
import com.mindcli.capability.tool.registry.ToolRegistrationContext;
import com.mindcli.capability.web.FetchResult;
import com.mindcli.capability.web.HtmlExtractor;
import com.mindcli.capability.web.NetworkPolicy;
import com.mindcli.capability.web.SearchProvider;
import com.mindcli.capability.web.SearchProviderFactory;
import com.mindcli.capability.web.SearchResult;
import com.mindcli.capability.web.WebFetcher;

import java.io.File;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;
    private static final int MAX_READ_FILE_LINES = 2_000;
    private static final int MAX_GREP_RESULTS = 200;
    private static final int MAX_GREP_CONTEXT_LINES = 5;
    private static final int DEFAULT_GREP_MAX_CHARS = 24_000;
    private static final int MAX_GREP_MAX_CHARS = 60_000;
    private static final int DEFAULT_GREP_HEAD_LIMIT = 20;
    private static final String STEP_SEARCH_SERVER = "step_search";
    private static final String STEP_SEARCH_TOOL = "mcp__" + STEP_SEARCH_SERVER + "__web_search";
    private static final String STEP_FETCH_TOOL = "mcp__" + STEP_SEARCH_SERVER + "__web_fetch";
    private static final Set<String> SEARCH_EXCLUDED_DIRS = Set.of(
            ".git", ".mindcli", "target", "node_modules", "dist", "build", "coverage", ".idea", ".gradle"
    );
    // write_file 单次写入字节数上限。LLM 想塞超大内容时通常是误生成（重复粘贴 / hallucinate 大段日志），
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
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
    private BrowserConnector browserConnector;
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
        registerTools(new BrowserToolRegistrar());
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
            public String browserConnectTool(Map<String, String> args) {
                return ToolRegistry.this.browserConnectTool(args);
            }

            @Override
            public String browserDisconnectTool(Map<String, String> args) {
                return ToolRegistry.this.browserDisconnectTool(args);
            }

            @Override
            public String browserStatusTool(Map<String, String> args) {
                return ToolRegistry.this.browserStatusTool(args);
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
        fork.setBrowserConnector(this.browserConnector);
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

    public void setBrowserConnector(BrowserConnector browserConnector) {
        this.browserConnector = browserConnector;
    }

    public void setMemorySaver(Consumer<String> memorySaver) {
        this.memorySaver = memorySaver == null ? null : (fact, scope) -> {
            memorySaver.accept(fact);
            return MemoryWriteResult.legacyWritten(fact, scope);
        };
    }

    public void setScopedMemorySaver(BiConsumer<String, String> memorySaver) {
        this.memorySaver = memorySaver == null ? null : (fact, scope) -> {
            memorySaver.accept(fact, scope);
            return MemoryWriteResult.legacyWritten(fact, scope);
        };
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
        Path safe = pathGuard.resolveSafe(args.get("path"));
        try {
            return readFileForTool(safe, args);
        } catch (Exception e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    String writeFileTool(Map<String, String> args) {
        String path = args.get("path");
        String content = args.get("content") == null ? "" : args.get("content");
        int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > MAX_WRITE_FILE_BYTES) {
            throw new PolicyException("写入内容 " + contentBytes + " 字节超过 "
                    + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限");
        }
        Path safe = pathGuard.resolveSafe(path);
        String before = null;
        try {
            if (Files.exists(safe) && Files.isRegularFile(safe)) {
                before = Files.readString(safe);
            }
        } catch (Exception ignored) {
            // 二进制 / 大文件 / 编码错读不出来时，前文当 null 处理（diff 退化为长度提示）
        }
        try {
            Path parent = safe.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(safe, content);
            try {
                writeFileObserver.accept(path, new String[]{before, content});
            } catch (Exception ignored) {
                // observer 失败不能影响 write_file 主路径
            }
            runPostEditLspHook(path, safe);
            return "文件已写入: " + path;
        } catch (Exception e) {
            return "写入文件失败: " + e.getMessage();
        }
    }

    String listDirTool(Map<String, String> args) {
        Path safe = pathGuard.resolveSafe(args.get("path"));
        try {
            File[] files = safe.toFile().listFiles();
            if (files == null) {
                return "目录为空或不存在";
            }
            StringBuilder sb = new StringBuilder("目录内容:\n");
            for (File f : files) {
                sb.append(f.isDirectory() ? "[D] " : "[F] ")
                        .append(f.getName())
                        .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "列出目录失败: " + e.getMessage();
        }
    }

    private String readFileForTool(Path file, Map<String, String> args) throws IOException {
        if (!Files.isRegularFile(file)) {
            return "读取文件失败: 不是普通文件";
        }
        boolean ranged = args.containsKey("offset") || args.containsKey("limit");
        if (!ranged) {
            return "文件内容:\n" + Files.readString(file);
        }

        int offset = Math.max(1, parseInt(args.get("offset"), 1));
        int limit = Math.max(1, Math.min(parseInt(args.get("limit"), 200), MAX_READ_FILE_LINES));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int total = lines.size();
        if (offset > total) {
            return "文件内容: " + file.getFileName() + " 共 " + total + " 行，offset 超出范围";
        }

        int from = offset - 1;
        int to = Math.min(from + limit, total);
        StringBuilder sb = new StringBuilder();
        sb.append("文件内容: ").append(file.getFileName())
                .append(" (lines ").append(offset).append("-").append(to)
                .append(" of ").append(total).append(")\n");
        for (int i = from; i < to; i++) {
            sb.append(String.format("%5d | %s%n", i + 1, lines.get(i)));
        }
        if (to < total) {
            sb.append("...(已截断，可用 offset=").append(to + 1).append(" 继续读取)");
        }
        return sb.toString().trim();
    }

    String globFilesTool(Map<String, String> args) {
        return globFiles(args);
    }

    private String globFiles(Map<String, String> args) {
        String pattern = args.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "文件匹配失败: pattern 不能为空";
        }
        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        Path projectRoot = pathGuard.getRootPath();
        PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeGlob(pattern));
        PathMatcher fileNameMatcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeFileNameGlob(pattern));
        List<String> matches = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SearchFileVisitor(projectRoot, path -> {
                if (matches.size() >= maxResults) {
                    return;
                }
                Path relative = projectRoot.relativize(path);
                if (matcher.matches(relative) || fileNameMatcher.matches(path.getFileName())) {
                    matches.add(relative.toString().replace('\\', '/'));
                }
            }));
        } catch (Exception e) {
            return "文件匹配失败: " + e.getMessage();
        }

        if (matches.isEmpty()) {
            return "未找到匹配文件: " + pattern;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配文件 ").append(matches.size()).append(" 个");
        if (matches.size() >= maxResults) {
            sb.append("（已达到上限 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    String grepCodeTool(Map<String, String> args) {
        return grepCode(args);
    }

    private String grepCode(Map<String, String> args) {
        String query = args.get("pattern");
        if (query == null || query.isBlank()) {
            return "代码搜索失败: pattern 不能为空";
        }
        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        Path projectRoot = pathGuard.getRootPath();
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        int contextLines = clamp(parseInt(args.get("context_lines"), 0), 0, MAX_GREP_CONTEXT_LINES);
        boolean regex = parseBoolean(args.get("regex"), false);
        boolean caseSensitive = parseBoolean(args.get("case_sensitive"), true);
        int headLimit = clamp(parseInt(args.get("head_limit"), DEFAULT_GREP_HEAD_LIMIT), 1, 50);
        int maxChars = clamp(parseInt(args.get("max_chars"), DEFAULT_GREP_MAX_CHARS), 1_000, MAX_GREP_MAX_CHARS);
        CodeSearchRequest request = new CodeSearchRequest(
                query,
                root,
                projectRoot,
                args.get("glob"),
                regex,
                caseSensitive,
                contextLines,
                maxResults,
                headLimit
        );
        CodeSearchResult result = new RipgrepCodeSearchEngine(SEARCH_EXCLUDED_DIRS).search(request);

        if (!result.partialReason().isBlank() && result.matches().isEmpty()) {
            return "代码搜索失败: " + result.partialReason();
        }
        if (result.matches().isEmpty()) {
            return "未找到匹配内容: " + query;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配结果 ").append(result.matches().size()).append(" 条")
                .append(" (engine=").append(result.engine()).append(")");
        if (result.partial()) {
            sb.append("（partial: ").append(result.partialReason()).append("）");
        }
        sb.append(":\n");
        boolean truncatedByChars = false;
        int rendered = 0;
        for (int i = 0; i < result.matches().size(); i++) {
            GrepMatch match = result.matches().get(i);
            String matchHeader = (i + 1) + ". " + match.file() + ":" + match.lineNumber() + "\n";
            if (sb.length() + matchHeader.length() > maxChars) {
                truncatedByChars = true;
                break;
            }
            sb.append(i + 1).append(". ").append(match.file()).append(":").append(match.lineNumber()).append("\n");
            for (ContextLine line : match.context()) {
                String marker = line.lineNumber() == match.lineNumber() ? ">" : " ";
                String contextLine = String.format("   %s%5d | %s%n", marker, line.lineNumber(), line.text());
                if (sb.length() + contextLine.length() > maxChars) {
                    truncatedByChars = true;
                    break;
                }
                sb.append(contextLine);
            }
            rendered++;
            if (truncatedByChars) {
                break;
            }
        }
        if (truncatedByChars) {
            sb.append("\npartial: true（已达到 max_chars=").append(maxChars).append("，请缩小 path/glob/pattern 或提高 offset 后 read_file）");
        } else if (result.partial()) {
            sb.append("\npartial: true（").append(result.partialReason()).append("，请缩小 path/glob/pattern 继续搜索）");
        }
        appendSuggestedReads(sb, result.matches().subList(0, Math.min(rendered, result.matches().size())));
        return sb.toString().trim();
    }

    private void appendSuggestedReads(StringBuilder sb, List<GrepMatch> matches) {
        if (matches.isEmpty()) {
            return;
        }
        sb.append("\nsuggested_reads:");
        Set<String> seen = new LinkedHashSet<>();
        for (GrepMatch match : matches) {
            if (seen.size() >= 3 || !seen.add(match.file())) {
                continue;
            }
            int offset = Math.max(1, match.lineNumber() - 20);
            sb.append("\n- read_file {\"path\":\"")
                    .append(match.file().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\"offset\":").append(offset)
                    .append(",\"limit\":80}");
        }
    }

    String executeCommandTool(Map<String, String> args) {
        return executeCommand(args.get("command"));
    }

    String createProjectTool(Map<String, String> args) {
        String name = args.get("name");
        String type = args.get("type");
        Path projectRoot = pathGuard.resolveSafe(name);
        try {
            Files.createDirectories(projectRoot);

            switch (type.toLowerCase()) {
                case "java" -> {
                    Files.createDirectories(projectRoot.resolve("src/main/java"));
                    Files.createDirectories(projectRoot.resolve("src/main/resources"));
                    Files.writeString(projectRoot.resolve("pom.xml"),
                            String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                    "<project>\n" +
                                    "    <modelVersion>4.0.0</modelVersion>\n" +
                                    "    <groupId>com.example</groupId>\n" +
                                    "    <artifactId>%s</artifactId>\n" +
                                    "    <version>1.0</version>\n" +
                                    "</project>", name));
                }
                case "python" -> {
                    Files.createDirectories(projectRoot.resolve(name));
                    Files.writeString(projectRoot.resolve("main.py"), "# 主程序入口\n");
                    Files.writeString(projectRoot.resolve("requirements.txt"), "# 依赖列表\n");
                }
                case "node" -> Files.writeString(projectRoot.resolve("package.json"),
                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
            }
            return "项目已创建: " + name + " (类型: " + type + ")";
        } catch (Exception e) {
            return "创建项目失败: " + e.getMessage();
        }
    }

    String webSearchTool(Map<String, String> args) {
        return webSearch(args.get("query"), parseInt(args.get("top_k"), 5));
    }

    String webFetchTool(Map<String, String> args) {
        return webFetch(args.get("url"), parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS));
    }

    String browserConnectTool(Map<String, String> args) {
        return browserConnector == null
                ? "浏览器连接器未初始化，无法自动切换 shared 模式"
                : browserConnector.connectDefault();
    }

    String browserDisconnectTool(Map<String, String> args) {
        return browserConnector == null
                ? "浏览器连接器未初始化，无法切回 isolated 模式"
                : browserConnector.disconnect();
    }

    String browserStatusTool(Map<String, String> args) {
        return browserConnector == null
                ? "浏览器连接器未初始化，无法查看浏览器状态"
                : browserConnector.status();
    }

    String loadSkillTool(Map<String, String> args) {
        String name = args.get("name");
        if (name == null || name.isBlank()) {
            return "load_skill 失败: name 不能为空";
        }
        if (skillRegistry == null) {
            return "load_skill 失败: Skill 系统未初始化";
        }
        Skill skill = skillRegistry.findSkill(name);
        if (skill == null) {
            Skill any = skillRegistry.findAnySkill(name);
            if (any == null) {
                return "Skill '" + name + "' 未找到，可用 /skill list 查看可用 skill";
            }
            return "Skill '" + name + "' 已被禁用，可用 /skill on " + name + " 启用";
        }
        String body = skill.body();
        if (body == null) body = "";
        int max = 5 * 1024;
        if (body.length() > max) {
            body = body.substring(0, max)
                    + "\n\n...(skill body truncated, full content via /skill show " + name + ")";
        }
        return "## 已加载 Skill：" + name + "\n\n" + body;
    }

    String saveMemoryTool(Map<String, String> args) {
        String fact = args.get("fact");
        if (fact == null || fact.isBlank()) {
            return "保存长期记忆失败: fact 不能为空";
        }
        if (memorySaver == null) {
            return "保存长期记忆失败: 记忆保存器未初始化";
        }
        String normalized = fact.trim();
        String scope = "global".equalsIgnoreCase(args.get("scope")) ? "global" : "project";
        MemoryWriteResult result = memorySaver.apply(normalized, scope);
        if (result == null || result.message().isBlank()) {
            return MemoryWriteResult.legacyWritten(normalized, scope).message();
        }
        return result.message();
    }

    String searchMemoryTool(Map<String, String> args) {
        String query = args.get("query");
        if (query == null || query.isBlank()) {
            return "检索长期记忆失败: query 不能为空";
        }
        if (memorySearcher == null) {
            return "检索长期记忆失败: 记忆检索器未初始化";
        }
        int limit = Math.min(20, Math.max(1, parseInt(args.get("limit"), 5)));
        try {
            return memorySearcher.apply(query.trim(), limit);
        } catch (RuntimeException e) {
            return "检索长期记忆失败: " + e.getMessage();
        }
    }

    String readMemoryTool(Map<String, String> args) {
        String id = args.get("id");
        if (id == null || id.isBlank()) {
            return "读取长期记忆失败: id 不能为空";
        }
        if (memoryReader == null) {
            return "读取长期记忆失败: 记忆读取器未初始化";
        }
        try {
            return memoryReader.apply(id.trim());
        } catch (RuntimeException e) {
            return "读取长期记忆失败: " + e.getMessage();
        }
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern == null ? "**/*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "**/*";
        }
        if (!normalized.contains("/") && !normalized.startsWith("**")) {
            return "**/" + normalized;
        }
        return normalized;
    }

    private static String normalizeFileNameGlob(String pattern) {
        String normalized = pattern == null ? "*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "*";
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static final class SearchFileVisitor extends SimpleFileVisitor<Path> {
        private final Path projectRoot;
        private final java.util.function.Consumer<Path> fileConsumer;

        private SearchFileVisitor(Path projectRoot, java.util.function.Consumer<Path> fileConsumer) {
            this.projectRoot = projectRoot;
            this.fileConsumer = fileConsumer;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (!dir.equals(projectRoot) && SEARCH_EXCLUDED_DIRS.contains(name)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileConsumer.accept(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
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

    String webSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        if (shouldPreferStepSearch() && tools.containsKey(STEP_SEARCH_TOOL)) {
            ObjectNode args = mapper.createObjectNode();
            args.put("query", query.trim());
            putIfStepToolAccepts(STEP_SEARCH_TOOL, args, topK,
                    "top_k", "topK", "max_results", "num_results", "limit", "count");
            ToolOutput output = executeToolOutput(STEP_SEARCH_TOOL, args.toString());
            if (isUsableMcpOutput(output)) {
                return "🔍 [StepSearch] " + query.trim() + "\n\n" + output.text().trim();
            }
        }
        SearchProvider provider = searchProvider();
        if (!provider.isReady()) {
            return "⚠️ " + provider.unavailableHint();
        }
        try {
            List<SearchResult> results = provider.search(query.trim(), topK);
            return formatSearchResults(provider.name(), query, results);
        } catch (Exception e) {
            return "搜索失败 (" + provider.name() + "): " + e.getMessage();
        }
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

    private String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    String webFetch(String url, int maxChars) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }
        NetworkPolicy policy = networkPolicy();
        String denyReason = policy.checkUrl(url);
        if (denyReason != null) {
            return "❌ 网络访问被拒绝: " + denyReason;
        }
        String rateReason = policy.acquire();
        if (rateReason != null) {
            return "❌ " + rateReason;
        }
        if (shouldPreferStepSearch() && tools.containsKey(STEP_FETCH_TOOL)) {
            ObjectNode args = mapper.createObjectNode();
            args.put("url", url.trim());
            putIfStepToolAccepts(STEP_FETCH_TOOL, args, maxChars,
                    "max_chars", "maxChars", "limit", "max_length", "maxLength");
            ToolOutput output = executeToolOutput(STEP_FETCH_TOOL, args.toString());
            if (isUsableMcpOutput(output)) {
                return "🌐 [StepSearch] 抓取: " + url.trim() + "\n\n" + output.text().trim();
            }
        }

        try {
            WebFetcher.RawResponse raw = webFetcher().fetch(url.trim());
            HtmlExtractor.Extracted extracted = htmlExtractor().extract(raw.body(), raw.url());
            String markdown = extracted.markdown();
            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }
            FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
            return formatFetchResult(result);
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    private boolean shouldPreferStepSearch() {
        return "step".equals(currentProvider) && currentModel.startsWith("step-3.7-flash");
    }

    private void putIfStepToolAccepts(String toolName, ObjectNode args, int value, String... names) {
        if (value <= 0 || names == null || names.length == 0) {
            return;
        }
        JsonNode schema = mcpToolNamespace.inputSchema(toolName);
        JsonNode properties = schema == null ? null : schema.path("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }
        for (String name : names) {
            if (properties.has(name)) {
                args.put(name, value);
                return;
            }
        }
    }

    private boolean isUsableMcpOutput(ToolOutput output) {
        if (output == null || output.text() == null || output.text().isBlank()) {
            return false;
        }
        String text = output.text().trim();
        return !text.startsWith("[HITL]")
                && !text.startsWith("🛡️")
                && !text.startsWith("工具执行失败")
                && !text.startsWith("未知工具")
                && !text.startsWith("MCP 工具返回错误");
    }

    private String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 抓取: ").append(result.url()).append("\n");
        if (!result.title().isBlank()) {
            sb.append("📄 标题: ").append(result.title()).append("\n");
        }
        if (result.bodyEmpty()) {
            sb.append("\n⚠️ ").append(result.hint()).append("\n");
            return sb.toString();
        }
        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) {
            sb.append("（已截断）");
        }
        sb.append("\n\n---\n\n");
        sb.append(result.markdown());
        return sb.toString();
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

    private String executeCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return "执行命令失败: 命令不能为空";
        }
        String denyReason = CommandGuard.check(normalized);
        if (denyReason != null) {
            // 抛 PolicyException 让外层 executeTool 统一写 audit 并格式化拒绝消息，
            // 命令围栏与路径围栏的拒绝路径走同一个出口。
            throw new PolicyException(denyReason);
        }

        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mindcli-command-output");
            thread.setDaemon(true);
            return thread;
        });

        Process process = null;
        try {
            ProcessBuilder pb = commandProcessBuilder(normalized);
            pb.directory(new File(projectPath));
            pb.redirectErrorStream(true);
            process = pb.start();

            Process runningProcess = process;
            Future<String> outputFuture = outputReaderExecutor.submit(() -> readProcessOutput(runningProcess));

            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return "命令执行超时（" + commandTimeoutSeconds + "秒），已强制终止";
            }

            String output = getCommandOutput(outputFuture);
            int exitCode = process.exitValue();
            return String.format("命令执行完成 (exit code: %d, cwd: %s)\n%s", exitCode, projectPath, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return "用户取消了此次工具调用";
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "执行命令失败: " + e.getMessage();
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    private ProcessBuilder commandProcessBuilder(String command) {
        if (isWindows()) {
            String script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                    + "$OutputEncoding=[System.Text.Encoding]::UTF8; "
                    + command;
            return new ProcessBuilder("powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    script);
        }
        return new ProcessBuilder("bash", "-c", command);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < MAX_COMMAND_OUTPUT_CHARS) {
                    int remaining = MAX_COMMAND_OUTPUT_CHARS - output.length();
                    if (line.length() > remaining) {
                        output.append(line, 0, remaining);
                    } else {
                        output.append(line);
                    }
                    output.append("\n");
                }
            }
        }
        if (output.length() >= MAX_COMMAND_OUTPUT_CHARS) {
            return output.substring(0, MAX_COMMAND_OUTPUT_CHARS) + "\n...(输出已截断)";
        }
        return output.toString();
    }

    private String getCommandOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束，但输出读取超时)";
        }
    }

    // 记录定义
    private record Param(String name, String type, String description, boolean required) {}

    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    public record ToolInvocation(String id, String name, String argumentsJson) {}

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }
}
