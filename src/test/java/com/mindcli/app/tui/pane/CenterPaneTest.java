package com.mindcli.app.tui.pane;

import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.TextBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CenterPaneTest {

    @Test
    void initialTranscriptUsesCyberLiteConsoleCopy() {
        CenterPane pane = new CenterPane(null, null);

        String text = chatText(pane);

        assertTrue(text.contains("MINDCLI // TUI ONLINE"), text);
        assertTrue(text.contains("COMMAND /"), text);
        assertTrue(text.contains("CONTEXT @path"), text);
        assertTrue(text.contains("CTRL+O"), text);
    }

    @Test
    void renderedMessagesUseCyberLiteLabels() {
        CenterPane pane = new CenterPane(null, null);

        pane.onUserMessage("hello");
        pane.appendSystemMessage("system note");
        pane.appendAssistantOutput("assistant note");
        pane.appendToolCall("read_file", "{\"path\":\"README.md\"}");
        pane.appendToolResult("ok");

        String text = chatText(pane);

        assertTrue(text.contains("USER //"), text);
        assertTrue(text.contains("SYS //"), text);
        assertTrue(text.contains("MINDCLI //"), text);
        assertTrue(text.contains("TOOL // read_file"), text);
        assertTrue(text.contains("OUT //"), text);
    }

    private static String chatText(CenterPane pane) {
        Component child = pane.getChildrenList().get(0);
        assertInstanceOf(TextBox.class, child);
        return ((TextBox) child).getText();
    }
}
