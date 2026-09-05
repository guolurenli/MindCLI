package com.mindcli.app.cli;

import com.mindcli.agent.Agent;
import com.mindcli.agent.team.AgentOrchestrator;
import com.mindcli.agent.PlanExecuteAgent;
import com.mindcli.agent.team.SubAgent;
import com.mindcli.agent.profile.AgentProfile;
import com.mindcli.agent.profile.AgentProfileLoader;
import com.mindcli.capability.browser.BrowserGuard;
import com.mindcli.capability.browser.BrowserSession;
import com.mindcli.capability.browser.SensitivePagePolicy;
import com.mindcli.app.cli.command.AgentCommandHandler;
import com.mindcli.app.cli.command.BrowserCommandHandler;
import com.mindcli.app.cli.command.CliCommandRouter;
import com.mindcli.app.cli.command.ConfigCommandHandler;
import com.mindcli.app.cli.command.ExportCommandHandler;
import com.mindcli.app.cli.command.SlashCommandCatalog;
import com.mindcli.app.cli.command.WechatCliCommandHandler;
import com.mindcli.app.cli.interaction.CliInputSupport;
import com.mindcli.app.cli.interaction.MindCliCompleter;
import com.mindcli.app.cli.interaction.MindCliHighlighter;
import com.mindcli.app.cli.interaction.MindCliHistory;
import com.mindcli.app.cli.runtime.CliRuntimeCoordinator;
import com.mindcli.app.cli.runtime.CliModeFactory;
import com.mindcli.app.cli.runtime.CliRecoverableRunDiscovery;
import com.mindcli.app.cli.runtime.CliRunResumer;
import com.mindcli.app.cli.runtime.CliRuntimeServerBootstrap;
import com.mindcli.platform.config.MindCliConfig;
import com.mindcli.platform.hitl.HitlHandler;
import com.mindcli.platform.hitl.HitlToolRegistry;
import com.mindcli.platform.hitl.SwitchableHitlHandler;
import com.mindcli.platform.hitl.RendererHitlHandler;
import com.mindcli.platform.hitl.TerminalHitlHandler;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.llm.LlmClientFactory;
import com.mindcli.platform.render.Renderer;
import com.mindcli.platform.render.RendererFactory;
import com.mindcli.platform.render.StatusInfo;
import com.mindcli.platform.render.inline.InlineRenderer;
import com.mindcli.platform.render.inline.TerminalMascotRenderer;
import com.mindcli.capability.image.ClipboardImage;
import com.mindcli.capability.mcp.McpServerManager;
import com.mindcli.capability.mcp.mention.AtMentionExpander;
import com.mindcli.agent.plan.ExecutionPlan;
import com.mindcli.runtime.CancellationContext;
import com.mindcli.runtime.CancellationToken;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentModeRouter;
import com.mindcli.runtime.run.AgentRuntime;
import com.mindcli.runtime.run.AgentRunResult;
import com.mindcli.runtime.run.AgentRunStatus;
import com.mindcli.runtime.run.ModeAdapter;
import com.mindcli.runtime.run.mode.PlanModeAdapter;
import com.mindcli.runtime.run.mode.ReActModeAdapter;
import com.mindcli.runtime.run.store.RunStore;
import com.mindcli.runtime.run.recovery.RunRecoveryPlan;
import com.mindcli.runtime.run.recovery.RunRecoveryService;
import com.mindcli.runtime.run.session.SessionContext;
import com.mindcli.runtime.run.mode.SingleAgentAdapter;
import com.mindcli.runtime.run.mode.TeamModeAdapter;
import com.mindcli.runtime.task.DurableTaskManager;
import com.mindcli.platform.snapshot.SnapshotService;
import com.mindcli.capability.skill.SkillRegistry;
import com.mindcli.platform.render.terminal.AnsiStyle;
import com.mindcli.app.wechat.WechatCommandMain;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Attributes;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Reference;
import org.jline.utils.NonBlockingReader;
import org.jline.widget.AutosuggestionWidgets;
import org.jline.widget.AutopairWidgets;
import org.jline.console.CmdDesc;
import org.jline.keymap.KeyMap;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MindCLI v16.1.0 - Terminal-First Agent IDE
 * 支持 ReAct、Plan-and-Execute、Memory、Multi-Agent、HITL、并行工具调用、多模型切换、MCP、CDP 会话复用
 * 第 15 期新增：Skill 系统（三层加载 + load_skill 工具 + tool_result 即时注入）、内置 web-access skill
 * 第 16.1 期形态修正：抽出 Renderer 接口，默认使用 inline 流式渲染（Claude Code 风格）
 *   - inline 流式：prompt 下方 inline 状态区、行内可折叠工具块、行内 git diff、单字符 HITL 提示、命令 palette
 *   - plain：纯 println 兜底
 * HITL 增强：路径围栏（PathGuard）、命令快速拒绝（CommandGuard）、操作审计链（AuditLog）—— 见 com.mindcli.platform.security
 */
public class Main {
    private static final String VERSION = "16.1.0";
    private static final CliRuntimeCoordinator RUNTIME_COORDINATOR = new CliRuntimeCoordinator();
    private static final String BRACKETED_PASTE_BEGIN = "[200~";
    private static final String BRACKETED_PASTE_END = "\u001b[201~";
    private static final int CTRL_O = 15;

    enum EscapeSequenceType {
        STANDALONE_ESC,
        BRACKETED_PASTE,
        CONTROL_SEQUENCE,
        OTHER
    }

    private record PromptInput(String text, boolean canceled) {
        static PromptInput submitted(String text) {
            return new PromptInput(text, false);
        }

        static PromptInput canceledInput() {
            return new PromptInput("", true);
        }
    }

    private record PrefillResult(String seedBuffer, boolean canceled, boolean submitted) {
        static PrefillResult canceledInput() {
            return new PrefillResult("", true, false);
        }

        static PrefillResult submittedInput() {
            return new PrefillResult("", false, true);
        }

        static PrefillResult seed(String seedBuffer) {
            return new PrefillResult(seedBuffer, false, false);
        }
    }

    private record KeyReadResult(Integer key, boolean ignoredControlSequence) {
        static KeyReadResult keyPressed(int key) {
            return new KeyReadResult(key, false);
        }

        static KeyReadResult ignoredSequence() {
            return new KeyReadResult(null, true);
        }

        static KeyReadResult unavailable() {
            return new KeyReadResult(null, false);
        }
    }

    public static void main(String[] args) {
        configureAwtForCli();
        if (WechatCommandMain.isWechatCommand(args)) {
            configureLogging();
            int code = WechatCommandMain.run(args);
            if (code != 0) {
                System.exit(code);
            }
            return;
        }
        if (CliRuntimeServerBootstrap.isRuntimeServeCommand(args)) {
            configureLogging();
            CliRuntimeServerBootstrap.startRuntimeApiAndBlock(args);
            return;
        }

        configureLogging();
        TerminalEncoding.Plan terminalEncoding = TerminalEncoding.detect();
        TerminalEncoding.configureStandardStreams(terminalEncoding);

        MindCliConfig config = MindCliConfig.load();
        LlmClient llmClient = LlmClientFactory.createFromConfig(config);
        if (llmClient == null) {
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.err.println("请在 .env 文件中添加 GLM_API_KEY、DEEPSEEK_API_KEY、STEP_API_KEY、KIMI_API_KEY、FREELLMAPI_API_KEY 或 XFYUN_MAAS_API_KEY");
            System.exit(1);
        }
        AtomicReference<LlmClient> llmClientRef = new AtomicReference<>(llmClient);

        try (Terminal terminal = TerminalEncoding.applyTo(
                TerminalBuilder.builder().system(true).dumb(true),
                terminalEncoding).build()) {
            refreshTerminalColumns(terminal);
            TerminalHitlHandler terminalHitlHandler = new TerminalHitlHandler(false);
            SwitchableHitlHandler hitlHandler = new SwitchableHitlHandler(terminalHitlHandler);
            HitlToolRegistry hitlToolRegistry = new HitlToolRegistry(hitlHandler);
            BrowserSession browserSession = new BrowserSession();
            hitlToolRegistry.setBrowserGuard(new BrowserGuard(browserSession, new SensitivePagePolicy()));
            McpServerManager mcpServerManager = new McpServerManager(hitlToolRegistry, Path.of("."));
            AtomicReference<SkillRegistry> skillRegistryRef = new AtomicReference<>();

            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .history(new MindCliHistory())
                    .completer(new MindCliCompleter(mcpServerManager::resourceCandidates,
                            () -> skillRegistryRef.get() == null ? List.of() : skillRegistryRef.get().allSkills()))
                    .highlighter(new MindCliHighlighter())
                    .build();
            lineReader.option(LineReader.Option.BRACKETED_PASTE, true);
            lineReader.option(LineReader.Option.AUTO_LIST, true);
            lineReader.option(LineReader.Option.AUTO_MENU, true);
            configureHistory(lineReader, Path.of(System.getProperty("user.home")));
            configureSlashCommandHint(lineReader);
            configureJLineInteractiveWidgets(lineReader);

            // JLine-first：命令输出、Agent 流式内容都走同一条 Renderer.stream() 通道。
            // 启动猫耳图由 native chafa 直接写真实终端；状态栏必须在它之后启动，
            // 避免 JLine Status/scroll-region 改变 chafa 的终端探测与显示效果。
            Renderer renderer = RendererFactory.create(RendererFactory.resolveMode(), terminal);
            RendererHitlHandler rendererHitl = new RendererHitlHandler(renderer, hitlHandler.isEnabled());
            hitlHandler.setDelegate(rendererHitl);
            if (renderer instanceof InlineRenderer inline) {
                inline.bindLineReader(lineReader);
            }
            PrintStream ui = renderer.stream();

            String startupNote = terminalEncoding.startupNote();
            try {
                McpConfigBootstrapResult bootstrapResult = ensureDefaultMcpConfig(Path.of(System.getProperty("user.home")));
                if (!bootstrapResult.message().isBlank()) {
                    startupNote = appendStartupNote(startupNote, bootstrapResult.message());
                }
                mcpServerManager.loadConfiguredServers();
                Duration mcpWait = mcpStartupWait();
                mcpServerManager.startAll(null, mcpWait);
                startupNote = appendStartupNote(startupNote, mcpServerManager.startupNotice(mcpWait));
                Runtime.getRuntime().addShutdownHook(new Thread(mcpServerManager::close, "mindcli-mcp-shutdown"));
            } catch (Exception e) {
                startupNote = appendStartupNote(startupNote, "MCP 初始化失败: " + e.getMessage());
            }
            AtMentionExpander mentionExpander = new AtMentionExpander(mcpServerManager);
            LocalPathMentionExpander localPathMentionExpander = new LocalPathMentionExpander(Path.of("."));

            // === Skill 系统初始化 ===
            Path home = Path.of(System.getProperty("user.home"));
            Path skillsCacheDir = home.resolve(".mindcli/skills-cache");
            Path userSkillsDir = home.resolve(".mindcli/skills");
            Path projectSkillsDir = Path.of(".mindcli/skills").toAbsolutePath();
            try {
                new com.mindcli.capability.skill.SkillBuiltinExtractor(skillsCacheDir).extractAll();
            } catch (Exception e) {
                startupNote = appendStartupNote(startupNote, "内置 skill 解压失败: " + e.getMessage());
            }
            com.mindcli.capability.skill.SkillStateStore skillStateStore = new com.mindcli.capability.skill.SkillStateStore(home.resolve(".mindcli/skills.json"));
            com.mindcli.capability.skill.SkillRegistry skillRegistry = new com.mindcli.capability.skill.SkillRegistry(
                    skillsCacheDir, userSkillsDir, projectSkillsDir, skillStateStore);
            skillRegistry.reload();
            skillRegistryRef.set(skillRegistry);
            hitlToolRegistry.setSkillRegistry(skillRegistry);

            Agent reactAgent = new Agent(llmClient, hitlToolRegistry);
            startupNote = appendStartupNote(startupNote,
                    CliRecoverableRunDiscovery.startupNotice(reactAgent.runStore()));
            SessionContext sessionContext = new SessionContext();
            reactAgent.setSessionContext(sessionContext);
            reactAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
            reactAgent.setSkillRegistry(skillRegistry);
            DurableTaskManager taskManager = CliRuntimeServerBootstrap.openTaskManager(llmClientRef);
            taskManager.start();
            Runtime.getRuntime().addShutdownHook(new Thread(taskManager::close, "mindcli-task-shutdown"));
            WechatCliCommandHandler.WechatRuntimeController wechatRuntime =
                    new WechatCliCommandHandler.WechatRuntimeController(renderer);
            Runtime.getRuntime().addShutdownHook(new Thread(wechatRuntime::stop, "mindcli-wechat-shutdown"));
            CliCommandRouter commandRouter = new CliCommandRouter(new CliCommandRouter.Context(
                    ui,
                    reactAgent,
                    renderer,
                    lineReader,
                    skillRegistry,
                    skillStateStore,
                    mcpServerManager,
                    taskManager,
                    wechatRuntime,
                    sessionContext,
                    hitlHandler,
                    phase -> renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, phase)),
                    config,
                    llmClientRef::get,
                    browserSession,
                    hitlToolRegistry,
                    runId -> resumeRun(runId, reactAgent, llmClientRef.get(), terminal, lineReader,
                            ui, mcpServerManager, skillRegistry, sessionContext)));
            CliStartupView.StartupScreenInfo startupScreenInfo =
                    startupScreenInfo(llmClient, mcpServerManager, skillRegistry, startupNote);
            List<String> startupBannerLines = startupScreenLines(startupScreenInfo);
            TerminalMascotRenderer.renderStartupMascot(terminal);
            printStartupScreen(ui, startupBannerLines);
            renderer.start();
            renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
            boolean nextTaskUsePlanMode = false;
            boolean nextTaskUseTeamMode = false;

            reactAgent.setRenderer(renderer);
            reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled);
            reactAgent.getToolRegistry().setWriteFileObserver(
                    (path, ba) -> renderer.appendDiff(path, ba[0], ba[1]));

            // Day 3：inline 模式绑 Ctrl+O 到 BlockRegistry.toggleLast 实现折叠块展开/收起
            boolean spaciousPrompt = false;
            if (renderer instanceof InlineRenderer inline) {
                bindCtrlOToFoldableBlocks(lineReader, inline);
            }
            spaciousPrompt = defaultSpaciousPrompt(spaciousPrompt);
            bindCtrlVToClipboardImage(lineReader);
            bindEscToClearInput(lineReader);

            while (true) {
                refreshTerminalColumns(terminal);
                PromptInput promptInput;
                try {
                    promptInput = readPromptInput(terminal, lineReader, renderer,
                            nextTaskUsePlanMode || nextTaskUseTeamMode, spaciousPrompt);
                } catch (UserInterruptException e) {
                    continue;  // Ctrl+C 跳过
                } catch (EndOfFileException e) {
                    break;  // Ctrl+D 退出
                }
                if (renderer instanceof InlineRenderer inline) {
                    inline.clearAcceptedInput(promptInput.text());
                }

                if (promptInput.canceled()) {
                    if (nextTaskUsePlanMode) {
                        nextTaskUsePlanMode = false;
                        ui.println("↩️ 已取消待执行的 Plan-and-Execute，回到默认 ReAct。\n");
                    }
                    if (nextTaskUseTeamMode) {
                        nextTaskUseTeamMode = false;
                        ui.println("↩️ 已取消待执行的 Multi-Agent，回到默认 ReAct。\n");
                    }
                    continue;
                }

                String input = promptInput.text().trim();

                if (input.isEmpty()) {
                    continue;
                }

                //1.用户输入文本解析成命令对象
                CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);
                boolean submittedInputRendered = false;

                //2.判断是否是有效命令（不是空指令NONE）
                if(command.type() != CliCommandParser.CommandType.NONE){
                    renderer.beginTurn();     //开启一轮交互渲染上下文
                    printSubmittedInput(renderer, ui, input);    //把用户输入打印到界面
                    submittedInputRendered = true;    //标记：输入已经渲染输出过
                }
                if (commandRouter.dispatch(command)) {
                    continue;
                }
                switch (command.type()) {
                    case UNKNOWN_COMMAND -> {
                        ui.println("❌ 未知命令: " + command.payload());
                        printSlashCommandHelp(ui);
                        continue;
                    }
                    case EXIT -> {
                        ui.println("\n👋 再见!");
                        wechatRuntime.stop();
                        renderer.close();
                        return;
                    }
                    case CANCEL -> {
                        ui.println("当前没有正在运行的任务。\n");
                        continue;
                    }
                    case SWITCH_PLAN -> {
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskUsePlanMode = true;
                            ui.println("📋 下一条任务将使用 Plan-and-Execute 模式，输入任务前按 ESC 可取消，执行完成后自动回到默认 ReAct。\n");
                            continue;
                        }
                        input = command.payload();
                    }
                    case SWITCH_TEAM -> {
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskUseTeamMode = true;
                            ui.println("👥 下一条任务将使用 Multi-Agent 协作模式（规划者 + 执行者 + 检查者），输入任务前按 ESC 可取消，执行完成后自动回到默认 ReAct。\n");
                            continue;
                        }
                        input = command.payload();
                    }
                    case SWITCH_MODEL -> {
                        String selection = command.payload();
                        if (selection == null || selection.isEmpty()) {
                            ui.println("🤖 当前模型: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
                            ui.println("   GLM 明确模型：");
                            ui.println("   /model glm-5.1       - 切换到 GLM-5.1");
                            ui.println("   /model glm-5v-turbo  - 切换到 GLM-5V-Turbo 多模态");
                            ui.println("   其它 provider 使用你配置里的具体模型：");
                            ui.println("   /model deepseek      - 切换到 DeepSeek（读取配置模型）");
                            ui.println("   /model step          - 切换到 StepFun（读取配置模型）");
                            ui.println("   /model kimi          - 切换到 Kimi（读取配置模型）");
                            ui.println("   /model freellmapi    - 切换到本地 FreeLLMAPI（读取配置模型）");
                            ui.println("   /model xfyun         - 切换到讯飞星辰 MaaS（读取配置模型）\n");
                        } else {
                            ModelSelection target = resolveModelSelection(selection);
                            if (target.explicitModel()) {
                                ensureProviderConfig(config, target.provider()).setModel(target.model());
                            }
                            LlmClient newClient = LlmClientFactory.create(target.provider(), config);
                            if (newClient == null) {
                                ui.println("❌ 切换失败：未配置 " + target.provider() + " 的 API Key\n");
                            } else {
                                llmClient = newClient;
                                llmClientRef.set(newClient);
                                config.setDefaultProvider(target.provider());
                                config.save();
                                reactAgent.setLlmClient(llmClient);
                                ui.println("✅ 已切换到: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
                                ui.println("   上下文策略: " + reactAgent.getMemoryManager().getContextProfile().summary());
                                ui.println("   对话上下文已保留，使用 /clear 可清空\n");
                                renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                            }
                        }
                        continue;
                    }
                    case AGENT -> {
                        String projectPath = reactAgent.getToolRegistry().getProjectPath();
                        AgentCommandHandler.AgentCommandTarget target =
                                AgentCommandHandler.parse(command.payload());
                        List<AgentProfile> profiles = AgentProfileLoader.load(Path.of(projectPath));
                        AgentProfile profile = AgentCommandHandler.find(profiles, target.name());
                        if (profile == null) {
                            ui.println("❌ 未找到子代理: " + target.name() + "（用 /agent 查看列表）\n");
                            continue;
                        }
                        SnapshotService agentSnapshotService = reactAgent.getToolRegistry().getSnapshotService();
                        RunStore agentRunStore = reactAgent.runStore();
                        String agentTask = target.task();
                        LlmClient activeClient = llmClient;
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "agent"));
                        String agentResponse = runWithCancelSupport(terminal, ui, () -> {
                            SubAgent subAgent = createSingleAgent(profile, activeClient, reactAgent,
                                    mcpServerManager, skillRegistry);
                            subAgent.setSessionContext(sessionContext);
                            return runModeWithRuntime(
                                    AgentMode.TEAM,
                                    agentTask,
                                    projectPath,
                                    agentRunStore,
                                    agentSnapshotService,
                                    new SingleAgentAdapter(subAgent, ui),
                                    sessionContext);
                        });
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        if (agentResponse != null && !agentResponse.isBlank()) {
                            ui.println(agentResponse);
                            ui.println();
                        }
                        continue;
                    }
                    case NONE -> {
                    }
                }

                // 运行 Agent
                String submittedInput = input;
                //@提及扩展器：解析输入里 @xxx 这类提及标记，把提及对象（知识库资源、文件、MCP 服务、工具）注入上下文、补全描述、替换占位符。
                input = mentionExpander.expand(input);
                //本地文件路径扩展器：识别输入里本地文件 / 目录路径，自动读取文件内容、把文件摘要 / 全文塞进 prompt，实现一问文件自动加载内容。
                input = localPathMentionExpander.expand(input);
                if (!(renderer instanceof InlineRenderer)) {
                    ui.println();
                }
                if (!submittedInputRendered) {
                    renderer.beginTurn();
                    printSubmittedInput(renderer, ui, submittedInput);
                }
                final String taskInput = input;
                Callable<String> runTask;
                String snapshotMode;
                if (nextTaskUsePlanMode || command.type() == CliCommandParser.CommandType.SWITCH_PLAN) {
                    snapshotMode = "plan";
                    LlmClient activeClient = llmClient;
                    runTask = () -> {
                        PlanExecuteAgent planAgent = createPlanAgent(activeClient, reactAgent, terminal, lineReader, ui);
                        planAgent.setSessionContext(sessionContext);
                        planAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
                        planAgent.setSkillRegistry(skillRegistry);
                        return runModeWithRuntime(
                                AgentMode.PLAN,
                                taskInput,
                                reactAgent.getToolRegistry().getProjectPath(),
                                reactAgent.runStore(),
                                reactAgent.getToolRegistry().getSnapshotService(),
                                new PlanModeAdapter(planAgent),
                                sessionContext);
                    };
                } else if (nextTaskUseTeamMode || command.type() == CliCommandParser.CommandType.SWITCH_TEAM) {
                    snapshotMode = "team";
                    LlmClient activeClient = llmClient;
                    runTask = () -> {
                        AgentOrchestrator orchestrator = createTeamAgent(activeClient, reactAgent, ui);
                        orchestrator.setSessionContext(sessionContext);
                        orchestrator.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
                        orchestrator.setSkillSystem(skillRegistry);
                        return runModeWithRuntime(
                                AgentMode.TEAM,
                                taskInput,
                                reactAgent.getToolRegistry().getProjectPath(),
                                reactAgent.runStore(),
                                reactAgent.getToolRegistry().getSnapshotService(),
                                new TeamModeAdapter(orchestrator),
                                sessionContext);
                    };
                } else {
                    snapshotMode = "react";
                    runTask = () -> runReactModeWithRuntime(
                            taskInput,
                            reactAgent.getToolRegistry().getProjectPath(),
                            reactAgent.runStore(),
                            reactAgent.getToolRegistry().getSnapshotService(),
                            new ReActModeAdapter(reactAgent),
                            sessionContext);
                }
                SnapshotService snapshotService = reactAgent.getToolRegistry().getSnapshotService();
                renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, snapshotMode));
                String response = runWithCancelSupport(terminal,
                        ui,
                        () -> runAgentTask(snapshotMode, taskInput, snapshotService, runTask));
                if (!"react".equals(snapshotMode)) {
                    renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                }
                nextTaskUsePlanMode = false;
                nextTaskUseTeamMode = false;
                if (response != null && !response.isBlank()) {
                    ui.println(response);
                    ui.println();
                }
            }
            ui.println("\n👋 再见!");
            wechatRuntime.stop();
            renderer.close();

        } catch (IOException e) {
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            System.exit(1);
        }
    }

    static String handleWechatCommand(String payload,
                                      LineReader lineReader,
                                      Renderer renderer,
                                      PrintStream out,
                                      WechatCliCommandHandler.WechatRuntimeController runtime) {
        return WechatCliCommandHandler.handleWechatCommand(payload, lineReader, renderer, out, runtime);
    }

    static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                            PlanExecuteAgent.PlanReviewHandler reviewHandler) {
        return CliModeFactory.createPlanAgent(llmClient, reactAgent, reviewHandler, System.out);
    }

    private static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                                    Terminal terminal, LineReader lineReader, PrintStream out) {
        out.println("📋 使用 Plan-and-Execute 模式\n");
        return CliModeFactory.createPlanAgent(llmClient, reactAgent,
                createPlanReviewHandler(terminal, lineReader, out), out);
    }

    private static AgentOrchestrator createTeamAgent(LlmClient llmClient, Agent reactAgent, PrintStream out) {
        out.println("👥 使用 Multi-Agent 协作模式\n");
        return CliModeFactory.createTeamAgent(llmClient, reactAgent, out);
    }

    private static SubAgent createSingleAgent(AgentProfile profile, LlmClient llmClient, Agent reactAgent,
                                              McpServerManager mcpServerManager,
                                              SkillRegistry skillRegistry) {
        return CliModeFactory.createSingleAgent(profile, llmClient, reactAgent,
                mcpServerManager, skillRegistry);
    }

    static String runModeWithRuntime(AgentMode mode, String input, String workspace,
                                     RunStore runStore, SnapshotService snapshotService,
                                     ModeAdapter adapter) {
        return runModeWithRuntime(mode, input, workspace, runStore, snapshotService, adapter, null);
    }

    private static String resumeRun(String runId, Agent reactAgent, LlmClient llmClient,
                                    Terminal terminal, LineReader lineReader, PrintStream out,
                                    McpServerManager mcpServerManager, SkillRegistry skillRegistry,
                                    SessionContext sessionContext) {
        return CliRunResumer.resume(runId, reactAgent, llmClient, terminal, lineReader, out,
                mcpServerManager, skillRegistry, sessionContext);
    }

    static String runModeWithRuntime(AgentMode mode, String input, String workspace,
                                     RunStore runStore, SnapshotService snapshotService,
                                     ModeAdapter adapter, SessionContext sessionContext) {
        return RUNTIME_COORDINATOR.run(mode, input, workspace, runStore, snapshotService, adapter, sessionContext);
    }

    static String runReactModeWithRuntime(String input, String workspace,
                                          RunStore runStore, SnapshotService snapshotService,
                                          ModeAdapter adapter) {
        return RUNTIME_COORDINATOR.runReact(input, workspace, runStore, snapshotService, adapter, null);
    }

    static String runReactModeWithRuntime(String input, String workspace,
                                          RunStore runStore, SnapshotService snapshotService,
                                          ModeAdapter adapter, SessionContext sessionContext) {
        return RUNTIME_COORDINATOR.runReact(input, workspace, runStore, snapshotService, adapter, sessionContext);
    }

    static String runAgentTask(String mode, String input, SnapshotService snapshotService,
                               Callable<String> task) throws Exception {
        return RUNTIME_COORDINATOR.runTask(task);
    }

    private static String runtimeUserFacingContent(AgentRunResult result) {
        return CliRuntimeCoordinator.userFacingContent(result);
    }

    private static String runWithCancelSupport(Terminal terminal, PrintStream out, Callable<String> task) {
        CancellationToken token = CancellationContext.startRun();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mindcli-agent-runner");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> future = executor.submit(task);
        // 进入 raw mode 监听 ESC：raw mode 关 ICANON / ECHO / IEXTEN 但保留 ISIG，所以 Ctrl+C 仍能终止 MindCLI。
        Attributes original = null;
        try {
            if (terminal != null) {
                try {
                    original = terminal.enterRawMode();
                } catch (Exception ignored) {
                    // raw mode 进入失败（非交互终端等），降级为不监听 ESC，靠 Ctrl+C 退出。
                }
            }
            while (!future.isDone()) {
                if (original != null && readEscCancel(terminal)) {
                    token.cancel();
                    future.cancel(true);
                    executor.shutdownNow();
                    return "⏹️ 已请求取消当前任务。";
                }
                try {
                    return future.get(150, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // 继续监听 ESC
                }
            }
            return future.get();
        } catch (CancellationException e) {
            return "⏹️ 已取消当前任务。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            token.cancel();
            future.cancel(true);
            return "⏹️ 已取消当前任务。";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage();
            return "❌ 执行失败: " + message;
        } finally {
            if (terminal != null && original != null) {
                try {
                    terminal.setAttributes(original);
                } catch (Exception ignored) {
                }
            }
            CancellationContext.clear(token);
            executor.shutdownNow();
        }
    }

    /**
     * 任务运行期间监听 ESC 按键。raw mode 下 ESC 字节是 0x1b（27）。
     *
     * 关键陷阱：方向键 / Home / End 等由 ESC + 控制序列组成（如 ESC[A），不能误判为单 ESC 取消。
     * 复用 {@link #readInputBurst} + {@link #classifyEscapeSequence}：
     * - STANDALONE_ESC（孤立的 ESC）→ 用户取消
     * - CONTROL_SEQUENCE / BRACKETED_PASTE / OTHER → 丢弃，不取消
     */
    static boolean readEscCancel(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        try {
            NonBlockingReader reader = terminal.reader();
            int next = reader.read(50);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                return false;
            }
            String escTail = next == 27 ? readInputBurst(terminal, 80, 20, 120) : null;
            if (next != 27) {
                // 非 ESC 输入，drain 这一轮残余字节避免堆积，但不触发取消。
                while (true) {
                    int more = reader.read(1);
                    if (more == NonBlockingReader.READ_EXPIRED || more < 0) {
                        break;
                    }
                }
            }
            return decideEscCancel(next, escTail);
        } catch (Exception ignored) {
            // 监听是 best-effort；失败不能影响任务执行。
            return false;
        }
    }

    /**
     * ESC 取消判断的纯函数版（不依赖终端 IO，便于单测）。
     *
     * @param firstByte ESC=27 触发判断；其他字节直接返回 false
     * @param escTail  紧跟 ESC 之后的字节序列（不含 ESC 本身）；null / 空 → 单 ESC 取消
     */
    static boolean decideEscCancel(int firstByte, String escTail) {
        if (firstByte != 27) {
            return false;
        }
        return classifyEscapeSequence(escTail) == EscapeSequenceType.STANDALONE_ESC;
    }

    private static PromptInput readPromptInput(Terminal terminal,
                                               LineReader lineReader,
                                               Renderer renderer,
                                               boolean allowEscCancel,
                                               boolean spaciousPrompt)
            throws UserInterruptException, EndOfFileException {
        if (spaciousPrompt) {
            renderer.stream().println();
        }
        renderer.beforeInput();
        try {
            String prompt = renderer.inputPrompt();
            String rightPrompt = renderer.inputRightPrompt();
            if (!allowEscCancel) {
                return PromptInput.submitted(lineReader.readLine(prompt, rightPrompt, (MaskingCallback) null, null));
            }

            if (terminal != null && terminal.writer() != null) {
                terminal.writer().print(prompt);
                terminal.writer().flush();
            } else {
                renderer.stream().print(prompt);
                renderer.stream().flush();
            }

            PrefillResult prefill = readPrefillInputFromTerminal(terminal, lineReader);
            if (prefill == null) {
                return PromptInput.submitted(lineReader.readLine("", rightPrompt, (MaskingCallback) null, null));
            }

            if (prefill.canceled()) {
                return PromptInput.canceledInput();
            }

            if (prefill.submitted()) {
                return PromptInput.submitted("");
            }

            return PromptInput.submitted(lineReader.readLine("", rightPrompt, (MaskingCallback) null, prefill.seedBuffer()));
        } finally {
            renderer.afterInput();
        }
    }

    static boolean defaultSpaciousPrompt(boolean statusBarAvailable) {
        return false;
    }

    static void printSubmittedPrompt(PrintStream out, String input) {
        String visible = input == null ? "" : input.strip();
        if (visible.isEmpty()) {
            return;
        }
        out.println(AnsiStyle.userMessageBlock(visible, terminalColumns()));
    }

    static void printSubmittedInput(Renderer renderer, PrintStream out, String input) {
        String visible = redactSensitiveInput(input);
        if (renderer instanceof InlineRenderer inline) {
            inline.printSubmittedPrompt(visible);
        } else {
            printSubmittedPrompt(out, visible);
        }
    }

    static String redactSensitiveInput(String input) {
        return CliInputSupport.redactSensitiveInput(input);
    }

    private static int terminalColumns() {
        String configured = System.getProperty("mindcli.render.columns");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(40, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        String columns = System.getenv("COLUMNS");
        if (columns != null && !columns.isBlank()) {
            try {
                return Math.max(40, Integer.parseInt(columns.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 120;
    }

    private static void refreshTerminalColumns(Terminal terminal) {
        if (terminal == null || terminal.getSize() == null || terminal.getSize().getColumns() <= 0) {
            return;
        }
        System.setProperty("mindcli.render.columns", String.valueOf(Math.max(40, terminal.getSize().getColumns())));
    }

    static void configureAwtForCli() {
        CliBootstrap.configureAwtForCli();
    }

    static boolean isMacOs() {
        return CliBootstrap.isMacOs();
    }

    private static PlanExecuteAgent.PlanReviewHandler createPlanReviewHandler(Terminal terminal,
                                                                               LineReader lineReader,
                                                                               PrintStream out) {
        return (String goal, ExecutionPlan plan) -> {
            boolean expanded = false;
            out.println(plan.summarize());
            out.println("📝 计划已生成。");
            out.println("   - 回车：按当前计划执行");
            out.println("   - Ctrl+O：展开完整计划");
            out.println("   - ESC：折叠或取消本次计划");
            out.println("   - I：输入补充要求后重新规划\n");

            while (true) {
                KeyReadResult keyReadResult = readSingleKeyFromTerminal(terminal);
                if (keyReadResult.ignoredControlSequence()) {
                    continue;
                }

                Integer key = keyReadResult.key();
                if (key != null) {
                    // Enter
                    if (key == '\n' || key == '\r') {
                        out.println();
                        return PlanExecuteAgent.PlanReviewDecision.execute();
                    }

                    // ESC (27)
                    if (key == 27) {
                        out.println();
                        if (expanded) {
                            expanded = false;
                            out.println(plan.summarize());
                            out.println("📁 已退出完整计划视图，继续按 Enter / Ctrl+O / ESC / I。\n");
                            continue;
                        }
                        return PlanExecuteAgent.PlanReviewDecision.cancel();
                    }

                    // I 或 i
                    if (key == 'i' || key == 'I') {
                        out.println();
                        String supplementInput = lineReader.readLine("补充> ").trim();
                        PlanReviewInputParser.Decision supplementDecision =
                                PlanReviewInputParser.parse(supplementInput);
                        return mapReviewDecision(supplementDecision);
                    }

                    // Ctrl+O
                    if (key == CTRL_O) {
                        out.println();
                        out.println(plan.visualize());
                        expanded = true;
                        out.println("👆 已展开完整计划，继续按 Enter / Ctrl+O / ESC / I。\n");
                        continue;
                    }

                    out.println();
                    out.println("未识别按键，请按 Enter / Ctrl+O / ESC / I。\n");
                    continue;
                }

                // 如果无法读取单键，回退到行输入模式
                String decisionInput = lineReader.readLine("操作/补充> ").trim();
                if (decisionInput.equalsIgnoreCase("/view")) {
                    out.println();
                    out.println(plan.visualize());
                    expanded = true;
                    out.println("👆 已展开完整计划，继续输入 Enter / /cancel / 补充要求。\n");
                    continue;
                }
                PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse(decisionInput);
                return mapReviewDecision(decision);
            }
        };
    }

    /** Compatibility seam for runtime helpers that need the interactive plan reviewer. */
    public static PlanExecuteAgent.PlanReviewHandler createPlanReviewHandlerForRuntime(
            Terminal terminal, LineReader lineReader, PrintStream out) {
        return createPlanReviewHandler(terminal, lineReader, out);
    }

    private static KeyReadResult readSingleKeyFromTerminal(Terminal terminal) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return KeyReadResult.unavailable();
                }

                if (key == 27) {
                    String escapeSequence = readInputBurst(terminal, 80, 20, 120);
                    EscapeSequenceType escapeSequenceType = classifyEscapeSequence(escapeSequence);
                    if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
                        return KeyReadResult.keyPressed(27);
                    }
                    if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE
                            || escapeSequenceType == EscapeSequenceType.BRACKETED_PASTE) {
                        return KeyReadResult.ignoredSequence();
                    }
                }

                return KeyReadResult.keyPressed(key);
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return KeyReadResult.unavailable();
        }
    }

    private static PrefillResult readPrefillInputFromTerminal(Terminal terminal, LineReader lineReader) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return null;
                }

                if (key == 27) {
                    return readEscapeInput(terminal, lineReader);
                }

                if (isSubmitKey(key)) {
                    return PrefillResult.submittedInput();
                }

                String rawInput = switch (key) {
                    case 8, 127 -> "";
                    default -> Character.toString((char) key);
                };

                rawInput += readInputBurst(terminal, 20, 25, 250);
                return PrefillResult.seed(prepareSeedBuffer(rawInput));
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static PrefillResult readEscapeInput(Terminal terminal, LineReader lineReader)
            throws IOException, InterruptedException {
        String sequence = readInputBurst(terminal, 80, 20, 300);
        EscapeSequenceType escapeSequenceType = classifyEscapeSequence(sequence);
        if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
            return PrefillResult.canceledInput();
        }

        if (escapeSequenceType == EscapeSequenceType.BRACKETED_PASTE) {
            String pastedText = sequence.substring(BRACKETED_PASTE_BEGIN.length());
            while (!pastedText.contains(BRACKETED_PASTE_END)) {
                String burst = readInputBurst(terminal, 30, 25, 500);
                if (burst.isEmpty()) {
                    break;
                }
                pastedText += burst;
            }

            return PrefillResult.seed(prepareSeedBuffer(stripBracketedPasteEndMarker(pastedText)));
        }

        if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE) {
            return PrefillResult.seed(seedBufferForHistoryNavigation(lineReader, sequence));
        }

        return PrefillResult.canceledInput();
    }

    private static String readInputBurst(Terminal terminal, long firstWaitMs, long idleWaitMs, long maxWaitMs)
            throws IOException, InterruptedException {
        NonBlockingReader reader = terminal.reader();
        StringBuilder buffer = new StringBuilder();
        long start = System.currentTimeMillis();
        long waitMs = firstWaitMs;

        while (System.currentTimeMillis() - start < maxWaitMs) {
            int next = reader.read(waitMs);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                break;
            }
            buffer.append((char) next);
            waitMs = idleWaitMs;
        }

        return buffer.toString();
    }

    static String prepareSeedBuffer(String rawInput) {
        return CliInputSupport.prepareSeedBuffer(rawInput);
    }

    static List<String> startupHints() {
        return SlashCommandCatalog.startupHints();
    }

    record SlashCommandHint(String insertText, String display, String description) {
    }

    static List<SlashCommandHint> slashCommandHints() {
        return SlashCommandCatalog.slashCommandHints().stream()
                .map(hint -> new SlashCommandHint(hint.insertText(), hint.display(), hint.description()))
                .toList();
    }

    private static void printSlashCommandHelp() {
        printSlashCommandHelp(System.out);
    }

    private static void printSlashCommandHelp(PrintStream out) {
        out.println("可用命令：");
        for (SlashCommandHint hint : slashCommandHints()) {
            out.println("   " + hint.display() + " - " + hint.description());
        }
        out.println();
    }

    static void configureSlashCommandHint(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("mindcli-slash-command-hint", () -> {
            lineReader.getBuffer().write("/");
            return true;
        });
        Reference slashHint = new Reference("mindcli-slash-command-hint");
        bindSlashWidget(lineReader, LineReader.MAIN, slashHint);
        bindSlashWidget(lineReader, LineReader.EMACS, slashHint);
        bindSlashWidget(lineReader, LineReader.VIINS, slashHint);
    }

    static void configureJLineInteractiveWidgets(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        new AutosuggestionWidgets(lineReader).enable();
        new AutopairWidgets(lineReader).enable();
        // JLine TailTipWidgets 会通过 Status 预留多行底部区域；如果在首屏前 enable，
        // banner 前会出现大段空白，输入行下方也会长期空出一块。命令说明后续用
        // 不预留布局的方式展示，避免破坏 Claude Code / Qoder 风格的 inline 体验。
    }

    static LinkedHashMap<String, CmdDesc> slashCommandTailTips() {
        return SlashCommandCatalog.slashCommandTailTips();
    }

    private static void bindSlashWidget(LineReader lineReader, String keyMapName, Reference slashHint) {
        KeyMap<org.jline.reader.Binding> keyMap = lineReader.getKeyMaps().get(keyMapName);
        if (keyMap != null) {
            keyMap.bind(slashHint, "/");
        }
    }

    static String formatSlashCommandChoices(int terminalWidth) {
        return SlashCommandCatalog.formatSlashCommandChoices(terminalWidth);
    }

    static String handleConfigCommand(MindCliConfig config, String payload) {
        return ConfigCommandHandler.handleConfigCommand(config, payload);
    }

    static ProviderConfigUpdate parseProviderConfigUpdate(String payload) {
        return ConfigCommandHandler.parseProviderConfigUpdate(payload);
    }

    static void bindCtrlOToFoldableBlocks(LineReader lineReader, InlineRenderer inline) {
        if (lineReader == null || inline == null) {
            return;
        }
        lineReader.getWidgets().put("mindcli-toggle-foldable", () -> {
            inline.toggleLastBlock();
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference ref = new Reference("mindcli-toggle-foldable");
        String ctrlO = String.valueOf((char) 15);  // Ctrl+O
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(ref, ctrlO);
            }
        }
    }

    // Ctrl+V 抓系统剪贴板里的图片到 ~/.mindcli/cache/ 并把 @image:<path> 注入当前输入行。
    // 失败（无图 / headless / IO 错误）时只打提示，不破坏现有 buffer，覆盖掉 JLine 默认的
    // quoted-insert 没有交互价值。注意 macOS Cmd+V 通常被终端劫持成本地粘贴文本，所以这里
    // 绑的是 Ctrl+V（ASCII 22 / SYN），iTerm / Terminal.app 默认不会拦截。
    //
    // 输入层不按模型名拦截图片：与 Claude Code 类似，先把图片读成附件收进
    // prompt；模型是否接受 image block 由 provider API 自己处理。
    static void bindCtrlVToClipboardImage(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("mindcli-paste-clipboard-image", () -> {
            ClipboardImage.GrabResult grab = ClipboardImage.grab();
            if (!grab.ok()) {
                lineReader.printAbove("⚠️ Ctrl+V 抓图失败: " + grab.error());
                lineReader.callWidget(LineReader.REDISPLAY);
                return true;
            }
            String token = "@image:<" + grab.path().toAbsolutePath() + "> ";
            lineReader.getBuffer().write(token);
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference ref = new Reference("mindcli-paste-clipboard-image");
        String ctrlV = String.valueOf((char) 22);  // Ctrl+V (SYN)
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(ref, ctrlV);
            }
        }
    }

    static void bindEscToClearInput(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("mindcli-clear-input", () -> {
            clearInputBuffer(lineReader);
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference clearInput = new Reference("mindcli-clear-input");
        String esc = KeyMap.esc();
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(clearInput, esc);
            }
        }
    }

    static void clearInputBuffer(LineReader lineReader) {
        if (lineReader == null || lineReader.getBuffer() == null) {
            return;
        }
        lineReader.getBuffer().clear();
    }

    static boolean hasExportableMessages(List<LlmClient.Message> history) {
        return ExportCommandHandler.hasExportableMessages(history);
    }

    static long countExportedMessages(List<LlmClient.Message> history) {
        return ExportCommandHandler.countExportedMessages(history);
    }

    static String renderConversationExport(List<LlmClient.Message> history, LocalDateTime exportedAt) {
        return ExportCommandHandler.renderConversationExport(history, exportedAt);
    }

    static String markdownFenceFor(String content) {
        return ExportCommandHandler.markdownFenceFor(content);
    }

    static String handleBrowserCommand(String payload,
                                       BrowserSession browserSession,
                                       McpServerManager mcpServerManager,
                                       HitlToolRegistry registry,
                                       HitlHandler hitlHandler) {
        return BrowserCommandHandler.handle(
                payload,
                browserSession,
                mcpServerManager,
                registry,
                hitlHandler);
    }

    private static void printStartupHints(PrintStream out) {
        CliStartupView.printStartupHints(out, startupHints());
    }

    private static CliStartupView.StartupScreenInfo startupScreenInfo(LlmClient llmClient,
                                                                      McpServerManager mcpServerManager,
                                                                      SkillRegistry skillRegistry,
                                                                      String note) {
        return CliStartupView.startupScreenInfo(llmClient, mcpServerManager, skillRegistry, note);
    }

    private static StatusInfo statusInfo(LlmClient llmClient,
                                         SwitchableHitlHandler hitlHandler,
                                         String phase,
                                         McpServerManager mcpServerManager,
                                         SkillRegistry skillRegistry) {
        return CliStartupView.statusInfo(llmClient, hitlHandler, phase, mcpServerManager, skillRegistry);
    }

    private static StatusInfo statusInfo(Agent reactAgent,
                                         McpServerManager mcpServerManager,
                                         SkillRegistry skillRegistry,
                                         String phase) {
        return CliStartupView.statusInfo(reactAgent, mcpServerManager, skillRegistry, phase);
    }

    private static String mcpStatusSummary(McpServerManager mcpServerManager) {
        return CliStartupView.mcpStatusSummary(mcpServerManager);
    }

    private static String skillStatusSummary(SkillRegistry skillRegistry) {
        return CliStartupView.skillStatusSummary(skillRegistry);
    }

    private static String appendStartupNote(String current, String next) {
        return CliBootstrap.appendStartupNote(current, next);
    }

    static Duration mcpStartupWait() {
        return CliBootstrap.mcpStartupWait();
    }

    static String normalizeLineEndings(String rawInput) {
        return CliInputSupport.normalizeLineEndings(rawInput);
    }

    private static String stripBracketedPasteEndMarker(String rawInput) {
        return CliInputSupport.stripBracketedPasteEndMarker(rawInput);
    }

    private static boolean isSubmitKey(int key) {
        return key == '\n' || key == '\r';
    }

    static EscapeSequenceType classifyEscapeSequence(String sequence) {
        return switch (CliInputSupport.classifyEscapeSequence(sequence)) {
            case STANDALONE_ESC -> EscapeSequenceType.STANDALONE_ESC;
            case BRACKETED_PASTE -> EscapeSequenceType.BRACKETED_PASTE;
            case CONTROL_SEQUENCE -> EscapeSequenceType.CONTROL_SEQUENCE;
            case OTHER -> EscapeSequenceType.OTHER;
        };
    }

    static String seedBufferForHistoryNavigation(LineReader lineReader, String sequence) {
        return CliInputSupport.seedBufferForHistoryNavigation(lineReader, sequence);
    }

    static void configureHistory(LineReader lineReader, Path homeDir) {
        CliInputSupport.configureHistory(lineReader, homeDir);
    }

    static Path resolveHistoryFile(Path homeDir) {
        return CliInputSupport.resolveHistoryFile(homeDir);
    }

    static Path normalizeHistoryFile(Path configured) {
        return CliInputSupport.normalizeHistoryFile(configured);
    }

    static void clearLineReaderHistory(LineReader lineReader) {
        CliInputSupport.clearLineReaderHistory(lineReader);
    }

    private static PlanExecuteAgent.PlanReviewDecision mapReviewDecision(PlanReviewInputParser.Decision decision) {
        return switch (decision.type()) {
            case EXECUTE -> PlanExecuteAgent.PlanReviewDecision.execute();
            case CANCEL -> PlanExecuteAgent.PlanReviewDecision.cancel();
            case SUPPLEMENT -> PlanExecuteAgent.PlanReviewDecision.supplement(decision.feedback());
        };
    }

    /**
     * 从 .env 文件加载 API Key
     */
    private static String loadApiKey() {
        return CliBootstrap.loadConfigValue("GLM_API_KEY", null);
    }

    private static void configureLogging() {
        CliBootstrap.configureLogging();
    }

    static ModelSelection resolveModelSelection(String raw) {
        String value = raw == null ? "" : raw.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "glm" -> new ModelSelection("glm", "glm-5.1", true);
            case "deepseek" -> new ModelSelection("deepseek", null, false);
            case "step", "stepfun", "step-fun" -> new ModelSelection("step", null, false);
            case "kimi", "moonshot", "moonshotai", "moonshot-ai" -> new ModelSelection("kimi", null, false);
            case "freellmapi", "free-llm-api", "free_llm_api", "freellm", "free-llm" ->
                    new ModelSelection("freellmapi", null, false);
            case "xfyun", "xfyun-maas", "xfyun_maas", "iflytek", "iflytek-maas", "iflytek_maas", "maas" ->
                    new ModelSelection("xfyun", null, false);
            default -> {
                if (normalized.startsWith("glm-")) {
                    yield new ModelSelection("glm", value, true);
                }
                if (normalized.startsWith("deepseek")) {
                    yield new ModelSelection("deepseek", value, true);
                }
                if (normalized.startsWith("step")) {
                    yield new ModelSelection("step", value, true);
                }
                if (normalized.startsWith("kimi-") || normalized.startsWith("moonshot-")) {
                    yield new ModelSelection("kimi", value, true);
                }
                yield new ModelSelection(normalized, null, false);
            }
        };
    }

    private static MindCliConfig.ProviderConfig ensureProviderConfig(MindCliConfig config, String provider) {
        if (config.getProviders() == null) {
            config.setProviders(new LinkedHashMap<>());
        }
        return config.getProviders().computeIfAbsent(provider, ignored -> new MindCliConfig.ProviderConfig());
    }

    private static void printStartupScreen(PrintStream out, CliStartupView.StartupScreenInfo info) {
        CliStartupView.printStartupScreen(out, VERSION, info);
    }

    private static void printStartupScreen(PrintStream out, List<String> lines) {
        for (String line : lines) {
            out.println(line);
        }
    }

    static List<String> startupScreenLines(CliStartupView.StartupScreenInfo info) {
        return CliStartupView.startupScreenLines(VERSION, info);
    }

    static List<String> startupBannerLines() {
        return CliStartupView.startupBannerLines(VERSION);
    }

    static List<String> startupBannerLines(CliStartupView.StartupScreenInfo info) {
        return CliStartupView.startupBannerLines(VERSION, info);
    }

    static McpConfigBootstrapResult ensureDefaultMcpConfig(Path userHome) throws IOException {
        return CliBootstrap.ensureDefaultMcpConfig(userHome);
    }

    record McpConfigBootstrapResult(boolean created, String message) {
    }

    record ModelSelection(String provider, String model, boolean explicitModel) {
    }

    public record ProviderConfigUpdate(String provider, String apiKey, String baseUrl, String model, String loraId,
                                       boolean setDefault, String error) {
        public static ProviderConfigUpdate error(String error) {
            return new ProviderConfigUpdate(null, null, null, null, null, false, error);
        }
    }
}
