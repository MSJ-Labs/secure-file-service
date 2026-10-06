package com.msj.securefile.storage.domain.file;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;

import java.time.Instant;

/**
 * The persisted state of a file, as it is read back from storage, to rebuild the aggregate with
 * {@link SecureFile#reconstitute}. It is a plain carrier of what was stored: no rule is applied to it. The values that
 * can be absent (digest, failure reason, infection signature) are null, as they are in the row; the aggregate exposes
 * them as Optional.
 */
public record FileSnapshot(FileId id, OwnerId owner, String name, long declaredSize, FileStatus status, Sha256 sha256,
                           Instant uploadExpiresAt, UploadFailureReason failureReason, String infectionSignature,
                           Instant createdAt, Instant updatedAt, long version) {
}