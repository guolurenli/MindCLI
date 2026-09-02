package com.mindcli.capability.tool.builtin;

import com.mindcli.platform.security.PathGuard;
import com.mindcli.platform.security.PolicyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolExecutorTest {

    @Test
    void readsWholeFile(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("note.txt"), "first\nsecond");
        FileToolExecutor executor = executor(tempDir);

        String result = executor.read(Map.of("path", "note.txt"));

        assertEquals("文件内容:\nfirst\nsecond", result);
    }

    @Test
    void readsNumberedRangeAndSuggestsNextOffset(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("note.txt"), "one\ntwo\nthree\nfour");
        FileToolExecutor executor = executor(tempDir);

        String result = executor.read(Map.of("path", "note.txt", "offset", "2", "limit", "2"));

        assertTrue(result.contains("lines 2-3 of 4"));
        assertTrue(result.contains("2 | two"));
        assertTrue(result.contains("3 | three"));
        assertTrue(result.contains("offset=4"));
    }

    @Test
    void writesFileAndPublishesBeforeAfterContent(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("note.txt"), "before");
        AtomicReference<String[]> observed = new AtomicReference<>();
        AtomicReference<Path> edited = new AtomicReference<>();
        FileToolExecutor executor = new FileToolExecutor(
                new PathGuard(tempDir.toString()),
                (path, contents) -> observed.set(contents),
                (path, file) -> edited.set(file));

        String result = executor.write(Map.of("path", "note.txt", "content", "after"));

        assertEquals("文件已写入: note.txt", result);
        assertEquals("after", Files.readString(tempDir.resolve("note.txt")));
        assertEquals("before", observed.get()[0]);
        assertEquals("after", observed.get()[1]);
        assertEquals(tempDir.resolve("note.txt").toRealPath(), edited.get());
    }

    @Test
    void listsFilesAndDirectories(@TempDir Path tempDir) throws Exception {
        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("README.md"), "demo");
        FileToolExecutor executor = executor(tempDir);

        String result = executor.listDirectory(Map.of("path", "."));

        assertTrue(result.contains("[D] src"));
        assertTrue(result.contains("[F] README.md"));
    }

    @Test
    void rejectsPathsOutsideProject(@TempDir Path tempDir) {
        FileToolExecutor executor = executor(tempDir);
        Path outside = tempDir.getParent().resolve("outside.txt");

        assertThrows(PolicyException.class,
                () -> executor.read(Map.of("path", outside.toString())));
    }

    private FileToolExecutor executor(Path root) {
        return new FileToolExecutor(new PathGuard(root.toString()), (path, contents) -> {}, (path, file) -> {});
    }
}
