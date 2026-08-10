package com.mindcli.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainCliBootstrapRefactorTest {

    @Test
    void cliBootstrapMatchesMainMcpConfigFacade(@TempDir Path tempDir) throws Exception {
        Path mainHome = tempDir.resolve("main");
        Path bootstrapHome = tempDir.resolve("bootstrap");

        Main.McpConfigBootstrapResult mainResult = Main.ensureDefaultMcpConfig(mainHome);
        Main.McpConfigBootstrapResult bootstrapResult = CliBootstrap.ensureDefaultMcpConfig(bootstrapHome);

        assertEquals(mainResult.created(), bootstrapResult.created());
        assertEquals(Files.readString(mainHome.resolve(".mindcli").resolve("mcp.json")),
                Files.readString(bootstrapHome.resolve(".mindcli").resolve("mcp.json")));
        assertTrue(bootstrapResult.message().contains("chrome-devtools"), bootstrapResult.message());
    }

    @Test
    void cliBootstrapMatchesMainMcpStartupWaitFacade() {
        String property = "mindcli.mcp.startup.wait.seconds";
        String original = System.getProperty(property);
        try {
            System.setProperty(property, "3");
            assertEquals(Main.mcpStartupWait(), CliBootstrap.mcpStartupWait());
            assertEquals(Duration.ofSeconds(3), CliBootstrap.mcpStartupWait());

            System.setProperty(property, "bad");
            assertEquals(Duration.ofSeconds(8), CliBootstrap.mcpStartupWait());
        } finally {
            if (original == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, original);
            }
        }
    }

    @Test
    void cliBootstrapComposesStartupNotes() {
        assertEquals("", CliBootstrap.appendStartupNote("", ""));
        assertEquals("MCP 初始化失败", CliBootstrap.appendStartupNote("", "MCP 初始化失败"));
        assertEquals("A\nB", CliBootstrap.appendStartupNote("A", "B"));
    }

    @Test
    void cliBootstrapKeepsPlatformFacade() {
        assertEquals(Main.isMacOs(), CliBootstrap.isMacOs());
    }
}
