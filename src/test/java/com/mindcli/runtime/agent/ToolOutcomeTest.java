package com.mindcli.runtime.agent;

import com.mindcli.llm.LlmClient;
import com.mindcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolOutcomeTest {

    @Test
    void mapsTimedOutLegacyResultToTimedOutOutcome() {
        ToolRegistry.ToolExecutionResult legacy = new ToolRegistry.ToolExecutionResult(
                "call_1", "read_file", "{}", "工具执行超时（3秒），已取消",
                3000, true, List.of());

        ToolOutcome outcome = ToolOutcome.fromLegacy(legacy);

        assertEquals(ToolOutcomeStatus.TIMED_OUT, outcome.status());
        assertEquals("read_file", outcome.name());
        assertEquals("工具执行超时（3秒），已取消", outcome.text());
    }

    @Test
    void mapsFailedLegacyTextToFailedOutcome() {
        ToolRegistry.ToolExecutionResult legacy = new ToolRegistry.ToolExecutionResult(
                "call_1", "write_file", "{}", "工具执行失败: denied",
                0, false, List.of());

        ToolOutcome outcome = ToolOutcome.fromLegacy(legacy);

        assertEquals(ToolOutcomeStatus.FAILED, outcome.status());
        assertEquals("denied", outcome.errorMessage());
    }

    @Test
    void mapsPrefixedUserCancellationToCancelledOutcome() {
        ToolRegistry.ToolExecutionResult legacy = new ToolRegistry.ToolExecutionResult(
                "call_1", "execute_command", "{}", "工具执行失败: 用户取消了此次工具调用",
                0, false, List.of());

        ToolOutcome outcome = ToolOutcome.fromLegacy(legacy);

        assertEquals(ToolOutcomeStatus.CANCELLED, outcome.status());
    }

    @Test
    void preservesImagePartsAndCreatesToolMessage() {
        List<LlmClient.ContentPart> images = List.of(LlmClient.ContentPart.imageBase64("abc", "image/png"));
        ToolRegistry.ToolExecutionResult legacy = new ToolRegistry.ToolExecutionResult(
                "call_1", "browser_screenshot", "{}", "screenshot",
                12, false, images);

        ToolOutcome outcome = ToolOutcome.fromLegacy(legacy);

        assertEquals(ToolOutcomeStatus.COMPLETED, outcome.status());
        assertTrue(outcome.hasImageParts());
        assertEquals(images, outcome.imageParts());
        assertEquals("tool", outcome.toToolMessage().role());
        assertEquals("call_1", outcome.toToolMessage().toolCallId());
        assertEquals("screenshot", outcome.toToolMessage().content());
    }
}
