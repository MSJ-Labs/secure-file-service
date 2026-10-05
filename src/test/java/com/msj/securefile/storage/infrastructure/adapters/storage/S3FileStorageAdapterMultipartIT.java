package com.msj.securefile.storage.infrastructure.adapters.storage;

import com.msj.securefile.storage.application.port.out.FileStorageException;
import com.msj.securefile.storage.application.port.out.StorageZone;
import com.msj.securefile.storage.application.port.out.StoredContent;
import com.msj.securefile.storage.application.port.out.UploadInterruptedException;
import com.msj.securefile.storage.application.port.out.UploadTooLargeException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import com.msj.securefile.support.LocalStackTestS3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The path of a body that does not fit in one part: S3 refuses parts under 5 MiB (except the last one), so the parts of
 * these tests are 5 MiB and the bodies a few times that.
 */
class S3FileStorageAdapterMultipartIT {

    private static final int MIB = 1024 * 1024;
    private static final FileId ID = FileId.of(42L);
    private static final S3StorageSettings SETTINGS = new S3StorageSettings(
            LocalStackTestS3.QUARANTINE_BUCKET, LocalStackTestS3.CLEAN_BUCKET, 5 * MIB);
    private static final long NO_LIMIT = Long.MAX_VALUE;

    private final S3Client s3 = LocalStackTestS3.client();
    private final S3FileStorageAdapter storage = new S3FileStorageAdapter(s3, SETTINGS);

    @BeforeEach
    void emptyTheBuckets() {
        LocalStackTestS3.emptyBuckets();
    }

    // The same bytes every run: a failure can be replayed.
    private static byte[] bytes(int size) {
        byte[] content = new byte[size];
        new Random(7).nextBytes(content);
        return content;
    }

    private static Sha256 sha256Of(byte[] content) {
        try {
            return Sha256.of(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] stored() {
        return s3.getObjectAsBytes(request -> request.bucket(LocalStackTestS3.QUARANTINE_BUCKET).key(ID.asString()))
                .asByteArray();
    }

    private boolean exists() {
        try {
            s3.headObject(request -> request.bucket(LocalStackTestS3.QUARANTINE_BUCKET).key(ID.asString()));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    // A multipart upload that was neither completed nor aborted keeps costing storage until it is cleaned.
    private boolean hasUnfinishedUploads() {
        return !s3.listMultipartUploads(request -> request.bucket(LocalStackTestS3.QUARANTINE_BUCKET)).uploads().isEmpty();
    }

    @Test
    void store_writesABodyOfSeveralPartsInFull() {
        byte[] body = bytes(12 * MIB);

        storage.store(StorageZone.QUARANTINE, ID, new ByteArrayInputStream(body), NO_LIMIT);

        assertThat(stored()).isEqualTo(body);
        assertThat(hasUnfinishedUploads()).isFalse();
    }

    @Test
    void store_reportsTheSizeAndTheDigestOfTheWholeBody() {
        byte[] body = bytes(12 * MIB);

        StoredContent result = storage.store(StorageZone.QUARANTINE, ID, new ByteArrayInputStream(body), NO_LIMIT);

        assertThat(result.size()).isEqualTo(12L * MIB);
        assertThat(result.digest()).isEqualTo(sha256Of(body));
    }

    @Test
    void store_handlesABodyThatIsAnExactMultipleOfThePartSize() {
        byte[] body = bytes(10 * MIB);

        StoredContent result = storage.store(StorageZone.QUARANTINE, ID, new ByteArrayInputStream(body), NO_LIMIT);

        // No empty trailing part: S3 would refuse it.
        assertThat(stored()).isEqualTo(body);
        assertThat(result.size()).isEqualTo(10L * MIB);
    }

    @Test
    void store_handlesABodyOfExactlyOnePart() {
        byte[] body = bytes(5 * MIB);

        StoredContent result = storage.store(StorageZone.QUARANTINE, ID, new ByteArrayInputStream(body), NO_LIMIT);

        assertThat(stored()).isEqualTo(body);
        assertThat(result.digest()).isEqualTo(sha256Of(body));
    }

    @Test
    void store_stopsInTheMiddleOfAnUploadThatPassesTheLimitAndLeavesNothing() {
        assertThatThrownBy(() -> storage.store(StorageZone.QUARANTINE, ID,
                new ByteArrayInputStream(bytes(12 * MIB)), 7L * MIB))
                .isInstanceOf(UploadTooLargeException.class);

        assertThat(exists()).isFalse();
        assertThat(hasUnfinishedUploads()).isFalse();
    }

    @Test
    void store_reportsAnInterruptedUploadWhenTheBodyBreaksAfterTheFirstParts() {
        InputStream breaksAfterSixMib = new SequenceInputStream(
                new ByteArrayInputStream(bytes(6 * MIB)),
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("connection reset");
                    }
                });

        assertThatThrownBy(() -> storage.store(StorageZone.QUARANTINE, ID, breaksAfterSixMib, NO_LIMIT))
                .isInstanceOf(UploadInterruptedException.class);

        // The part already sent must not stay behind: the upload is aborted.
        assertThat(exists()).isFalse();
        assertThat(hasUnfinishedUploads()).isFalse();
    }

    @Test
    void store_reportsAStorageErrorWhenTheMultipartUploadCannotStart() {
        S3FileStorageAdapter withoutBucket = new S3FileStorageAdapter(s3,
                new S3StorageSettings("no-such-bucket", "no-such-bucket", 5 * MIB));

        assertThatThrownBy(() -> withoutBucket.store(StorageZone.QUARANTINE, ID,
                new ByteArrayInputStream(bytes(12 * MIB)), NO_LIMIT))
                .isInstanceOf(FileStorageException.class);
    }
}
