package com.mindcli.platform.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Properties;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalCapabilitiesTest {

    private String savedSysProp;

    @BeforeEach
    void save() {
        savedSysProp = System.getProperty("mindcli.no.statusbar");
    }

    @AfterEach
    void restore() {
        if (savedSysProp == null) {
            System.clearProperty("mindcli.no.statusbar");
        } else {
            System.setProperty("mindcli.no.statusbar", savedSysProp);
        }
    }

    @Test
    void nullTerminalIsNotAnsiCapable() {
        assertFalse(TerminalCapabilities.supportsAnsi(null));
    }

    @Test
    void dumbTerminalIsNotAnsiCapable() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        assertFalse(TerminalCapabilities.supportsAnsi(terminal, Map.of()));
    }

    @Test
    void windowsTerminalEnvironmentCanOverrideDumbTerminalType() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        assertTrue(TerminalCapabilities.supportsAnsi(terminal, new Properties(), Map.of(
                "WT_SESSION", "f0c51f06-4bf5-4db6-8d24-7a2d8f1d8201",
                "TERM", "xterm-256color")));
    }

    @Test
    void dumbTermEnvironmentStillDisablesAnsi() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");

        assertFalse(TerminalCapabilities.supportsAnsi(terminal, new Properties(), Map.of("TERM", "dumb")));
    }

    @Test
    void explicitTerminalTypePropertyOverridesDumbTerminal() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        Properties props = new Properties();
        props.setProperty("mindcli.terminal.type", "xterm-256color");

        assertTrue(TerminalCapabilities.supportsAnsi(terminal, props, Map.of("TERM", "dumb")));
    }

    @Test
    void xtermTerminalIsAnsiCapable() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        assertTrue(TerminalCapabilities.supportsAnsi(terminal));
    }

    @Test
    void scrollRegionRequiresMinimumSize() {
        Terminal small = Mockito.mock(Terminal.class);
        Mockito.when(small.getType()).thenReturn("xterm-256color");
        Mockito.when(small.getSize()).thenReturn(new Size(40, 4));
        assertFalse(TerminalCapabilities.supportsScrollRegion(small));
    }

    @Test
    void scrollRegionTrueOnNormalTerminal() {
        Terminal normal = Mockito.mock(Terminal.class);
        Mockito.when(normal.getType()).thenReturn("xterm-256color");
        Mockito.when(normal.getSize()).thenReturn(new Size(120, 40));
        assertTrue(TerminalCapabilities.supportsScrollRegion(normal));
    }

    @Test
    void noStatusbarPropertyDisablesScrollRegion() {
        System.setProperty("mindcli.no.statusbar", "true");
        Terminal normal = Mockito.mock(Terminal.class);
        Mockito.when(normal.getType()).thenReturn("xterm-256color");
        Mockito.when(normal.getSize()).thenReturn(new Size(120, 40));
        assertFalse(TerminalCapabilities.supportsScrollRegion(normal));
    }

    @Test
    void safeSizeFallbackOnNullSize() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(null);
        Size size = TerminalCapabilities.safeSize(terminal);
        assertEquals(80, size.getColumns());
        assertEquals(24, size.getRows());
    }

    @Test
    void safeSizeFallbackOnZeroSize() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(0, 0));
        Size size = TerminalCapabilities.safeSize(terminal);
        assertEquals(80, size.getColumns());
        assertEquals(24, size.getRows());
    }
}
