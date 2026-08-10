package com.mindcli.app.cli;

import com.mindcli.app.cli.command.ExportCommandHandler;
import com.mindcli.app.cli.command.RunCommandHandler;
import com.mindcli.app.cli.command.SnapshotCommandHandler;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.InMemoryRunStore;
import com.mindcli.platform.snapshot.RestoreResult;
import com.mindcli.platform.snapshot.SideGitManager;
import com.mindcli.platform.snapshot.SnapshotPhase;
import com.mindcli.platform.snapshot.SnapshotService;
import com.mindcli.platform.snapshot.TurnSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainCommandHandlerRefactorTest {

    @Test
    void exportHandlerMatchesMainFacade() {
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.system("system prompt"),
                LlmClient.Message.user("读取文件"),
                LlmClient.Message.tool("call-1", "工具结果\n```java\nclass A {}\n```")
        );
        LocalDateTime exportedAt = LocalDateTime.of(2026, 8, 10, 19, 45);

        assertEquals(Main.hasExportableMessages(history), ExportCommandHandler.hasExportableMessages(history));
        assertEquals(Main.countExportedMessages(history), ExportCommandHandler.countExportedMessages(history));
        assertEquals(Main.markdownFenceFor("```java"), ExportCommandHandler.markdownFenceFor("```java"));
        assertEquals(Main.renderConversationExport(history, exportedAt),
                ExportCommandHandler.renderConversationExport(history, exportedAt));
    }

    @Test
    void snapshotHandlerFormatsListAndRestoreHints(@TempDir Path tempDir) throws Exception {
        RecordingSnapshotService service = new RecordingSnapshotService(tempDir);
        service.snapshots = List.of(
                new TurnSnapshot("1234567890abcdef", SnapshotPhase.PRE_TURN,
                        "react-1", Instant.parse("2026-08-10T10:00:00Z"), "before"),
                new TurnSnapshot("abcdef1234567890", SnapshotPhase.POST_TURN,
                        "react-1", Instant.parse("2026-08-10T10:01:00Z"), "after")
        );
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        SnapshotCommandHandler.printSnapshotCommand(printStream(sink), service, "list");

        String output = sink.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("最近 2 条 Side-Git 快照"), output);
        assertTrue(output.contains("1234567890"), output);
        assertTrue(output.contains("pre-turn"), output);
        assertTrue(output.contains("/restore 1"), output);
    }

    @Test
    void snapshotHandlerRestoresRequestedOffset(@TempDir Path tempDir) throws Exception {
        RecordingSnapshotService service = new RecordingSnapshotService(tempDir);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        SnapshotCommandHandler.printRestoreCommand(printStream(sink), service, "2");

        assertEquals(2, service.restoreOffset);
        String output = sink.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("已恢复到快照 commit-123"), output);
    }

    @Test
    void runHandlerFormatsRunInspect(@TempDir Path tempDir) {
        InMemoryRunStore runStore = new InMemoryRunStore();
        AgentRunContext context = new AgentRunContext(
                "run_test",
                AgentMode.REACT,
                "hello",
                tempDir.toString(),
                Instant.parse("2026-08-10T10:00:00Z"),
                Map.of());
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.SNAPSHOT_CREATED, Map.of(
                "snapshotPhase", "PRE_RUN",
                "snapshotCommitId", "commit-pre")));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.RUN_STARTED));
        runStore.append(AgentRunEvent.of(context, AgentRunEventType.LLM_RESPONSE));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        RunCommandHandler.printRunInspect(printStream(sink), runStore, "inspect run_test");

        String output = sink.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Run Inspect"), output);
        assertTrue(output.contains("Run: run_test"), output);
        assertTrue(output.contains("Status: RESUMABLE"), output);
        assertTrue(output.contains("Last completed: LLM_RESPONSE"), output);
        assertTrue(output.contains("Pre-run snapshot: commit-pre"), output);
    }

    private static PrintStream printStream(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    private static final class RecordingSnapshotService extends SnapshotService {
        private List<TurnSnapshot> snapshots = List.of();
        private int restoreOffset;

        private RecordingSnapshotService(Path projectRoot) {
            super(new SideGitManager(projectRoot));
        }

        @Override
        public List<TurnSnapshot> listSnapshots(int limit) {
            return snapshots.stream().limit(limit).toList();
        }

        @Override
        public RestoreResult restorePreTurn(int offset) {
            restoreOffset = offset;
            return RestoreResult.success("commit-1234567890", List.of("a.txt"), List.of());
        }
    }
}
