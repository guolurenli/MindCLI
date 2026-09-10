package com.mindcli.runtime.run.store;

import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;

import java.nio.file.Path;
import java.util.Map;

/** Test-only process entry point for exercising cross-JVM ledger locking. */
public final class JsonlRunStoreProcessWriter {
    private JsonlRunStoreProcessWriter() {
    }

    public static void main(String[] args) {
        Path runsRoot = Path.of(args[0]);
        String runId = args[1];
        int count = Integer.parseInt(args[2]);
        JsonlRunStore store = new JsonlRunStore(runsRoot);
        AgentRunContext context = new AgentRunContext(runId, AgentMode.REACT, "process", runsRoot.toString(),
                null, Map.of());
        for (int i = 0; i < count; i++) {
            store.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE,
                    Map.of("index", Integer.toString(i))));
        }
    }
}
