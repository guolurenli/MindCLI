package com.mindcli.app.tui.pane;

import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Label;
import com.mindcli.platform.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusPaneTest {

    @Test
    void initialLabelsUseCyberLitePrefixes() {
        StatusPane pane = new StatusPane(null, new StubLlmClient("glm-5.1"));

        List<String> labels = labelTexts(pane);

        assertEquals("MODEL // glm-5.1", labels.get(0));
        assertEquals("CTX // --", labels.get(1));
        assertEquals("MODE // ReAct", labels.get(2));
        assertEquals("TIME // --", labels.get(3));
    }

    @Test
    void updatesKeepCyberLitePrefixes() {
        StatusPane pane = new StatusPane(null, new StubLlmClient("glm-5.1"));

        pane.updateTokenUsage(1200, 200000, 100);
        pane.updateMode("Plan");

        List<String> labels = labelTexts(pane);

        assertTrue(labels.get(1).contains("CTX // 1200/200000"), labels.get(1));
        assertTrue(labels.get(1).contains("CACHE 100"), labels.get(1));
        assertEquals("MODE // Plan", labels.get(2));
    }

    private static List<String> labelTexts(StatusPane pane) {
        return pane.getChildrenList().stream()
                .map(StatusPaneTest::labelText)
                .toList();
    }

    private static String labelText(Component component) {
        assertInstanceOf(Label.class, component);
        return ((Label) component).getText();
    }

    private record StubLlmClient(String model) implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "", null, 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return model;
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
