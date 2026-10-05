package com.msj.securefile.storage.application.port.out;

import com.msj.securefile.storage.domain.file.valueobject.FileId;

import java.io.InputStream;

/**
 * Output port to the binary storage. Content is always streamed, never loaded in memory. Failures surface as
 * FileStorageException.
 */
public interface FileStoragePort {

    // The caller closes the stream.
    InputStream open(StorageZone zone, FileId id);

    // Streams the body into the zone and reports what was actually written. It never reads more than maxBytes: a longer
    // body stops the write, nothing is kept, and UploadTooLargeException is thrown. Reading the body failing throws
    // UploadInterruptedException, writing it failing throws FileStorageException.
    StoredContent store(StorageZone zone, FileId id, InputStream content, long maxBytes);

    // Copies the content from QUARANTINE to CLEAN, leaving the quarantine untouched. Same key, same bytes: redoing it
    // after a crash is harmless, which is what lets a scan be retried.
    void promote(FileId id);

    // Deleting something that is already gone is not an error.
    void delete(StorageZone zone, FileId id);
}