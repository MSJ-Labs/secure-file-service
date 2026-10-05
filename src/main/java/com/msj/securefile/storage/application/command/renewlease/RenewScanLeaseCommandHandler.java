package com.msj.securefile.storage.application.command.renewlease;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Heartbeat of a running scan: keeps the lease alive so a dead worker is detected within the lease duration, whatever
 * the file size.
 */
@Service
@RequiredArgsConstructor
public class RenewScanLeaseCommandHandler {

    private final ScanJobRepository scanJobRepository;
    private final Clock clock;

    @Transactional
    public void handle(RenewScanLeaseCommand command) {
        // A missing job and a lost lease mean the same to the worker: it must stop.
        ScanJob job = scanJobRepository.findById(command.jobId()).orElseThrow(LeaseLostException::new);

        job.renew(command.worker(), clock.instant(), command.lease());

        scanJobRepository.save(job, new Actor.Worker(command.worker()));
    }
}