package com.mindcli.platform.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Startup mascot renderer backed by ANSI text resources.
 *
 * <p>This deliberately avoids terminal image protocols. ANSI text survives the same
 * JLine printAbove path as the rest of the startup screen, while unsupported terminals
 * can keep the plain text banner.
 */
final class TerminalMascotRenderer {

    private static final String STARTUP_RESOURCE = "/ui/mindcli-neko-helper-startup.ans";
    private static final String COMPACT_RESOURCE = "/ui/mindcli-neko-helper-compact.ans";
    private static final int STARTUP_HEIGHT_ROWS = 47;
    private static final int STARTUP_WIDTH_COLUMNS = 72;
    private static final int COMPACT_HEIGHT_ROWS = 38;
    private static final int COMPACT_WIDTH_COLUMNS = 42;
    private static final String UNICODE_ART_GLYPHS = "▀";

    private TerminalMascotRenderer() {
    }

    static Optional<String> startupMascot(Terminal terminal) {
        if (!mascotEnabled() || !TerminalCapabilities.supportsAnsi(terminal)) {
            return Optional.empty();
        }
        Size size = TerminalCapabilities.safeSize(terminal);
        String resource = selectResource(size);
        Optional<String> mascot = readResource(resource);
        if (mascot.isEmpty() || TerminalCapabilities.supportsUnicodeGlyphs(terminal, UNICODE_ART_GLYPHS)) {
            return mascot;
        }
        return mascot.map(TerminalMascotRenderer::asciiFallback);
    }

    private static String selectResource(Size size) {
        if (size.getColumns() >= STARTUP_WIDTH_COLUMNS && size.getRows() >= STARTUP_HEIGHT_ROWS + 12) {
            return STARTUP_RESOURCE;
        }
        if (size.getColumns() >= COMPACT_WIDTH_COLUMNS && size.getRows() >= COMPACT_HEIGHT_ROWS + 10) {
            return COMPACT_RESOURCE;
        }
        return "";
    }

    private static Optional<String> readResource(String resource) {
        if (resource.isBlank()) {
            return Optional.empty();
        }
        try (InputStream in = TerminalMascotRenderer.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(text.endsWith("\n") ? text : text + "\n");
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String asciiFallback(String text) {
        return text == null ? "" : text.replace('▀', '#');
    }

    private static boolean mascotEnabled() {
        String value = firstNonBlank(System.getProperty("mindcli.ui.mascot"),
                System.getenv("MINDCLI_UI_MASCOT"));
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
