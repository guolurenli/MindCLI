package com.mindcli.runtime.run;

import com.mindcli.capability.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HookManagerTest {

    @Test
    void noHooksAllowByDefault() {
        HookDecision decision = HookManager.noop().fire(event());

        assertEquals(HookDecisionType.ALLOW, decision.type());
    }

    @Test
    void firstDenyDecisionStopsLaterHooks() {
        AtomicInteger called = new AtomicInteger();
        HookManager manager = new HookManager(List.of(
                event -> HookDecision.denyByPolicy("blocked"),
                event -> {
                    called.incrementAndGet();
                    return HookDecision.allow();
                }
        ));

        HookDecision decision = manager.fire(event());

        assertEquals(HookDecisionType.DENY_BY_POLICY, decision.type());
        assertEquals("blocked", decision.reason());
        assertEquals(0, called.get());
    }

    @Test
    void modifyArgumentsDecisionCarriesEffectiveArguments() {
        HookManager manager = new HookManager(List.of(
                event -> HookDecision.modifyArguments("{\"path\":\"safe.txt\"}")
        ));

        HookDecision decision = manager.fire(event());

        assertEquals(HookDecisionType.MODIFY_ARGUMENTS, decision.type());
        assertEquals("{\"path\":\"safe.txt\"}", decision.effectiveArgumentsJson());
    }

    private static HookEvent event() {
        return HookEvent.of(
                HookType.PRE_TOOL_USE,
                new ToolRegistry.ToolInvocation("call_1", "read_file", "{\"path\":\"a.txt\"}"),
                AgentRunContext.create(AgentMode.REACT, "test", "workspace"));
    }
}
