package com.msj.securefile.storage.application.port.out;

import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Output port, owned by the application, implemented by the persistence adapter.
 */
public interface ScanJobRepository {

    void save(ScanJob job);

    // The oldest PENDING job of the queue that is due. The adapter locks the row (SKIP LOCKED) until the end of the
    // transaction, so two workers never get the same job.
    Optional<ScanJob> findNextDue(ScanQueue queue, Instant now);

    // For the worker that holds (or believes it holds) the lease: ownership is checked by the job itself.
    Optional<ScanJob> findById(ScanJobId id);

    // LEASED jobs whose lease is past, at most `limit`. The adapter skips rows locked by another reaper.
    List<ScanJob> findExpiredLeases(Instant now, int limit);
}