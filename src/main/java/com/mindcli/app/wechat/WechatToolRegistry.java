package com.mindcli.app.wechat;

import com.mindcli.platform.security.AuditLog;
import com.mindcli.capability.tool.ToolOutput;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.ToolExecution;

import java.util.concurrent.TimeUnit;

public class WechatToolRegistry extends ToolRegistry {
    private final WechatPolicyDecider decider;

    public WechatToolRegistry(WechatPolicyDecider decider) {
        this.decider = decider;
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        return executeToolExecution(name, argumentsJson).output();
    }

    @Override
    public ToolExecution executeToolExecution(String name, String argumentsJson) {
        long start = System.nanoTime();
        WechatPolicyDecision decision = decider == null
                ? WechatPolicyDecision.allow()
                : decider.decide(name, argumentsJson);
        if (!decision.allowed()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(
                    name,
                    argumentsJson,
                    decision.reason(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            return ToolExecution.deniedByPolicy("微信通道策略拒绝: " + decision.reason(),
                    argumentsJson, decision.reason());
        }
        return super.doExecuteToolExecution(name, argumentsJson);
    }
}
