package com.mindcli.runtime.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeThreadStoreConcurrencyTest {

    private static final int EXPECTED_BUSY_TIMEOUT_MILLIS = 5_000;

    @TempDir
    Path tempDir;

    @Test
    void concurrentConnectionsCanAppendEventsWithoutDatabaseLockedFailures() throws Exception {
        Path dbPath = tempDir.resolve("runtime.db");
        try (RuntimeThreadStore firstStore = new RuntimeThreadStore(dbPath);
             RuntimeThreadStore secondStore = new RuntimeThreadStore(dbPath)) {
            String threadId = firstStore.createThread();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = executor.submit(() -> appendMany(firstStore, threadId, "first"));
                Future<?> second = executor.submit(() -> appendMany(secondStore, threadId, "second"));
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(101, firstStore.events(threadId, 0).size());
        }
    }

    @Test
    void waitsForAnExternalWriteTransactionBeforeAppending() throws Exception {
        Path dbPath = tempDir.resolve("busy-timeout.db");
        try (RuntimeThreadStore store = new RuntimeThreadStore(dbPath)) {
            String threadId = store.createThread();
            try (Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                blocker.setAutoCommit(false);
                // The table is created dynamically by RuntimeThreadStore in this @TempDir database.
                //noinspection SqlResolve
                try (PreparedStatement statement = blocker.prepareStatement(
                        "INSERT INTO runtime_events (thread_id, type, data, created_at) VALUES (?, ?, ?, datetime('now'))")) {
                    statement.setString(1, threadId);
                    statement.setString(2, "external.lock");
                    statement.setString(3, "{}");
                    statement.executeUpdate();
                }

                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<?> append = executor.submit(() -> store.appendEvent(threadId, "after.lock", "{}"));
                    Thread.sleep(250);
                    assertTrue(!append.isDone(), "append should wait for the external SQLite write transaction");
                    blocker.commit();
                    append.get(10, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                }
            }
        }
    }

    @Test
    void configuresAnExplicitSqliteBusyTimeout() throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("busy-timeout-config.db"))) {
            assertEquals(EXPECTED_BUSY_TIMEOUT_MILLIS, store.busyTimeoutMillis());
        }
    }

    private static void appendMany(RuntimeThreadStore store, String threadId, String source) {
        for (int i = 0; i < 50; i++) {
            store.appendEvent(threadId, "turn.progress", "{\"source\":\"" + source + "\",\"i\":" + i + "}");
        }
    }
}
