package com.msj.securefile.storage.infrastructure.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Runs the queue loops and the maintenance loop on virtual threads for the life of the application. At shutdown the
 * threads are interrupted: a scan cut short keeps its lease until it expires, then the maintenance of another run (or
 * of this instance after a restart) hands the job back. A graceful release is a possible improvement.
 */
@Slf4j
public class ScanWorkerRuntime implements SmartLifecycle {

    private final Map<String, ScanQueueLoop> loops;
    private final List<ExecutorService> scanExecutors;
    private final Duration maintenanceInterval;
    private final Runnable maintenance;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    // The names of the loops only serve to name their threads, so a thread dump says which queue a thread polls.
    public ScanWorkerRuntime(Map<String, ScanQueueLoop> loops, List<ExecutorService> scanExecutors,
                             Duration maintenanceInterval, Runnable maintenance) {
        this.loops = loops;
        this.scanExecutors = scanExecutors;
        this.maintenanceInterval = maintenanceInterval;
        this.maintenance = maintenance;
    }

    @Override
    public synchronized void start() {
        // Before the threads: the maintenance loop tests this flag on its first line.
        running = true;
        loops.forEach((name, loop) -> threads.add(Thread.ofVirtual().name("scan-loop-" + name).start(loop::run)));
        threads.add(Thread.ofVirtual().name("scan-maintenance").start(this::runMaintenance));
    }

    @Override
    public synchronized void stop() {
        running = false;
        loops.values().forEach(ScanQueueLoop::stop);
        threads.forEach(Thread::interrupt);
        scanExecutors.forEach(ExecutorService::shutdownNow);
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runMaintenance() {
        while (running) {
            try {
                maintenance.run();
            } catch (RuntimeException e) {
                // A failing round (database down) is retried at the next interval, it must not end the loop.
                log.error("Scan maintenance failed", e);
            }
            try {
                Thread.sleep(maintenanceInterval);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
