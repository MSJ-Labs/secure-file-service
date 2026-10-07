package com.msj.securefile.storage.infrastructure.worker;

import com.msj.securefile.storage.application.command.claimscan.ClaimScanCommand;
import com.msj.securefile.storage.application.command.claimscan.ClaimScanCommandHandler;
import com.msj.securefile.storage.application.command.processscan.ProcessScanCommand;
import com.msj.securefile.storage.application.command.processscan.ProcessScanCommandHandler;
import com.msj.securefile.storage.application.result.ClaimedScan;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class ScanQueueWorkerTest {

    private static final WorkerId WORKER = WorkerId.of("worker-small-1");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration UNAVAILABLE_DELAY = Duration.ofSeconds(60);
    private static final PollingPolicy POLLING = new PollingPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30));
    private static final int SLOTS = 2;
    private static final long WAIT_MILLIS = 2_000;

    @Mock
    private ClaimScanCommandHandler claimScan;
    @Mock
    private ProcessScanCommandHandler processScan;
    @Captor
    private ArgumentCaptor<ClaimScanCommand> claimCaptor;
    @Captor
    private ArgumentCaptor<ProcessScanCommand> processCaptor;

    private ExecutorService executor;
    private ScanQueueWorker worker;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        worker = new ScanQueueWorker(ScanQueue.SMALL, WORKER, LEASE, SLOTS, UNAVAILABLE_DELAY, POLLING,
                claimScan, processScan, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void poll_claimsForItsQueueAndItsWorkerUpToTheFreeSlots() {
        when(claimScan.handle(any())).thenReturn(List.of());

        worker.poll(Duration.ZERO);

        verify(claimScan).handle(claimCaptor.capture());
        assertThat(claimCaptor.getValue()).isEqualTo(new ClaimScanCommand(ScanQueue.SMALL, WORKER, LEASE, SLOTS));
    }

    @Test
    void poll_handsEachClaimedJobToTheScanProcess() {
        ClaimedScan first = claimed(1);
        ClaimedScan second = claimed(2);
        when(claimScan.handle(any())).thenReturn(List.of(first, second));

        worker.poll(Duration.ZERO);

        verify(processScan, timeout(WAIT_MILLIS).times(2)).handle(processCaptor.capture());
        assertThat(processCaptor.getAllValues()).containsExactlyInAnyOrder(
                new ProcessScanCommand(first.jobId(), first.fileId(), WORKER, UNAVAILABLE_DELAY),
                new ProcessScanCommand(second.jobId(), second.fileId(), WORKER, UNAVAILABLE_DELAY));
    }

    @Test
    void poll_returnsNoDelayWhenWorkWasFound() {
        when(claimScan.handle(any())).thenReturn(List.of(claimed(1)));

        // The queue may hold more: it is drained without waiting.
        assertThat(worker.poll(Duration.ofSeconds(8))).isZero();
    }

    @Test
    void poll_backsOffWhenTheQueueIsEmpty() {
        when(claimScan.handle(any())).thenReturn(List.of());

        Duration first = worker.poll(Duration.ZERO);
        Duration second = worker.poll(first);

        assertThat(first).isEqualTo(Duration.ofSeconds(1));
        assertThat(second).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void poll_doesNotClaimWhileEverySlotIsBusy() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        holdScansUntil(started, release);
        when(claimScan.handle(any())).thenReturn(List.of(claimed(1), claimed(2)));
        worker.poll(Duration.ZERO);
        awaitStarted(started);

        Duration delay = worker.poll(Duration.ZERO);

        // A lease runs from the claim: a job taken with no slot to run it would see its lease expire while it waits.
        verify(claimScan, times(1)).handle(any());
        assertThat(worker.freeSlots()).isZero();
        assertThat(delay).isEqualTo(Duration.ofSeconds(1));
        release.countDown();
    }

    @Test
    void poll_claimsOnlyTheSlotsThatAreFree() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdScansUntil(started, release);
        when(claimScan.handle(any())).thenReturn(List.of(claimed(1)), List.of());
        worker.poll(Duration.ZERO);
        awaitStarted(started);

        worker.poll(Duration.ZERO);

        verify(claimScan, times(2)).handle(claimCaptor.capture());
        assertThat(claimCaptor.getAllValues().get(1).limit()).isEqualTo(SLOTS - 1);
        release.countDown();
    }

    @Test
    void aSlotIsFreedWhenTheScanEnds() {
        when(claimScan.handle(any())).thenReturn(List.of(claimed(1), claimed(2)));

        worker.poll(Duration.ZERO);

        awaitFreeSlots(SLOTS);
    }

    @Test
    void aSlotIsFreedWhenTheScanProcessThrows() {
        ClaimedScan job = claimed(1);
        when(claimScan.handle(any())).thenReturn(List.of(job));
        doThrow(new IllegalStateException("lease lost")).when(processScan).handle(any());

        worker.poll(Duration.ZERO);

        // One sick job must neither leak its slot nor stop the worker.
        awaitFreeSlots(SLOTS);
    }

    @Test
    void poll_survivesAFailedClaimAndBacksOff() {
        when(claimScan.handle(any())).thenThrow(new IllegalStateException("database down"));

        Duration delay = worker.poll(Duration.ZERO);

        assertThat(delay).isEqualTo(Duration.ofSeconds(1));
        verify(processScan, never()).handle(any());
    }

    @Test
    void poll_givesTheSlotBackWhenTheExecutorRefusesTheScan() {
        Executor refusing = task -> {
            throw new RejectedExecutionException("shut down");
        };
        ScanQueueWorker refused = new ScanQueueWorker(ScanQueue.SMALL, WORKER, LEASE, SLOTS, UNAVAILABLE_DELAY, POLLING,
                claimScan, processScan, refusing);
        when(claimScan.handle(any())).thenReturn(List.of(claimed(1)));

        refused.poll(Duration.ZERO);

        // The job keeps its lease until it expires and the reaper hands it back: the slot must not stay lost.
        assertThat(refused.freeSlots()).isEqualTo(SLOTS);
        verify(processScan, never()).handle(any());
    }

    @Test
    void poll_givesTheSlotsBackWhenTheClaimFails() {
        when(claimScan.handle(any())).thenThrow(new IllegalStateException("database down"));

        worker.poll(Duration.ZERO);

        assertThat(worker.freeSlots()).isEqualTo(SLOTS);
    }

    @Test
    void constructor_refusesAWorkerWithoutSlot() {
        assertThatThrownBy(() -> new ScanQueueWorker(ScanQueue.SMALL, WORKER, LEASE, 0, UNAVAILABLE_DELAY, POLLING,
                claimScan, processScan, executor)).isInstanceOf(IllegalArgumentException.class);
    }

    // The scans run on other threads: the test must wait until they have really started, or it could end before
    // they call the mock and Mockito would report the stubbing as unused.
    private void holdScansUntil(CountDownLatch started, CountDownLatch release) {
        doAnswer(invocation -> {
            started.countDown();
            release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            return null;
        }).when(processScan).handle(any());
    }

    private static void awaitStarted(CountDownLatch started) throws InterruptedException {
        assertThat(started.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
    }

    private void awaitFreeSlots(int expected) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (worker.freeSlots() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(worker.freeSlots()).isEqualTo(expected);
    }

    private static ClaimedScan claimed(long id) {
        return new ClaimedScan(ScanJobId.of(id), FileId.of(id + 100), Instant.parse("2026-10-06T12:00:30Z"));
    }
}
