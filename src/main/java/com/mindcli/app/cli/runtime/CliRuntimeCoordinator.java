package com.mindcli.app.cli.runtime;

import com.mindcli.platform.snapshot.SnapshotService;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentModeRouter;
import com.mindcli.runtime.run.AgentRunResult;
import com.mindcli.runtime.run.ModeAdapter;
import com.mindcli.runtime.run.mode.ReActModeAdapter;
import com.mindcli.runtime.run.session.SessionContext;
import com.mindcli.runtime.run.store.RunStore;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Coordinates CLI mode execution with the shared Agent Runtime.
 *
 * <p>The CLI facade owns terminal interaction; this module owns the invariant
 * that every mode goes through one runtime, one store, and one session update.</p>
 */
public final class CliRuntimeCoordinator {

    public String run(AgentMode mode, String input, String workspace,
                      RunStore runStore, SnapshotService snapshotService,
                      ModeAdapter adapter, SessionContext sessionContext) {
        AgentModeRouter router = new AgentModeRouter(
                new com.mindcli.runtime.run.AgentRuntime(runStore, snapshotService),
                List.of(adapter), workspace);
        AgentRunResult result = router.submit(input, mode);
        recordSession(result, adapter, sessionContext);
        return userFacingContent(result);
    }

    public String runReact(String input, String workspace,
                           RunStore runStore, SnapshotService snapshotService,
                           ModeAdapter adapter, SessionContext sessionContext) {
        return run(AgentMode.REACT, input, workspace, runStore, snapshotService, adapter, sessionContext);
    }

    public <T> T runTask(Callable<T> task) throws Exception {
        return task.call();
    }

    public static String userFacingContent(AgentRunResult result) {
        if (result == null) {
            return "";
        }
        if (result.isSuccess() || result.status() == com.mindcli.runtime.run.AgentRunStatus.CANCELLED) {
            return result.content();
        }
        if (result.errorMessage() != null && !result.errorMessage().isBlank()) {
            return result.errorMessage();
        }
        return result.content();
    }

    private static void recordSession(AgentRunResult result, ModeAdapter adapter,
                                      SessionContext sessionContext) {
        if (sessionContext == null) {
            return;
        }
        String contentOverride = adapter instanceof ReActModeAdapter reactAdapter
                ? reactAdapter.latestAssistantResponse()
                : null;
        sessionContext.record(result, contentOverride);
    }
}
