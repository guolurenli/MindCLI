package com.mindcli.runtime.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeApiTurnSchedulerTest {

    @Test
    void serializesTurnsForTheSameThread() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        RuntimeApiTurnScheduler scheduler = new RuntimeApiTurnScheduler(executor);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        List<String> prompts = Collections.synchronizedList(new ArrayList<>());

        try {
            var first = scheduler.submit("thread_1", () -> run("one", firstStarted, releaseFirst, active,
                    maxActive, prompts));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            var second = scheduler.submit("thread_1", () -> run("two", null, null, active, maxActive, prompts));

            assertTrue(!second.isDone(), "same-thread second turn must wait for the first turn");
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertEquals(1, maxActive.get());
            assertEquals(List.of("one", "two"), prompts);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void differentThreadsCanRunConcurrently() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        RuntimeApiTurnScheduler scheduler = new RuntimeApiTurnScheduler(executor);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        try {
            var first = scheduler.submit("thread_1", () -> run("one", started, release, active, maxActive, null));
            var second = scheduler.submit("thread_2", () -> run("two", started, release, active, maxActive, null));

            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(maxActive.get() >= 2);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void run(String prompt,
                            CountDownLatch started,
                            CountDownLatch release,
                            AtomicInteger active,
                            AtomicInteger maxActive,
                            List<String> prompts) {
        int running = active.incrementAndGet();
        maxActive.accumulateAndGet(running, Math::max);
        if (prompts != null) {
            prompts.add(prompt);
        }
        try {
            if (started != null) {
                started.countDown();
            }
            if (release != null && !await(release)) {
                throw new IllegalStateException("turn was not released");
            }
        } finally {
            active.decrementAndGet();
        }
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
