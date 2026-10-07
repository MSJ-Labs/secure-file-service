package com.msj.securefile.storage.infrastructure.worker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ScanWorkerRuntimeTest {

    private static final long WAIT_SECONDS = 2;
    private static final Duration INTERVAL = Duration.ofMillis(10);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();
    private ScanWorkerRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) runtime.stop();
        executor.shutdownNow();
    }

    private ScanQueueLoop loopThatRecordsItsThread(CountDownLatch ran) {
        ScanQueueLoop loop = mock(ScanQueueLoop.class);
        doAnswer(invocation -> {
            threadNames.add(Thread.currentThread().getName());
            ran.countDown();
            return null;
        }).when(loop).run();
        return loop;
    }

    @Test
    void start_runsEveryLoopOnItsOwnNamedThread() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(2);
        runtime = new ScanWorkerRuntime(
                Map.of("small", loopThatRecordsItsThread(ran), "large", loopThatRecordsItsThread(ran)),
                List.of(executor), INTERVAL, () -> { });

        runtime.start();

        assertThat(ran.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.isRunning()).isTrue();
        // The name tells which queue a thread polls, in a thread dump or a log line.
        assertThat(threadNames).contains("scan-loop-small", "scan-loop-large");
    }

    @Test
    void start_runsTheMaintenanceAtEveryInterval() throws InterruptedException {
        CountDownLatch rounds = new CountDownLatch(3);
        runtime = new ScanWorkerRuntime(Map.of(), List.of(executor), INTERVAL, () -> {
            threadNames.add(Thread.currentThread().getName());
            rounds.countDown();
        });

        runtime.start();

        assertThat(rounds.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(threadNames).contains("scan-maintenance");
    }

    @Test
    void maintenance_goesOnAfterARoundThatFails() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        runtime = new ScanWorkerRuntime(Map.of(), List.of(executor), INTERVAL, () -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("database down");
            recovered.countDown();
        });

        runtime.start();

        // A database that is down for one round must not end the loop for good.
        assertThat(recovered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void stop_stopsTheLoopsAndShutsTheExecutorsDown() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        ScanQueueLoop loop = loopThatRecordsItsThread(ran);
        runtime = new ScanWorkerRuntime(Map.of("small", loop), List.of(executor), INTERVAL, () -> { });
        runtime.start();
        assertThat(ran.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.stop();

        verify(loop).stop();
        assertThat(executor.isShutdown()).isTrue();
        assertThat(runtime.isRunning()).isFalse();
    }
}
