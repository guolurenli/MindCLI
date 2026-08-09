package com.mindcli.runtime.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunStoreFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void createsJsonlRunStoreAtConfiguredRoot() {
        Path runsRoot = tempDir.resolve("runs");

        RunStore runStore = RunStoreFactory.create(runsRoot);

        JsonlRunStore jsonlRunStore = assertInstanceOf(JsonlRunStore.class, runStore);
        assertEquals(runsRoot.toAbsolutePath().normalize(), jsonlRunStore.runsRoot());
        assertTrue(Files.isDirectory(runsRoot));
    }

    @Test
    void fallsBackToMemoryWhenConfiguredRootIsAFile() throws Exception {
        Path notDirectory = tempDir.resolve("runs-as-file");
        Files.writeString(notDirectory, "not a directory");

        RunStore runStore = RunStoreFactory.create(notDirectory);

        assertInstanceOf(InMemoryRunStore.class, runStore);
    }
}
