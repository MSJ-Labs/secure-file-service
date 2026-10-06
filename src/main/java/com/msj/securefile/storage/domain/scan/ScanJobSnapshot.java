package com.msj.securefile.storage.domain.scan;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

import java.time.Instant;

/**
 * The persisted state of a scan job, as it is read back from storage, to rebuild the aggregate with
 * {@link ScanJob#reconstitute}. It is a plain carrier of what was stored: no rule is applied to it. The values that can
 * be absent (lease owner, lease expiry, last error) are null, as they are in the row; the aggregate exposes them as
 * Optional.
 */
public record ScanJobSnapshot(ScanJobId id, FileId fileId, ScanQueue queue, ScanJobState state, int attempts,
                              Instant nextAttemptAt, WorkerId leaseOwner, Instant leaseExpiresAt, String lastError,
                              Instant createdAt, Instant updatedAt, long version) {
}