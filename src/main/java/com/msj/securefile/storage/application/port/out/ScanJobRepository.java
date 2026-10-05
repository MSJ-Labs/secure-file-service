package com.msj.securefile.storage.application.port.out;

import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Output port, owned by the application, implemented by the persistence adapter.
 */
public interface ScanJobRepository {

    // Writes the state and the audit events of the changes in the same transaction; the actor is recorded with each event.
    void save(ScanJob job, Actor actor);

    // The same for several jobs at once, written in batches. One stale copy refuses the whole call.
    void saveAll(Collection<ScanJob> jobs, Actor actor);

    // Writes the new lease expiry of a job the caller still holds. A heartbeat is no transition: it records no event and
    // leaves the version alone, which is why it is not a save. Throws LeaseLostException when the lease is no longer
    // held by the owner of the job, so a worker that was replaced learns it must stop.
    void renewLease(ScanJob job);

    // The oldest PENDING jobs of the queue that are due, at most `limit`, oldest first. The adapter locks the rows
    // (SKIP LOCKED) until the end of the transaction, so two workers never get the same job. The lock is only the one of
    // the short claim transaction: what protects a job during its scan is its lease, not a database lock.
    List<ScanJob> findNextDue(ScanQueue queue, Instant now, int limit);

    // For the worker that holds (or believes it holds) the lease: ownership is checked by the job itself.
    Optional<ScanJob> findById(ScanJobId id);

    // LEASED jobs whose lease is past, at most `limit`. The adapter skips rows locked by another reaper.
    List<ScanJob> findExpiredLeases(Instant now, int limit);
}