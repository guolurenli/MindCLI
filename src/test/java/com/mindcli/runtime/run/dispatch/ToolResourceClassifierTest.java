package com.mindcli.runtime.run.dispatch;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import com.mindcli.capability.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResourceClassifierTest {
    @TempDir
    Path workspace;

    private final ToolResourceClassifier classifier = new ToolResourceClassifier();

    @Test
    void readFileUsesSharedWorkspaceAndFileLocks() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("read_file", "{\"path\":\"src/Main.java\"}"), context);

        assertEquals(List.of(
                new ResourceKey(ResourceScope.WORKSPACE, canonicalWorkspace().toString(), ResourceAccess.SHARED),
                new ResourceKey(ResourceScope.DIRECTORY, canonicalWorkspace().toString(), ResourceAccess.SHARED),
                new ResourceKey(ResourceScope.DIRECTORY, canonicalWorkspace().resolve("src").toString(), ResourceAccess.SHARED),
                new ResourceKey(ResourceScope.FILE, canonicalWorkspace().resolve(normalizeCase("src/Main.java")).toString(), ResourceAccess.SHARED)
        ), keys);
    }

    @Test
    void writeFileUsesSharedWorkspaceAndExclusiveFileLocks() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("write_file", "{\"path\":\"README.md\",\"content\":\"x\"}"), context);

        assertEquals(List.of(
                new ResourceKey(ResourceScope.WORKSPACE, canonicalWorkspace().toString(), ResourceAccess.SHARED),
                new ResourceKey(ResourceScope.DIRECTORY, canonicalWorkspace().toString(), ResourceAccess.SHARED),
                new ResourceKey(ResourceScope.FILE, canonicalWorkspace().resolve(normalizeCase("README.md")).toString(), ResourceAccess.EXCLUSIVE)
        ), keys);
    }

    @Test
    void listDirUsesExclusiveDirectoryLock() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("list_dir", "{\"path\":\"src\"}"), context);

        assertTrue(keys.contains(new ResourceKey(ResourceScope.DIRECTORY,
                canonicalWorkspace().resolve("src").toString(), ResourceAccess.EXCLUSIVE)));
    }

    @Test
    void saveMemoryUsesExclusiveLongTermMemoryLock() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("save_memory", "{\"fact\":\"x\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.MEMORY, "long-term", ResourceAccess.EXCLUSIVE)), keys);
    }

    @Test
    void symlinkAndTargetFileShareTheSameFileLockWhenSupported() throws Exception {
        Path target = workspace.resolve("target.txt");
        Files.writeString(target, "x");
        Path link = workspace.resolve("alias.txt");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            return;
        }

        ResourceKey targetKey = classifier.classify(
                invocation("write_file", "{\"path\":\"target.txt\",\"content\":\"a\"}"), context())
                .stream().filter(key -> key.scope() == ResourceScope.FILE).findFirst().orElseThrow();
        ResourceKey linkKey = classifier.classify(
                invocation("write_file", "{\"path\":\"alias.txt\",\"content\":\"b\"}"), context())
                .stream().filter(key -> key.scope() == ResourceScope.FILE).findFirst().orElseThrow();

        assertEquals(targetKey, linkKey);
    }

    @Test
    void executeCommandDefaultsToExclusiveWorkspace() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("execute_command", "{\"command\":\"mvn test\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.WORKSPACE, canonicalWorkspace().toString(), ResourceAccess.EXCLUSIVE)), keys);
    }

    @Test
    void knownReadOnlyCommandUsesSharedWorkspace() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("execute_command", "{\"command\":\"git status --short\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.WORKSPACE, canonicalWorkspace().toString(), ResourceAccess.SHARED)), keys);
    }

    @Test
    void gitDiffWithOutputFileUsesExclusiveWorkspaceLock() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("execute_command",
                "{\"command\":\"git diff --output=changes.patch\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.WORKSPACE, canonicalWorkspace().toString(), ResourceAccess.EXCLUSIVE)), keys);
    }

    @Test
    void gitDiffWithExternalDiffUsesExclusiveWorkspaceLock() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("execute_command",
                "{\"command\":\"git diff --ext-diff\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.WORKSPACE, canonicalWorkspace().toString(), ResourceAccess.EXCLUSIVE)), keys);
    }

    @Test
    void pipedReadOnlyLookingCommandUsesExclusiveWorkspaceLock() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("execute_command",
                "{\"command\":\"git status | Out-File status.txt\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.WORKSPACE, canonicalWorkspace().toString(), ResourceAccess.EXCLUSIVE)), keys);
    }

    @Test
    void browserMcpToolsUseExclusiveBrowserSessionLock() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("mcp__chrome__browser_click", "{}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.BROWSER_SESSION, "chrome", ResourceAccess.EXCLUSIVE)), keys);
    }

    @Test
    void genericMcpToolsUseExclusiveServerLock() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("mcp__filesystem__read_file", "{}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.MCP_SERVER, "filesystem", ResourceAccess.EXCLUSIVE)), keys);
    }

    @Test
    void unknownToolsFallBackToExclusiveWorkspaceAndUnknownLocks() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("custom_mutator", "{}"), context);

        assertTrue(keys.contains(new ResourceKey(ResourceScope.WORKSPACE, canonicalWorkspace().toString(), ResourceAccess.EXCLUSIVE)));
        assertTrue(keys.contains(new ResourceKey(ResourceScope.UNKNOWN, "custom_mutator", ResourceAccess.EXCLUSIVE)));
    }

    private AgentRunContext context() {
        return AgentRunContext.create(AgentMode.REACT, "test", workspace.toString());
    }

    private Path canonicalWorkspace() {
        try {
            return Path.of(normalizeCase(workspace.toRealPath().toString()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalizeCase(String value) {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? value.toLowerCase(java.util.Locale.ROOT)
                : value;
    }

    private static ToolRegistry.ToolInvocation invocation(String name, String args) {
        return new ToolRegistry.ToolInvocation("call_1", name, args);
    }
}
