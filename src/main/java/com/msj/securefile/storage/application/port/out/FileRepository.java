package com.msj.securefile.storage.application.port.out;

import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Output port, owned by the application, implemented by the persistence adapter.
 */
public interface FileRepository {

    // Writes the state and the audit events of the changes in the same transaction; the actor is recorded with each event.
    void save(SecureFile file, Actor actor);

    // The same for several files at once: written in batches, one statement for the new ones, one batch for the
    // changed ones, one for all the events. One stale copy refuses the whole call, the caller's transaction undoes it.
    void saveAll(Collection<SecureFile> files, Actor actor);

    // By id and owner on purpose: there is no lookup by id alone, so no handler can forget the ownership check.
    Optional<SecureFile> findByIdAndOwner(FileId id, OwnerId owner);

    // Every file of the owner, newest first.
    List<SecureFile> findAllByOwner(OwnerId owner);

    // For the scan worker, which acts on behalf of the system and has no caller. Never use it for a user request.
    Optional<SecureFile> findByIdForScan(FileId id);

    // The same for the files of a batch of jobs, in one query. Ids that match no file are simply absent from the result.
    List<SecureFile> findAllByIdForScan(Collection<FileId> ids);

    // System query for the reaper, not tied to a caller: UPLOADING files whose deadline is past, at most `limit`.
    List<SecureFile> findExpiredUploads(Instant now, int limit);
}