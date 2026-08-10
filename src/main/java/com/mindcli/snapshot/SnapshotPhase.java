package com.mindcli.snapshot;

public enum SnapshotPhase {
    PRE_RUN("pre-run"),
    POST_RUN("post-run"),
    PRE_TURN("pre-turn"),
    POST_TURN("post-turn"),
    PRE_RESTORE("pre-restore");

    private final String label;

    SnapshotPhase(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
