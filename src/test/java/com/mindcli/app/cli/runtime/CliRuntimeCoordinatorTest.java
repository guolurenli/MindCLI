package com.mindcli.app.cli.runtime;

import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunResult;
import com.mindcli.runtime.run.ModeAdapter;
import com.mindcli.runtime.run.store.InMemoryRunStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliRuntimeCoordinatorTest {

    @Test
    void executesModeThroughRuntimeAndReturnsContent() {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext[] seen = new AgentRunContext[1];
        ModeAdapter adapter = new ModeAdapter() {
            @Override
            public AgentMode mode() {
                return AgentMode.REACT;
            }

            @Override
            public AgentRunResult execute(AgentRunContext context) {
                return execute(context, runStore);
            }

            @Override
            public AgentRunResult execute(AgentRunContext context, com.mindcli.runtime.run.store.RunStore store) {
                seen[0] = context;
                return AgentRunResult.success(context, "done");
            }
        };

        String content = new CliRuntimeCoordinator().run(
                AgentMode.REACT, "hello", "workspace", runStore, null, adapter, null);

        assertEquals("done", content);
        assertEquals(List.of(AgentRunEventType.RUN_STARTED,
                        AgentRunEventType.MODE_SELECTED,
                        AgentRunEventType.RUN_FINISHED),
                runStore.events(seen[0].runId())
                        .stream().map(AgentRunEvent::type).toList());
    }
}
