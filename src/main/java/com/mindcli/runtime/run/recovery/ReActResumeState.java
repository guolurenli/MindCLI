package com.mindcli.runtime.run.recovery;

import com.mindcli.platform.llm.LlmClient;

import java.util.List;

/** Reconstructed message boundary for a ReAct retry. */
public record ReActResumeState(boolean available, List<LlmClient.Message> messages, String reason) {
    public ReActResumeState {
        messages = messages == null ? List.of() : List.copyOf(messages);
        reason = reason == null ? "" : reason;
    }
}
