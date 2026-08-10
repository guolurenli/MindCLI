package com.mindcli.app.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainCliStartupViewRefactorTest {

    @Test
    void cliStartupViewKeepsMainBannerFacade() {
        List<String> mainLines = Main.startupBannerLines();
        List<String> viewLines = CliStartupView.startupBannerLines("16.1.0");

        assertEquals(mainLines, viewLines);
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("MindCLI") && line.contains("v16.1.0")));
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("Tips for getting started")));
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
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("glm-5.1")));
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("MCP 1/2")));
        assertTrue(viewLines.stream().anyMatch(line -> line.contains("MCP ready")));
    }
}
