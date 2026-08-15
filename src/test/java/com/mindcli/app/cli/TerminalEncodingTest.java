package com.mindcli.app.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalEncodingTest {

    @Test
    void explicitPropertyWinsOverConsoleAndDefaultCharset() {
        Properties props = new Properties();
        props.setProperty("mindcli.terminal.encoding", "GBK");

        TerminalEncoding.Plan plan = TerminalEncoding.detect(
                props,
                Map.of("MINDCLI_TERMINAL_ENCODING", "UTF-8"),
                StandardCharsets.UTF_8,
                StandardCharsets.ISO_8859_1,
                "Windows 11");

        assertEquals(Charset.forName("GBK"), plan.charset());
        assertEquals("mindcli.terminal.encoding", plan.source());
        assertTrue(plan.startupNote().contains("GBK"), plan.startupNote());
    }

    @Test
    void environmentVariableWinsWhenPropertyIsMissing() {
        TerminalEncoding.Plan plan = TerminalEncoding.detect(
                new Properties(),
                Map.of("MINDCLI_TERMINAL_ENCODING", "Shift_JIS"),
                StandardCharsets.UTF_8,
                StandardCharsets.ISO_8859_1,
                "Linux");

        assertEquals(Charset.forName("Shift_JIS"), plan.charset());
        assertEquals("MINDCLI_TERMINAL_ENCODING", plan.source());
    }

    @Test
    void jvmStdoutEncodingWinsOverConsoleCharsetInIdeRunConsole() {
        Properties props = new Properties();
        props.setProperty("sun.stdout.encoding", "UTF-8");
        props.setProperty("sun.stderr.encoding", "UTF-8");
        props.setProperty("file.encoding", "UTF-8");

        TerminalEncoding.Plan plan = TerminalEncoding.detect(
                props,
                Map.of(),
                Charset.forName("GBK"),
                StandardCharsets.UTF_8,
                "Windows 11");

        assertEquals(StandardCharsets.UTF_8, plan.charset());
        assertEquals("sun.stdout.encoding", plan.source());
        assertEquals("", plan.startupNote());
    }

    @Test
    void consoleCharsetWinsOverJavaDefaultCharset() {
        TerminalEncoding.Plan plan = TerminalEncoding.detect(
                new Properties(),
                Map.of(),
                Charset.forName("GBK"),
                StandardCharsets.UTF_8,
                "Windows 11");

        assertEquals(Charset.forName("GBK"), plan.charset());
        assertEquals("console", plan.source());
        assertTrue(plan.startupNote().contains("已按该编码读写"), plan.startupNote());
        assertTrue(plan.startupNote().contains("chcp 65001"), plan.startupNote());
    }

    @Test
    void utf8ConsoleDoesNotEmitStartupWarning() {
        TerminalEncoding.Plan plan = TerminalEncoding.detect(
                new Properties(),
                Map.of(),
                StandardCharsets.UTF_8,
                Charset.forName("GBK"),
                "Windows 11");

        assertEquals(StandardCharsets.UTF_8, plan.charset());
        assertTrue(plan.isUtf8Compatible());
        assertEquals("", plan.startupNote());
    }

    @Test
    void invalidExplicitEncodingFallsBackAndReportsReason() {
        Properties props = new Properties();
        props.setProperty("mindcli.terminal.encoding", "not-a-charset");

        TerminalEncoding.Plan plan = TerminalEncoding.detect(
                props,
                Map.of(),
                StandardCharsets.UTF_8,
                Charset.forName("GBK"),
                "Windows 11");

        assertEquals(StandardCharsets.UTF_8, plan.charset());
        assertFalse(plan.startupNote().isBlank());
        assertTrue(plan.startupNote().contains("not-a-charset"), plan.startupNote());
    }

    @Test
    void terminalTypeUsesExplicitPropertyBeforeTermEnvironment() {
        Properties props = new Properties();
        props.setProperty("mindcli.terminal.type", "xterm-direct");

        assertEquals("xterm-direct", TerminalEncoding.detectTerminalType(props, Map.of("TERM", "xterm-256color")));
    }

    @Test
    void terminalTypeUsesNonDumbTermEnvironment() {
        assertEquals("xterm-256color", TerminalEncoding.detectTerminalType(new Properties(),
                Map.of("TERM", "xterm-256color")));
    }

    @Test
    void terminalTypeIgnoresDumbTermEnvironment() {
        assertEquals("", TerminalEncoding.detectTerminalType(new Properties(), Map.of("TERM", "dumb")));
    }
}
