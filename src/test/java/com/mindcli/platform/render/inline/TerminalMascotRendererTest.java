package com.mindcli.platform.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalMascotRendererTest {

    @AfterEach
    void restore() {
        System.clearProperty("mindcli.ui.mascot");
        System.clearProperty("mindcli.chafa.bin");
    }

    @Test
    void startupMascotUsesNativeChafaImageResourceAtTenByTen() {
        System.setProperty("mindcli.ui.mascot", "true");
        AtomicReference<byte[]> imageBytes = new AtomicReference<>();
        AtomicInteger columns = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        TerminalMascotRenderer.ChafaRunner runner = (bytes, targetColumns, targetRows) -> {
            imageBytes.set(bytes);
            columns.set(targetColumns);
            rows.set(targetRows);
            return true;
        };

        assertTrue(TerminalMascotRenderer.renderStartupMascot(
                terminal("dumb", 20, 10), runner));

        assertEquals(10, columns.get());
        assertEquals(10, rows.get());
        assertTrue(imageBytes.get().length > 1024);
    }

    @Test
    void startupMascotFallsBackWhenChafaFails() {
        System.setProperty("mindcli.ui.mascot", "true");
        TerminalMascotRenderer.ChafaRunner runner = (bytes, columns, rows) -> false;

        assertFalse(TerminalMascotRenderer.renderStartupMascot(
                terminal("xterm-256color", 120, 40), runner));
    }

    @Test
    void startupMascotDoesNotRequireInlineAnsiCapability() {
        System.setProperty("mindcli.ui.mascot", "true");
        TerminalMascotRenderer.ChafaRunner runner = (bytes, columns, rows) -> true;

        assertTrue(TerminalMascotRenderer.renderStartupMascot(
                terminal("dumb", 10, 10), runner));
    }

    @Test
    void startupMascotRunsConfiguredNativeChafaCommandDirectly() throws IOException {
        System.setProperty("mindcli.ui.mascot", "true");
        Path output = Files.createTempFile("fake-chafa-output-", ".txt");
        Path fakeChafa = createFakeChafaCommand(output);
        System.setProperty("mindcli.chafa.bin", fakeChafa.toString());

        assertTrue(TerminalMascotRenderer.renderStartupMascot(
                terminal("xterm-256color", 120, 40)));

        String command = Files.readString(output, StandardCharsets.UTF_8);
        assertTrue(command.contains("FAKE-CHAFA"), command);
        assertTrue(command.contains("-s 10x10"), command);
        assertTrue(command.contains("--dither ordered"), command);
        assertFalse(command.contains("-f symbols"), command);
        assertFalse(command.contains("-c full"), command);
        Files.deleteIfExists(output);
    }

    @Test
    void startupMascotLetsNativeChafaProbeTheRealTerminal() {
        ProcessBuilder builder = TerminalMascotRenderer.chafaProcessBuilder(
                10, 10, Path.of("mindcli-neko-helper.png"));

        assertEquals(ProcessBuilder.Redirect.INHERIT, builder.redirectInput());
        assertEquals(ProcessBuilder.Redirect.INHERIT, builder.redirectOutput());
        assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError());
    }

    @Test
    void startupMascotDiscoversPngResourcesFromUiDirectory() throws Exception {
        Path root = Files.createTempDirectory("mindcli-ui-resources-");
        Path ui = Files.createDirectories(root.resolve("ui"));
        Files.write(ui.resolve("noke.png"), new byte[]{1});
        Files.write(ui.resolve("noke1.PNG"), new byte[]{2});
        Files.write(ui.resolve("notes.txt"), new byte[]{3});
        Files.createDirectories(ui.resolve("nested"));
        Files.write(ui.resolve("nested").resolve("ignored.png"), new byte[]{4});

        try (URLClassLoader loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, null)) {
            assertEquals(List.of("/ui/noke.png", "/ui/noke1.PNG"),
                    TerminalMascotRenderer.startupImageResources(loader));
        }
    }

    @Test
    void startupMascotSelectsRandomPngResourceFromSortedCandidates() {
        Optional<String> selected = TerminalMascotRenderer.selectStartupImageResource(
                List.of("/ui/noke2.png", "/ui/readme.txt", "/ui/noke.png", "/ui/noke1.png"),
                bound -> bound - 1);

        assertEquals(Optional.of("/ui/noke2.png"), selected);
    }

    @Test
    void startupMascotIsDisabledByProperty() {
        System.setProperty("mindcli.ui.mascot", "false");
        AtomicBoolean called = new AtomicBoolean();
        TerminalMascotRenderer.ChafaRunner runner = (bytes, columns, rows) -> {
            called.set(true);
            return true;
        };

        assertFalse(TerminalMascotRenderer.renderStartupMascot(
                terminal("xterm-256color", 120, 60), runner));
        assertFalse(called.get());
    }

    private static Terminal terminal(String type, int columns, int rows) {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn(type);
        Mockito.when(terminal.getSize()).thenReturn(new Size(columns, rows));
        return terminal;
    }

    private static Path createFakeChafaCommand(Path output) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = Files.createTempFile("fake-chafa-", windows ? ".cmd" : ".sh");
        String body;
        if (windows) {
            body = "@echo off\r\necho FAKE-CHAFA %* > \"" + output + "\"\r\n";
        } else {
            String safeOutput = output.toString().replace("'", "'\\''");
            body = "#!/bin/sh\nprintf 'FAKE-CHAFA %s\\n' \"$*\" > '" + safeOutput + "'\n";
        }
        Files.writeString(script, body, StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        script.toFile().deleteOnExit();
        output.toFile().deleteOnExit();
        return script;
    }
}
