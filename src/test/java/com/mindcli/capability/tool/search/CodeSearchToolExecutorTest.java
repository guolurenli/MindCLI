package com.mindcli.capability.tool.search;

import com.mindcli.platform.security.PathGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchToolExecutorTest {

    @BeforeEach
    void forceDeterministicJavaSearch() {
        System.setProperty("mindcli.search.disable.rg", "true");
    }

    @AfterEach
    void restoreSearchEngineSelection() {
        System.clearProperty("mindcli.search.disable.rg");
    }

    @Test
    void findsGlobMatchesAndSkipsBuildDirectories(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("src/main/java/UserService.java"), "class UserService {}");
        Files.writeString(tempDir.resolve("target/GeneratedService.java"), "class GeneratedService {}");
        CodeSearchToolExecutor executor = executor(tempDir);

        String result = executor.glob(Map.of("pattern", "**/*Service.java"));

        assertTrue(result.contains("src/main/java/UserService.java"));
        assertFalse(result.contains("GeneratedService.java"));
    }

    @Test
    void rendersGrepMatchesWithPartialStateAndSuggestedReads(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/One.java"), "needle\nneedle\n");
        Files.writeString(tempDir.resolve("src/Two.java"), "needle\n");
        CodeSearchToolExecutor executor = executor(tempDir);

        String result = executor.grep(Map.of(
                "pattern", "needle",
                "max_results", "2",
                "head_limit", "2"));

        assertTrue(result.contains("匹配结果 2 条 (engine=java)"));
        assertTrue(result.contains("partial: true"));
        assertTrue(result.contains("suggested_reads:"));
        assertTrue(result.contains("read_file {\"path\":\"src/One.java\""));
    }

    private CodeSearchToolExecutor executor(Path root) {
        return new CodeSearchToolExecutor(new PathGuard(root.toString()));
    }
}
