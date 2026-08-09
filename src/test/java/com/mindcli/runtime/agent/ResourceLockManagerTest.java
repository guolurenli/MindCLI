package com.mindcli.runtime.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceLockManagerTest {

    @Test
    void sharedLocksCanOverlap() throws Exception {
        ResourceLockManager locks = new ResourceLockManager();
        ResourceKey key = new ResourceKey(ResourceScope.FILE, "a.txt", ResourceAccess.SHARED);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (ResourceLockManager.LockLease ignored = locks.acquireAll(List.of(key))) {
            Future<Boolean> acquired = executor.submit(() -> {
                try (ResourceLockManager.LockLease ignoredNested = locks.acquireAll(List.of(key))) {
                    return true;
                }
            });

            assertTrue(acquired.get(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exclusiveLockWaitsForSharedLockToRelease() throws Exception {
        ResourceLockManager locks = new ResourceLockManager();
        ResourceKey shared = new ResourceKey(ResourceScope.WORKSPACE, "workspace", ResourceAccess.SHARED);
        ResourceKey exclusive = new ResourceKey(ResourceScope.WORKSPACE, "workspace", ResourceAccess.EXCLUSIVE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> acquired;

        try (ResourceLockManager.LockLease ignored = locks.acquireAll(List.of(shared))) {
            acquired = executor.submit(() -> {
                try (ResourceLockManager.LockLease ignoredNested = locks.acquireAll(List.of(exclusive))) {
                    return true;
                }
            });

            Thread.sleep(100);
            assertFalse(acquired.isDone());
        }
        assertTrue(acquired.get(1, TimeUnit.SECONDS));
        executor.shutdownNow();
    }

    @Test
    void sortedAcquisitionAvoidsDeadlockForReversedKeys() throws Exception {
        ResourceLockManager locks = new ResourceLockManager();
        ResourceKey a = new ResourceKey(ResourceScope.FILE, "a.txt", ResourceAccess.EXCLUSIVE);
        ResourceKey b = new ResourceKey(ResourceScope.FILE, "b.txt", ResourceAccess.EXCLUSIVE);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);

        Future<Boolean> first = executor.submit(() -> acquireAfterReady(locks, ready, List.of(a, b)));
        Future<Boolean> second = executor.submit(() -> acquireAfterReady(locks, ready, List.of(b, a)));

        assertTrue(first.get(3, TimeUnit.SECONDS));
        assertTrue(second.get(3, TimeUnit.SECONDS));
        executor.shutdownNow();
    }

    private static boolean acquireAfterReady(ResourceLockManager locks, CountDownLatch ready,
                                             List<ResourceKey> keys) throws Exception {
        ready.countDown();
        assertTrue(ready.await(1, TimeUnit.SECONDS));
        try (ResourceLockManager.LockLease ignored = locks.acquireAll(keys)) {
            Thread.sleep(50);
            return true;
        }
    }
}
