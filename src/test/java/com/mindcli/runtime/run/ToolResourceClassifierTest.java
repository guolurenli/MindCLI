package com.mindcli.runtime.run;

import com.mindcli.capability.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                new ResourceKey(ResourceScope.WORKSPACE, workspace.normalize().toString(), ResourceAccess.SHARED),
                new ResourceKey(ResourceScope.DIRECTORY, workspace.normalize().toString(), ResourceAccess.SHARED),
                new ResourceKey(ResourceScope.DIRECTORY, workspace.resolve("src").normalize().toString(), ResourceAccess.SHARED),
                new ResourceKey(ResourceScope.FILE, workspace.resolve("src/Main.java").normalize().toString(), ResourceAccess.SHARED)
        ), keys);
    }

    @Test
    void writeFileUsesSharedWorkspaceAndExclusiveFileLocks() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("write_file", "{\"path\":\"README.md\",\"content\":\"x\"}"), context);

        assertEquals(List.of(
                new ResourceKey(ResourceScope.WORKSPACE, workspace.normalize().toString(), ResourceAccess.SHARED),
                new ResourceKey(ResourceScope.DIRECTORY, workspace.normalize().toString(), ResourceAccess.SHARED),
                new ResourceKey(ResourceScope.FILE, workspace.resolve("README.md").normalize().toString(), ResourceAccess.EXCLUSIVE)
        ), keys);
    }

    @Test
    void executeCommandDefaultsToExclusiveWorkspace() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("execute_command", "{\"command\":\"mvn test\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.WORKSPACE, workspace.normalize().toString(), ResourceAccess.EXCLUSIVE)), keys);
    }

    @Test
    void knownReadOnlyCommandUsesSharedWorkspace() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("execute_command", "{\"command\":\"git status --short\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.WORKSPACE, workspace.normalize().toString(), ResourceAccess.SHARED)), keys);
    }

    @Test
    void gitDiffWithOutputFileUsesExclusiveWorkspaceLock() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("execute_command",
                "{\"command\":\"git diff --output=changes.patch\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.WORKSPACE, workspace.normalize().toString(), ResourceAccess.EXCLUSIVE)), keys);
    }

    @Test
    void gitDiffWithExternalDiffUsesExclusiveWorkspaceLock() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("execute_command",
                "{\"command\":\"git diff --ext-diff\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.WORKSPACE, workspace.normalize().toString(), ResourceAccess.EXCLUSIVE)), keys);
    }

    @Test
    void pipedReadOnlyLookingCommandUsesExclusiveWorkspaceLock() {
        AgentRunContext context = context();

        List<ResourceKey> keys = classifier.classify(invocation("execute_command",
                "{\"command\":\"git status | Out-File status.txt\"}"), context);

        assertEquals(List.of(new ResourceKey(ResourceScope.WORKSPACE, workspace.normalize().toString(), ResourceAccess.EXCLUSIVE)), keys);
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

        assertTrue(keys.contains(new ResourceKey(ResourceScope.WORKSPACE, workspace.normalize().toString(), ResourceAccess.EXCLUSIVE)));
        assertTrue(keys.contains(new ResourceKey(ResourceScope.UNKNOWN, "custom_mutator", ResourceAccess.EXCLUSIVE)));
    }

    private AgentRunContext context() {
        return AgentRunContext.create(AgentMode.REACT, "test", workspace.toString());
    }

    private static ToolRegistry.ToolInvocation invocation(String name, String args) {
        return new ToolRegistry.ToolInvocation("call_1", name, args);
    }
}
