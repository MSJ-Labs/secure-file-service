package com.msj.securefile.storage.infrastructure.adapters.storage;

import com.msj.securefile.storage.application.port.out.FileStorageException;
import com.msj.securefile.storage.application.port.out.FileStoragePort;
import com.msj.securefile.storage.application.port.out.StorageZone;
import com.msj.securefile.storage.application.port.out.StoredContent;
import com.msj.securefile.storage.application.port.out.UploadInterruptedException;
import com.msj.securefile.storage.application.port.out.UploadTooLargeException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * The binary storage on S3. A zone is a bucket and an object is named by the id of its file, never by the name the user
 * gave it, so nothing a caller sends can steer the key. Every failure of the SDK is translated, the application never
 * sees its types.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3FileStorageAdapter implements FileStoragePort {

    private static final int READ_CHUNK_BYTES = 8 * 1024;

    private final S3Client s3;
    private final S3StorageSettings settings;

    @Override
    public InputStream open(StorageZone zone, FileId id) {
        try {
            return s3.getObject(request -> request.bucket(bucket(zone)).key(key(id)));
        } catch (SdkException e) {
            throw new FileStorageException(e);
        }
    }

    /**
     * Reads the body once, by blocks, computing its size and its digest on the way and never reading past maxBytes. A
     * body that fits in one part is written with a single request, a longer one as a multipart upload whose parts are
     * sent as soon as they are full. Only one part is held in memory, whatever the size of the body, and a part is only
     * sent once more data is known to follow, so the upload never ends on an empty part, which S3 refuses.
     */
    @Override
    public StoredContent store(StorageZone zone, FileId id, InputStream content, long maxBytes) {
        MessageDigest digest = newDigest();
        PartBuffer part = new PartBuffer(settings.partSizeBytes());
        MultipartUpload multipart = new MultipartUpload(bucket(zone), key(id));
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        long size = 0;

        try {
            int read;
            while ((read = content.read(chunk)) != -1) {
                size += read;
                if (size > maxBytes) {
                    throw new UploadTooLargeException();
                }
                digest.update(chunk, 0, read);

                int offset = 0;
                while (offset < read) {
                    if (part.isFull()) {
                        multipart.send(part);
                        part.clear();
                    }
                    int count = Math.min(part.free(), read - offset);
                    part.write(chunk, offset, count);
                    offset += count;
                }
            }
            finish(multipart, part, zone, id);
        } catch (IOException e) {
            // Reading the body failed: the client stopped sending, which is not a storage error.
            multipart.abortIfStarted();
            throw new UploadInterruptedException(e);
        } catch (SdkException e) {
            multipart.abortIfStarted();
            throw new FileStorageException(e);
        } catch (RuntimeException e) {
            multipart.abortIfStarted();
            throw e;
        }
        return new StoredContent(size, Sha256.of(HexFormat.of().formatHex(digest.digest())));
    }

    private void finish(MultipartUpload multipart, PartBuffer part, StorageZone zone, FileId id) {
        if (multipart.isStarted()) {
            multipart.send(part);
            multipart.complete();
        } else {
            s3.putObject(request -> request.bucket(bucket(zone)).key(key(id)),
                    RequestBody.fromInputStream(part.asStream(), part.length()));
        }
    }

    // A server-side copy: the bytes never pass through the application. Redoing it is harmless, it writes the same
    // object again.
    @Override
    public void promote(FileId id) {
        try {
            s3.copyObject(request -> request
                    .sourceBucket(settings.quarantineBucket()).sourceKey(key(id))
                    .destinationBucket(settings.cleanBucket()).destinationKey(key(id)));
        } catch (SdkException e) {
            throw new FileStorageException(e);
        }
    }

    // S3 answers a delete of a missing object as a success, so "already gone" is not an error here either.
    @Override
    public void delete(StorageZone zone, FileId id) {
        try {
            s3.deleteObject(request -> request.bucket(bucket(zone)).key(key(id)));
        } catch (SdkException e) {
            throw new FileStorageException(e);
        }
    }

    private String bucket(StorageZone zone) {
        return switch (zone) {
            case QUARANTINE -> settings.quarantineBucket();
            case CLEAN -> settings.cleanBucket();
        };
    }

    private static String key(FileId id) {
        return id.asString();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform has to provide SHA-256.
            throw new IllegalStateException(e);
        }
    }

    /**
     * The part being filled. It starts small and grows up to the part size, then is reused for every following part, so
     * a small body costs its own size and a large one never more than one part.
     */
    private static final class PartBuffer {

        private final int capacity;
        private byte[] data = new byte[READ_CHUNK_BYTES];
        private int length;

        PartBuffer(int capacity) {
            this.capacity = capacity;
        }

        boolean isFull() {
            return length == capacity;
        }

        int free() {
            return capacity - length;
        }

        int length() {
            return length;
        }

        void write(byte[] source, int offset, int count) {
            if (length + count > data.length) {
                data = Arrays.copyOf(data, (int) Math.min(capacity, Math.max(2L * data.length, length + count)));
            }
            System.arraycopy(source, offset, data, length, count);
            length += count;
        }

        InputStream asStream() {
            return new ByteArrayInputStream(data, 0, length);
        }

        void clear() {
            length = 0;
        }
    }

    /**
     * A multipart upload that only exists once a second part is needed. If anything goes wrong it is aborted: a part
     * sent and never completed keeps costing storage until something cleans it.
     */
    private final class MultipartUpload {

        private final String bucket;
        private final String key;
        private final List<CompletedPart> parts = new ArrayList<>();
        private String uploadId;

        MultipartUpload(String bucket, String key) {
            this.bucket = bucket;
            this.key = key;
        }

        boolean isStarted() {
            return uploadId != null;
        }

        void send(PartBuffer part) {
            if (!isStarted()) {
                uploadId = s3.createMultipartUpload(request -> request.bucket(bucket).key(key)).uploadId();
            }
            int number = parts.size() + 1;
            String eTag = s3.uploadPart(
                    request -> request.bucket(bucket).key(key).uploadId(uploadId).partNumber(number),
                    RequestBody.fromInputStream(part.asStream(), part.length())).eTag();
            parts.add(CompletedPart.builder().partNumber(number).eTag(eTag).build());
        }

        void complete() {
            s3.completeMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(upload -> upload.parts(parts)));
        }

        // Best effort: the error being reported matters more than the cleanup that follows it.
        void abortIfStarted() {
            if (!isStarted()) {
                return;
            }
            try {
                s3.abortMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(uploadId));
            } catch (SdkException e) {
                log.warn("Could not abort the multipart upload of {}", key, e);
            }
        }
    }
}
