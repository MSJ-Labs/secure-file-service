package com.msj.securefile.config;

import com.msj.securefile.storage.application.command.claimscan.ClaimScanCommandHandler;
import com.msj.securefile.storage.application.command.processscan.ProcessScanCommandHandler;
import com.msj.securefile.storage.application.command.reapuploads.ReapExpiredUploadsCommand;
import com.msj.securefile.storage.application.command.reapuploads.ReapExpiredUploadsCommandHandler;
import com.msj.securefile.storage.application.command.reclaimleases.ReclaimExpiredLeasesCommand;
import com.msj.securefile.storage.application.command.reclaimleases.ReclaimExpiredLeasesCommandHandler;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import com.msj.securefile.storage.infrastructure.worker.PollingPolicy;
import com.msj.securefile.storage.infrastructure.worker.ScanQueueLoop;
import com.msj.securefile.storage.infrastructure.worker.ScanQueueWorker;
import com.msj.securefile.storage.infrastructure.worker.ScanWorkerRuntime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts the scan workers inside the application, one polling loop per queue plus the maintenance loop, unless
 * app.worker.enabled is false (the API tests, or an instance that only serves requests). The numbers are working
 * hypotheses to calibrate with a load test.
 */
@Configuration
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
@EnableConfigurationProperties(ScanWorkerConfig.WorkerProperties.class)
public class ScanWorkerConfig {

    @ConfigurationProperties(prefix = "app.worker")
    public record WorkerProperties(int smallSlots, int largeSlots, Duration lease, Duration pollMinDelay,
                                   Duration pollMaxDelay, Duration scannerUnavailableDelay,
                                   Duration maintenanceInterval, int maintenanceBatchSize) {
    }

    @Bean
    ScanWorkerRuntime scanWorkerRuntime(WorkerProperties properties, ClaimScanCommandHandler claimScan,
                                        ProcessScanCommandHandler processScan,
                                        ReclaimExpiredLeasesCommandHandler reclaimLeases,
                                        ReapExpiredUploadsCommandHandler reapUploads) {
        // One executor per queue only so the threads of its scans carry the queue in their name (scan-small-1, ...);
        // it limits nothing, the slots of the queue do.
        var smallExecutor = scanExecutor("scan-small-");
        var largeExecutor = scanExecutor("scan-large-");
        var polling = new PollingPolicy(properties.pollMinDelay(), properties.pollMaxDelay());

        Map<String, ScanQueueLoop> loops = Map.of(
                "small", loopFor(ScanQueue.SMALL, properties.smallSlots(), properties, polling, claimScan, processScan,
                        smallExecutor),
                "large", loopFor(ScanQueue.LARGE, properties.largeSlots(), properties, polling, claimScan, processScan,
                        largeExecutor));

        return new ScanWorkerRuntime(loops, List.of(smallExecutor, largeExecutor), properties.maintenanceInterval(), () -> {
            reclaimLeases.handle(new ReclaimExpiredLeasesCommand(properties.maintenanceBatchSize()));
            reapUploads.handle(new ReapExpiredUploadsCommand(properties.maintenanceBatchSize()));
        });
    }

    private static ExecutorService scanExecutor(String threadNamePrefix) {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(threadNamePrefix, 1).factory());
    }

    private static ScanQueueLoop loopFor(ScanQueue queue, int slots, WorkerProperties properties,
                                         PollingPolicy polling, ClaimScanCommandHandler claimScan,
                                         ProcessScanCommandHandler processScan,
                                         ExecutorService executor) {
        // The name is what a lease is compared against: unique per process and queue, readable in the audit trail.
        WorkerId worker = WorkerId.of(hostName() + "-" + queue.name().toLowerCase() + "-"
                + UUID.randomUUID().toString().substring(0, 8));
        var queueWorker = new ScanQueueWorker(queue, worker, properties.lease(), slots,
                properties.scannerUnavailableDelay(), polling, claimScan, processScan, executor);
        return new ScanQueueLoop(queueWorker, Thread::sleep);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException _) {
            return "worker";
        }
    }
}
