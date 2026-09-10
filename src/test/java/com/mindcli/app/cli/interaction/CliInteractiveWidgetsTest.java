package com.mindcli.app.cli.interaction;

import com.mindcli.platform.render.inline.InlineRenderer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliInteractiveWidgetsTest {

    @Test
    void widgetConfigurationIsNullSafe() {
        assertDoesNotThrow(() -> CliInteractiveWidgets.configureJLineInteractiveWidgets(null));
        assertDoesNotThrow(() -> CliInteractiveWidgets.configureSlashCommandHint(null));
        assertDoesNotThrow(() -> CliInteractiveWidgets.bindCtrlOToFoldableBlocks(null, null));
        assertDoesNotThrow(() -> CliInteractiveWidgets.bindCtrlVToClipboardImage(null));
        assertDoesNotThrow(() -> CliInteractiveWidgets.bindEscToClearInput(null));
    }

    @Test
    void bindsMindCliWidgetsToLineReader() throws Exception {
        try (Terminal terminal = TerminalBuilder.builder().dumb(true).build()) {
            LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).build();
            InlineRenderer inline = new InlineRenderer(terminal);

            CliInteractiveWidgets.configureJLineInteractiveWidgets(lineReader);
            CliInteractiveWidgets.configureSlashCommandHint(lineReader);
            CliInteractiveWidgets.bindCtrlOToFoldableBlocks(lineReader, inline);
            CliInteractiveWidgets.bindCtrlVToClipboardImage(lineReader);
            CliInteractiveWidgets.bindEscToClearInput(lineReader);

            assertTrue(lineReader.getWidgets().containsKey("mindcli-toggle-foldable"));
            assertTrue(lineReader.getWidgets().containsKey("mindcli-paste-clipboard-image"));
            assertTrue(lineReader.getWidgets().containsKey("mindcli-clear-input"));
            assertTrue(lineReader.getWidgets().containsKey("mindcli-slash-command-hint"));
        }
    }
}
