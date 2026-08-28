package com.mindcli.app.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainCliStartupViewRefactorTest {

    @org.junit.jupiter.api.AfterEach
    void restoreColorProperties() {
        System.clearProperty("mindcli.render.color");
        System.clearProperty("mindcli.render.truecolor");
    }

    @Test
    void cliStartupViewKeepsMainBannerFacade() {
        List<String> mainLines = Main.startupBannerLines();
        List<String> viewLines = CliStartupView.startupBannerLines("16.1.0");

        assertEquals(mainLines, viewLines);
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("MindCLI // v16.1.0")));
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("Command /")));
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("Context @path")));
        assertTrue(viewLines.stream().noneMatch(line -> line.endsWith("║")),
                "banner should not depend on a padded right border");
    }

    @Test
    void cliStartupViewKeepsStartupScreenFacade() {
        CliStartupView.StartupScreenInfo info = new CliStartupView.StartupScreenInfo(
                "glm-5.1",
                "glm",
                1,
                2,
                3,
                1,
                2,
                "java",
                "MCP ready"
        );

        List<String> mainLines = Main.startupScreenLines(info);
        List<String> viewLines = CliStartupView.startupScreenLines("16.1.0", info);

        assertEquals(mainLines, viewLines);
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("Model") && line.contains("glm-5.1 / glm")));
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("Mcp") && line.contains("1/2 online")));
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("Skills") && line.contains("1/2 armed")));
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("MCP ready")));
    }

    @Test
    void startupBannerUsesSofterTitleCaseLabels() {
        String banner = String.join("\n", CliStartupView.startupBannerLines("16.1.0",
                new CliStartupView.StartupScreenInfo(
                        "deepseek-v4-flash",
                        "deepseek",
                        2,
                        3,
                        43,
                        1,
                        1,
                        "web-access",
                        "")));

        assertTrue(banner.contains("MindCLI // v16.1.0"), banner);
        assertTrue(banner.contains("Model   deepseek-v4-flash / deepseek"), banner);
        assertTrue(banner.contains("Runtime  ReAct"), banner);
        assertTrue(banner.contains("Mcp     2/3 online"), banner);
        assertTrue(banner.contains("Skills  1/1 armed"), banner);
        assertTrue(banner.contains("Command / palette"), banner);
        assertTrue(banner.contains("Context @path"), banner);
        assertTrue(banner.contains("Image @image:"), banner);
        assertTrue(banner.contains("Ctrl+O expand"), banner);
    }

    @Test
    void startupBannerUsesVisibleNekoWarmPaletteInTrueColorMode() {
        System.setProperty("mindcli.render.color", "true");
        System.setProperty("mindcli.render.truecolor", "true");

        String banner = String.join("\n", CliStartupView.startupBannerLines("16.1.0",
                new CliStartupView.StartupScreenInfo(
                        "deepseek-v4-flash",
                        "deepseek",
                        2,
                        3,
                        43,
                        1,
                        1,
                        "web-access",
                        "")));

        assertTrue(banner.contains("\u001B[38;2;217;169;169m"), banner); // rose accent
        assertTrue(banner.contains("\u001B[38;2;244;237;232m"), banner); // cream primary
        assertTrue(banner.contains("\u001B[38;2;235;215;209m"), banner); // blush secondary
        assertTrue(banner.contains("\u001B[38;2;140;129;127m"), banner); // taupe muted
    }
}
