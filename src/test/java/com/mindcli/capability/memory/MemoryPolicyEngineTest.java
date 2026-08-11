package com.mindcli.capability.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPolicyEngineTest {

    private final MemoryPolicyEngine engine = new MemoryPolicyEngine();

    @Test
    void allowsProjectScopedManualSaveWhenContentIsSafe() {
        MemoryPolicyDecision decision = engine.evaluate(
                "当前项目使用 Java 17",
                context("project", false, false, "manual"));

        assertEquals(MemoryPolicyDecision.DecisionType.ALLOW, decision.type());
        assertEquals("memory.project.allow", decision.policyId());
    }

    @Test
    void requiresApprovalForGlobalMemory() {
        MemoryPolicyDecision decision = engine.evaluate(
                "默认使用中文回答",
                context("global", false, false, "manual"));

        assertEquals(MemoryPolicyDecision.DecisionType.NEED_APPROVAL, decision.type());
        assertEquals("memory.global.approval", decision.policyId());
    }

    @Test
    void requiresApprovalForAutoExtractedMemory() {
        MemoryPolicyDecision decision = engine.evaluate(
                "用户偏好使用中文回答",
                context("project", false, true, "extractor"));

        assertEquals(MemoryPolicyDecision.DecisionType.NEED_APPROVAL, decision.type());
        assertEquals("memory.auto.proposal", decision.policyId());
    }

    @Test
    void requiresApprovalWhenExternalContextWasUsed() {
        MemoryPolicyDecision decision = engine.evaluate(
                "从网页读取到的团队约定",
                context("project", true, false, "manual"));

        assertEquals(MemoryPolicyDecision.DecisionType.NEED_APPROVAL, decision.type());
        assertEquals("memory.external.approval", decision.policyId());
    }

    @Test
    void deniesSecretsAndPii() {
        MemoryPolicyDecision apiKey = engine.evaluate(
                "API_KEY=sk-1234567890abcdefghijklmnopqrst",
                context("project", false, false, "manual"));
        MemoryPolicyDecision phone = engine.evaluate(
                "用户手机号是 13800138000",
                context("project", false, false, "manual"));

        assertEquals(MemoryPolicyDecision.DecisionType.DENY, apiKey.type());
        assertEquals("memory.sensitive", apiKey.policyId());
        assertEquals(MemoryPolicyDecision.DecisionType.DENY, phone.type());
        assertTrue(phone.reason().contains("敏感"));
    }

    private static MemoryPolicyContext context(String scope, boolean externalContextUsed,
                                               boolean autoExtractEnabled, String source) {
        return new MemoryPolicyContext(
                "/repo/current",
                scope,
                externalContextUsed,
                autoExtractEnabled,
                source,
                "",
                "");
    }
}
