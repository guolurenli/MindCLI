package com.mindcli.platform.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalMascotRendererTest {

    @AfterEach
    void restore() {
        System.clearProperty("mindcli.ui.mascot");
    }

    @Test
    void startupMascotUsesAnsiArtResourceOnLargeAnsiTerminal() {
        System.setProperty("mindcli.ui.mascot", "true");
        Terminal terminal = terminal("xterm-256color", 120, 60);

        String mascot = TerminalMascotRenderer.startupMascot(terminal).orElseThrow();

        assertTrue(mascot.contains("\u001B[38;2;"), mascot);
        assertTrue(mascot.contains(";48;2;"), mascot);
        assertTrue(mascot.contains("▀"), mascot);
        assertFalse(mascot.contains("\u001B]1337;File=inline=1;"), mascot);
        assertTrue(visibleLineCount(mascot) >= 40, mascot);
    }

    @Test
    void startupMascotUsesCompactAnsiArtOnShortTerminal() {
        System.setProperty("mindcli.ui.mascot", "true");
        Terminal terminal = terminal("xterm-256color", 100, 52);

        String mascot = TerminalMascotRenderer.startupMascot(terminal).orElseThrow();

        assertTrue(mascot.contains("▀"), mascot);
        assertTrue(visibleLineCount(mascot) <= 40, mascot);
    }

    @Test
    void startupMascotFallsBackToAsciiGlyphsWhenTerminalEncodingCannotRenderBlockGlyphs() {
        System.setProperty("mindcli.ui.mascot", "true");
        Terminal terminal = terminal("xterm-256color", 120, 60);
        Mockito.when(terminal.encoding()).thenReturn(StandardCharsets.US_ASCII);

        String mascot = TerminalMascotRenderer.startupMascot(terminal).orElseThrow();

        assertTrue(mascot.contains("\u001B[38;2;"), mascot);
        assertTrue(mascot.contains("#"), mascot);
        assertFalse(mascot.contains("▀"), mascot);
    }

    @Test
    void startupMascotIsDisabledByProperty() {
        System.setProperty("mindcli.ui.mascot", "false");
        Terminal terminal = terminal("xterm-256color", 120, 60);

        assertFalse(TerminalMascotRenderer.startupMascot(terminal).isPresent());
    }

    private static Terminal terminal(String type, int columns, int rows) {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn(type);
        Mockito.when(terminal.getSize()).thenReturn(new Size(columns, rows));
        return terminal;
    }

    private static long visibleLineCount(String value) {
        return value.lines()
                .filter(line -> !line.isBlank())
                .count();
    }
}
