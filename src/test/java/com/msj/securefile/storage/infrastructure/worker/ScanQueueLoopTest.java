package com.msj.securefile.storage.infrastructure.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanQueueLoopTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final Duration TWO_SECONDS = Duration.ofSeconds(2);

    @Mock
    private ScanQueueWorker worker;

    private final List<Duration> sleeps = new ArrayList<>();
    private ScanQueueLoop loop;
    // Lets a test stop the loop from inside its own sleep, so runs are deterministic and use no real time.
    private int stopAfterSleeps;

    @BeforeEach
    void setUp() {
        stopAfterSleeps = Integer.MAX_VALUE;
        loop = new ScanQueueLoop(worker, delay -> {
            sleeps.add(delay);
            if (sleeps.size() >= stopAfterSleeps) loop.stop();
        });
    }

    @Test
    void run_pollsAgainAfterTheDelayTheWorkerAsked() {
        stopAfterSleeps = 2;
        when(worker.poll(Duration.ZERO)).thenReturn(ONE_SECOND);
        when(worker.poll(ONE_SECOND)).thenReturn(TWO_SECONDS);

        loop.run();

        // Each poll receives the delay it just waited, which is what makes the backoff grow.
        assertThat(sleeps).containsExactly(ONE_SECOND, TWO_SECONDS);
        verify(worker).poll(Duration.ZERO);
        verify(worker).poll(ONE_SECOND);
    }

    @Test
    void run_doesNotSleepWhenThereIsNothingToWaitFor() {
        stopAfterSleeps = 1;
        when(worker.poll(Duration.ZERO)).thenReturn(Duration.ZERO, Duration.ZERO, ONE_SECOND);

        loop.run();

        // The queue is drained back to back: only the final wait sleeps.
        assertThat(sleeps).containsExactly(ONE_SECOND);
    }

    @Test
    void stop_endsTheLoopAfterTheCurrentPoll() {
        stopAfterSleeps = 1;
        when(worker.poll(any())).thenReturn(ONE_SECOND);

        loop.run();

        verify(worker).poll(any());
    }

    @Test
    void run_doesNotSleepWhenItWasStoppedDuringThePoll() {
        when(worker.poll(any())).thenAnswer(invocation -> {
            loop.stop();
            return ONE_SECOND;
        });

        loop.run();

        // Nothing to wait for once the application is shutting down.
        assertThat(sleeps).isEmpty();
    }

    @Test
    void run_doesNothingWhenItWasStoppedBeforeStarting() {
        loop.stop();

        loop.run();

        verify(worker, never()).poll(any());
    }

    @Test
    void run_endsWhenTheThreadIsInterruptedWhileSleeping() {
        when(worker.poll(any())).thenReturn(ONE_SECOND);
        ScanQueueLoop interrupted = new ScanQueueLoop(worker, delay -> {
            throw new InterruptedException();
        });

        interrupted.run();

        // The interruption is the shutdown signal: no further poll, and the flag is kept for the owner of the thread.
        verify(worker).poll(any());
        assertThat(Thread.interrupted()).isTrue();
    }
}
