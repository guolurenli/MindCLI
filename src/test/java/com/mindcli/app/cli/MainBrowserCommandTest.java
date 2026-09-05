package com.mindcli.app.cli;

import com.mindcli.capability.browser.BrowserMode;
import com.mindcli.capability.browser.BrowserSession;
import com.mindcli.app.cli.command.BrowserCommandHandler;
import com.mindcli.platform.hitl.HitlToolRegistry;
import com.mindcli.platform.hitl.TerminalHitlHandler;
import com.mindcli.capability.mcp.McpServer;
import com.mindcli.capability.mcp.McpServerManager;
import com.mindcli.capability.mcp.McpServerStatus;
import com.mindcli.capability.mcp.config.McpConfigLoader;
import com.mindcli.capability.mcp.config.McpServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MainBrowserCommandTest {

    @Test
    void extractedBrowserCommandHandlerMatchesMainFacade(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String direct = BrowserCommandHandler.handle("status", h.session, h.manager, h.registry, h.handler);
        String facade = Main.handleBrowserCommand("status", h.session, h.manager, h.registry, h.handler);

        assertEquals(facade, direct);
    }

    @Test
    void browserStatusShowsCurrentMode(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = Main.handleBrowserCommand("status", h.session, h.manager, h.registry, h.handler);

        assertTrue(result.contains("当前模式"));
        assertTrue(result.contains("isolated"));
    }

    @Test
    void browserConnectRejectsPortArgument(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = Main.handleBrowserCommand("connect 80", h.session, h.manager, h.registry, h.handler);

        assertTrue(result.contains("不再接受端口参数"));
        assertEquals(BrowserMode.ISOLATED, h.session.mode());
    }

    @Test
    void browserConnectDefaultUsesAutoConnectWithoutLegacyProbe(@TempDir Path tempDir) {
        BrowserSession session = new BrowserSession();
        HitlToolRegistry registry = new HitlToolRegistry(new TerminalHitlHandler(false));
        FakeMcpServerManager manager = new FakeMcpServerManager(registry, tempDir);

        String result = Main.handleBrowserCommand("connect", session, manager, registry, new TerminalHitlHandler(false));

        assertTrue(result.contains("--autoConnect"));
        assertEquals(BrowserMode.SHARED, session.mode());
        assertEquals("autoConnect", session.browserUrl());
        assertEquals(List.of("-y", "chrome-devtools-mcp@latest", "--autoConnect"), manager.lastArgs);
    }

    @Test
    void browserDisconnectWithoutServerClearsSession(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);
        h.session.switchToShared("http://127.0.0.1:9222");

        String result = Main.handleBrowserCommand("disconnect", h.session, h.manager, h.registry, h.handler);

        assertTrue(result.contains("未配置"));
        assertEquals(BrowserMode.ISOLATED, h.session.mode());
    }

    @Test
    void browserTabsInIsolatedModeGivesConnectHint(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = Main.handleBrowserCommand("tabs", h.session, h.manager, h.registry, h.handler);

        assertTrue(result.contains("isolated"));
        assertTrue(result.contains("/browser connect"));
    }

    @Test
    void unknownBrowserSubCommandShowsHelp(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = Main.handleBrowserCommand("wat", h.session, h.manager, h.registry, h.handler);

        assertTrue(result.contains("未知 /browser 子命令"));
        assertTrue(result.contains("/browser connect"));
    }

    private static final class Harness {
        private final BrowserSession session = new BrowserSession();
        private final TerminalHitlHandler handler = new TerminalHitlHandler(false);
        private final HitlToolRegistry registry = new HitlToolRegistry(handler);
        private final McpServerManager manager;

        private Harness(Path tempDir) throws IOException {
            manager = new McpServerManager(
                    registry,
                    tempDir,
                    new McpConfigLoader(tempDir.resolve("user.json"), tempDir.resolve("project.json"), tempDir));
            manager.loadConfiguredServers();
        }
    }

    private static final class FakeMcpServerManager extends McpServerManager {
        private final McpServer server;
        private List<String> lastArgs = List.of();

        private FakeMcpServerManager(HitlToolRegistry registry, Path projectDir) {
            super(registry, projectDir);
            McpServerConfig config = new McpServerConfig();
            config.setCommand("npx");
            config.setArgs(List.of("-y", "chrome-devtools-mcp@latest", "--isolated=true"));
            this.server = new McpServer("chrome-devtools", config);
            this.server.status(McpServerStatus.READY);
        }

        @Override
        public synchronized String restartWithArgs(String name, List<String> args) {
            lastArgs = List.copyOf(args);
            server.config().setArgs(args);
            server.status(McpServerStatus.READY);
            return "✅ MCP server 已重启: " + name;
        }

        @Override
        public McpServer server(String name) {
            return "chrome-devtools".equals(name) ? server : null;
        }
    }
}
