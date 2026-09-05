package com.mindcli.runtime.run.dispatch;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class ResourceLockManager {
    private final ConcurrentHashMap<ResourceIdentity, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public LockLease acquireAll(List<ResourceKey> keys) {
        try {
            return acquireAllInterruptibly(keys);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for resource lock", e);
        }
    }

    public LockLease acquireAllInterruptibly(List<ResourceKey> keys) throws InterruptedException {
        List<LockRequest> requests = collapse(keys);
        if (requests.isEmpty()) {
            return () -> {
            };
        }

        List<Lock> acquired = new ArrayList<>();
        try {
            for (LockRequest request : requests) {
                ReentrantReadWriteLock rwLock = locks.computeIfAbsent(
                        request.identity(),
                        ignored -> new ReentrantReadWriteLock(true));
                Lock lock = request.access() == ResourceAccess.EXCLUSIVE
                        ? rwLock.writeLock()
                        : rwLock.readLock();
                lock.lockInterruptibly();
                acquired.add(lock);
            }
            return new AcquiredLockLease(acquired);
        } catch (InterruptedException | RuntimeException e) {
            releaseReverse(acquired);
            throw e;
        }
    }

    private static List<LockRequest> collapse(List<ResourceKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        Map<ResourceIdentity, ResourceAccess> accessByIdentity = new LinkedHashMap<>();
        for (ResourceKey key : keys) {
            if (key == null) {
                continue;
            }
            ResourceIdentity identity = ResourceIdentity.from(key);
            ResourceAccess existing = accessByIdentity.get(identity);
            if (existing == ResourceAccess.EXCLUSIVE || key.access() == ResourceAccess.EXCLUSIVE) {
                accessByIdentity.put(identity, ResourceAccess.EXCLUSIVE);
            } else {
                accessByIdentity.put(identity, ResourceAccess.SHARED);
            }
        }
        return accessByIdentity.entrySet().stream()
                .map(entry -> new LockRequest(entry.getKey(), entry.getValue()))
                .sorted()
                .toList();
    }

    private static void releaseReverse(List<Lock> acquired) {
        for (int i = acquired.size() - 1; i >= 0; i--) {
            acquired.get(i).unlock();
        }
    }

    @FunctionalInterface
    public interface LockLease extends AutoCloseable {
        @Override
        void close();
    }

    private record AcquiredLockLease(List<Lock> acquired, AtomicBoolean closed) implements LockLease {
        private AcquiredLockLease(List<Lock> acquired) {
            this(List.copyOf(acquired), new AtomicBoolean(false));
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                releaseReverse(acquired);
            }
        }
    }

    private record LockRequest(ResourceIdentity identity, ResourceAccess access)
            implements Comparable<LockRequest> {
        private LockRequest {
            identity = Objects.requireNonNull(identity, "identity");
            access = Objects.requireNonNull(access, "access");
        }

        @Override
        public int compareTo(LockRequest other) {
            return identity.compareTo(other.identity);
        }
    }

    private record ResourceIdentity(ResourceScope scope, String name)
            implements Comparable<ResourceIdentity> {
        private ResourceIdentity {
            scope = Objects.requireNonNull(scope, "scope");
            name = name == null || name.isBlank() ? "<default>" : name;
        }

        private static ResourceIdentity from(ResourceKey key) {
            return new ResourceIdentity(key.scope(), key.name());
        }

        @Override
        public int compareTo(ResourceIdentity other) {
            int scopeCompare = scope.name().compareTo(other.scope.name());
            if (scopeCompare != 0) {
                return scopeCompare;
            }
            return name.compareTo(other.name);
        }
    }
}
