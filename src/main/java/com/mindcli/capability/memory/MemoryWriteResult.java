package com.mindcli.capability.memory;

public record MemoryWriteResult(
        Status status,
        String policyId,
        String message,
        MemoryEntry entry,
        MemoryProposal proposal
) {
    public enum Status {
        WRITTEN,
        PROPOSED,
        DENIED
    }

    public MemoryWriteResult {
        status = status == null ? Status.DENIED : status;
        policyId = policyId == null ? "" : policyId;
        message = message == null ? "" : message;
    }

    public static MemoryWriteResult written(MemoryEntry entry, String policyId, String message) {
        return new MemoryWriteResult(Status.WRITTEN, policyId, message, entry, null);
    }

    public static MemoryWriteResult proposed(MemoryProposal proposal, String policyId, String message) {
        return new MemoryWriteResult(Status.PROPOSED, policyId, message, null, proposal);
    }

    public static MemoryWriteResult denied(String policyId, String message) {
        return new MemoryWriteResult(Status.DENIED, policyId, message, null, null);
    }

}
