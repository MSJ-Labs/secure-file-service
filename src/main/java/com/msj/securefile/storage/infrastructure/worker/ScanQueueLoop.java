package com.msj.securefile.storage.infrastructure.worker;

import java.time.Duration;

/**
 * Polls one queue until it is stopped, waiting between two polls for the delay the worker asks. It runs on its own
 * thread, which its owner interrupts at shutdown: the interruption cuts the wait short instead of leaving the process
 * hanging for up to the maximum delay.
 */
public class ScanQueueLoop {

    /**
     * The wait is injected so tests run without real time.
     */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    private final ScanQueueWorker worker;
    private final Sleeper sleeper;
    // Written by the thread that shuts the application down, read by the loop's own thread.
    private volatile boolean stopped;

    public ScanQueueLoop(ScanQueueWorker worker, Sleeper sleeper) {
        this.worker = worker;
        this.sleeper = sleeper;
    }

    public void stop() {
        stopped = true;
    }

    public void run() {
        Duration delay = Duration.ZERO;
        while (!stopped) {
            delay = worker.poll(delay);
            if (stopped) return;
            if (!delay.isZero() && !pause(delay)) return;
        }
    }

    private boolean pause(Duration delay) {
        try {
            sleeper.sleep(delay);
            return true;
        } catch (InterruptedException _) {
            // The interruption is the shutdown signal: keep the flag for whoever owns the thread and leave.
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
