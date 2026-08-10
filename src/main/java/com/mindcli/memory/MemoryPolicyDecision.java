package com.mindcli.memory;

public record MemoryPolicyDecision(
        DecisionType type,
        String policyId,
        String reason
) {
    public enum DecisionType {
        ALLOW,
        NEED_APPROVAL,
        DENY
    }

    public MemoryPolicyDecision {
        type = type == null ? DecisionType.DENY : type;
        policyId = policyId == null ? "" : policyId;
        reason = reason == null ? "" : reason;
    }

    public static MemoryPolicyDecision allow(String policyId, String reason) {
        return new MemoryPolicyDecision(DecisionType.ALLOW, policyId, reason);
    }

    public static MemoryPolicyDecision needApproval(String policyId, String reason) {
        return new MemoryPolicyDecision(DecisionType.NEED_APPROVAL, policyId, reason);
    }

    public static MemoryPolicyDecision deny(String policyId, String reason) {
        return new MemoryPolicyDecision(DecisionType.DENY, policyId, reason);
    }
}
