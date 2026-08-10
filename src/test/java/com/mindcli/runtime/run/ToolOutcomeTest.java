package com.mindcli.runtime.run;

import com.mindcli.platform.llm.LlmClient;
import com.mindcli.capability.tool.ToolRegistry;
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
    void mapsPolicyDenialTextToDeniedByPolicyOutcome() {
        ToolRegistry.ToolExecutionResult legacy = new ToolRegistry.ToolExecutionResult(
                "call_1", "write_file", "{}", "🛡️ 策略拒绝: path escapes workspace",
                0, false, List.of());

        ToolOutcome outcome = ToolOutcome.fromLegacy(legacy);

        assertEquals(ToolOutcomeStatus.DENIED_BY_POLICY, outcome.status());
        assertEquals("path escapes workspace", outcome.errorMessage());
        assertEquals("path escapes workspace", outcome.metadata().get("deniedReason"));
    }

    @Test
    void mapsNetworkPolicyDenialTextToDeniedByPolicyOutcome() {
        ToolRegistry.ToolExecutionResult legacy = new ToolRegistry.ToolExecutionResult(
                "call_1", "web_fetch", "{}", "❌ 网络访问被拒绝: host not allowed",
                0, false, List.of());

        ToolOutcome outcome = ToolOutcome.fromLegacy(legacy);

        assertEquals(ToolOutcomeStatus.DENIED_BY_POLICY, outcome.status());
        assertEquals("host not allowed", outcome.errorMessage());
        assertEquals("host not allowed", outcome.metadata().get("deniedReason"));
    }

    @Test
    void mapsWechatPolicyDenialTextToDeniedByPolicyOutcome() {
        ToolRegistry.ToolExecutionResult legacy = new ToolRegistry.ToolExecutionResult(
                "call_1", "execute_command", "{}", "微信通道策略拒绝: command not allowlisted",
                0, false, List.of());

        ToolOutcome outcome = ToolOutcome.fromLegacy(legacy);

        assertEquals(ToolOutcomeStatus.DENIED_BY_POLICY, outcome.status());
        assertEquals("command not allowlisted", outcome.errorMessage());
        assertEquals("command not allowlisted", outcome.metadata().get("deniedReason"));
    }

    @Test
    void mapsHitlRejectionTextToDeniedByUserOutcome() {
        ToolRegistry.ToolExecutionResult legacy = new ToolRegistry.ToolExecutionResult(
                "call_1", "execute_command", "{}", "[HITL] 操作已被拒绝：用户拒绝了此操作",
                0, false, List.of());

        ToolOutcome outcome = ToolOutcome.fromLegacy(legacy);

        assertEquals(ToolOutcomeStatus.DENIED_BY_USER, outcome.status());
        assertEquals("用户拒绝了此操作", outcome.errorMessage());
        assertEquals("用户拒绝了此操作", outcome.metadata().get("deniedReason"));
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
