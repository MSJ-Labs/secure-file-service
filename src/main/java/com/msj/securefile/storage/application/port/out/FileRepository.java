package com.msj.securefile.storage.application.port.out;

import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Output port, owned by the application, implemented by the persistence adapter.
 */
public interface FileRepository {

    // Writes the state and the audit events of the changes in the same transaction; the actor is recorded with each event.
    void save(SecureFile file, Actor actor);

    // By id and owner on purpose: there is no lookup by id alone, so no handler can forget the ownership check.
    Optional<SecureFile> findByIdAndOwner(FileId id, OwnerId owner);

    // For the scan worker, which acts on behalf of the system and has no caller. Never use it for a user request.
    Optional<SecureFile> findByIdForScan(FileId id);

    // System query for the reaper, not tied to a caller: UPLOADING files whose deadline is past, at most `limit`.
    List<SecureFile> findExpiredUploads(Instant now, int limit);
}