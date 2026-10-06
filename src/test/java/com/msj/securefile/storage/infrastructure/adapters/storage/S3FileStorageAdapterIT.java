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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3FileStorageAdapterIT {

    private static final FileId ID = FileId.of(42L);
    // S3 refuses parts under 5 MiB, except the last one.
    private static final S3StorageSettings SETTINGS = new S3StorageSettings(
            LocalStackTestS3.QUARANTINE_BUCKET, LocalStackTestS3.CLEAN_BUCKET, 5 * 1024 * 1024);
    private static final String HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final S3Client s3 = LocalStackTestS3.client();
    private final S3FileStorageAdapter storage = new S3FileStorageAdapter(s3, SETTINGS);

    @BeforeEach
    void emptyTheBuckets() {
        LocalStackTestS3.emptyBuckets();
    }

    private static InputStream body(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] read(String bucket) {
        return s3.getObjectAsBytes(request -> request.bucket(bucket).key(ID.asString())).asByteArray();
    }

    private boolean exists(String bucket) {
        try {
            s3.headObject(request -> request.bucket(bucket).key(ID.asString()));
            return true;
        } catch (NoSuchKeyException _) {
            return false;
        }
    }

    @Test
    void store_writesTheBodyUnderTheFileIdInTheZoneBucket() {
        storage.store(StorageZone.QUARANTINE, ID, body("hello"), 100);

        assertThat(read(LocalStackTestS3.QUARANTINE_BUCKET)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(exists(LocalStackTestS3.CLEAN_BUCKET)).isFalse();
    }

    @Test
    void store_reportsTheSizeAndTheDigestItMeasuredWhileReading() {
        StoredContent stored = storage.store(StorageZone.QUARANTINE, ID, body("hello"), 100);

        assertThat(stored.size()).isEqualTo(5);
        assertThat(stored.digest()).isEqualTo(Sha256.of(HELLO_SHA256));
    }

    @Test
    void store_acceptsAnEmptyBody() {
        StoredContent stored = storage.store(StorageZone.QUARANTINE, ID, body(""), 100);

        assertThat(stored.size()).isZero();
        assertThat(stored.digest()).isEqualTo(Sha256.of(EMPTY_SHA256));
        assertThat(exists(LocalStackTestS3.QUARANTINE_BUCKET)).isTrue();
    }

    @Test
    void store_acceptsABodyOfExactlyTheLimit() {
        StoredContent stored = storage.store(StorageZone.QUARANTINE, ID, body("hello"), 5);

        assertThat(stored.size()).isEqualTo(5);
    }

    @Test
    void store_refusesABodyLongerThanTheLimitAndKeepsNothing() {
        InputStream tooLong = body("hello world");

        assertThatThrownBy(() -> storage.store(StorageZone.QUARANTINE, ID, tooLong, 5))
                .isInstanceOf(UploadTooLargeException.class);

        assertThat(exists(LocalStackTestS3.QUARANTINE_BUCKET)).isFalse();
    }

    @Test
    void store_reportsAnInterruptedUploadWhenReadingTheBodyFails() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };

        // Reading the body failed, which is the client's side: not a storage error.
        assertThatThrownBy(() -> storage.store(StorageZone.QUARANTINE, ID, broken, 100))
                .isInstanceOf(UploadInterruptedException.class);
        assertThat(exists(LocalStackTestS3.QUARANTINE_BUCKET)).isFalse();
    }

    @Test
    void store_reportsAStorageErrorWhenTheBucketCannotBeWritten() {
        S3FileStorageAdapter withoutBucket = new S3FileStorageAdapter(s3,
                new S3StorageSettings("no-such-bucket", "no-such-bucket", 5 * 1024 * 1024));

        InputStream content = body("hello");

        assertThatThrownBy(() -> withoutBucket.store(StorageZone.QUARANTINE, ID, content, 100))
                .isInstanceOf(FileStorageException.class);
    }

    @Test
    void open_streamsTheStoredContent() throws IOException {
        s3.putObject(request -> request.bucket(LocalStackTestS3.CLEAN_BUCKET).key(ID.asString()),
                RequestBody.fromString("hello"));

        try (InputStream content = storage.open(StorageZone.CLEAN, ID)) {
            assertThat(content.readAllBytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void open_reportsAStorageErrorForAMissingObject() {
        assertThatThrownBy(() -> storage.open(StorageZone.CLEAN, ID)).isInstanceOf(FileStorageException.class);
    }

    @Test
    void delete_removesTheObject() {
        storage.store(StorageZone.QUARANTINE, ID, body("hello"), 100);

        storage.delete(StorageZone.QUARANTINE, ID);

        assertThat(exists(LocalStackTestS3.QUARANTINE_BUCKET)).isFalse();
    }

    @Test
    void delete_doesNotFailWhenTheObjectIsAlreadyGone() {
        assertThatCode(() -> storage.delete(StorageZone.QUARANTINE, ID)).doesNotThrowAnyException();
    }

    @Test
    void promote_copiesTheQuarantinedContentToTheCleanZoneAndLeavesTheQuarantineAlone() {
        storage.store(StorageZone.QUARANTINE, ID, body("hello"), 100);

        storage.promote(ID);

        assertThat(read(LocalStackTestS3.CLEAN_BUCKET)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        // The quarantine stays until the verdict is recorded: a crash before it must find the content to scan again.
        assertThat(exists(LocalStackTestS3.QUARANTINE_BUCKET)).isTrue();
    }

    @Test
    void promote_canBeRedoneAfterACrash() {
        storage.store(StorageZone.QUARANTINE, ID, body("hello"), 100);
        storage.promote(ID);

        assertThatCode(() -> storage.promote(ID)).doesNotThrowAnyException();
        assertThat(read(LocalStackTestS3.CLEAN_BUCKET)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void promote_reportsAStorageErrorWhenThereIsNothingToCopy() {
        assertThatThrownBy(() -> storage.promote(ID)).isInstanceOf(FileStorageException.class);
    }
}
