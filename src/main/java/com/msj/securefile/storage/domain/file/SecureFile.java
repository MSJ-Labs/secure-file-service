package com.msj.securefile.storage.domain.file;

import com.msj.securefile.shared.domain.VersionedAggregateRoot;
import com.msj.securefile.storage.domain.file.event.*;
import com.msj.securefile.storage.domain.file.exception.FileNotDownloadableException;
import com.msj.securefile.storage.domain.file.exception.FileNotUploadableException;
import com.msj.securefile.storage.domain.file.exception.InvalidFileTransitionException;
import com.msj.securefile.storage.domain.file.exception.UploadSizeMismatchException;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.file.valueobject.Sha256;
import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

/**
 * File aggregate root: pure domain object. The single status drives the whole lifecycle, from the upload to the
 * scan verdict; the queue details of the scan live in the scan job.
 */
// Equal by identity, like every entity (see Entity): the fields below are state, not identity.
@SuppressWarnings("java:S2160")
@Getter
public class SecureFile extends VersionedAggregateRoot<FileId> {

    // Matches the VARCHAR(255) column: fail in the domain rather than in the database.
    private static final int MAX_NAME_LENGTH = 255;

    private final OwnerId owner;
    private final String name;
    private final long declaredSize;
    private FileStatus status;
    // Unknown until the whole body has been read.
    private Sha256 sha256;
    // Past this instant a stuck upload may be failed by the reaper.
    private final Instant uploadExpiresAt;
    // Set only when the upload failed.
    private UploadFailureReason failureReason;
    // Set only when the scan found a virus.
    private String infectionSignature;
    private final Instant createdAt;
    private Instant updatedAt;

    // The one constructor: every field is set here, so no instance exists half built. Both factories go through it.
    private SecureFile(FileSnapshot state) {
        super(state.id(), state.version());
        this.owner = state.owner();
        this.name = state.name();
        this.declaredSize = state.declaredSize();
        this.status = state.status();
        this.sha256 = state.sha256();
        this.uploadExpiresAt = state.uploadExpiresAt();
        this.failureReason = state.failureReason();
        this.infectionSignature = state.infectionSignature();
        this.createdAt = state.createdAt();
        this.updatedAt = state.updatedAt();
    }

    /**
     * Starts an upload: the row exists before the binary, so every intermediate state is visible and recoverable.
     */
    public static SecureFile initiate(FileId id, OwnerId owner, String name, long declaredSize,
                                      Instant now, Instant uploadExpiresAt) {
        if (owner == null) throw new IllegalArgumentException("Owner is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("File name is required");
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) throw new IllegalArgumentException("File name is too long");
        if (declaredSize < 0) throw new IllegalArgumentException("File size cannot be negative");
        if (uploadExpiresAt == null || !uploadExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("The upload deadline must be in the future");
        }

        SecureFile file = new SecureFile(new FileSnapshot(id, owner, trimmed, declaredSize, FileStatus.UPLOADING, null,
                uploadExpiresAt, null, null, now, now, 0L));
        file.registerEvent(new FileUploadStarted(id, owner, trimmed, declaredSize, now));
        return file;
    }

    /**
     * Rebuilds a stored file: no rule is applied and no event is recorded, the persisted state is trusted as is.
     */
    public static SecureFile reconstitute(FileSnapshot state) {
        return new SecureFile(state);
    }

    /**
     * Ends the upload: the body is fully stored, its digest is known and its size is the declared one. Only then
     * does the file become eligible for the scan queue.
     */
    public void completeUpload(Sha256 digest, long actualSize, Instant now) {
        if (status != FileStatus.UPLOADING) throw new InvalidFileTransitionException(status, FileStatus.PENDING);
        if (digest == null) throw new IllegalArgumentException("Digest is required");
        if (actualSize != declaredSize) throw new UploadSizeMismatchException();

        this.sha256 = digest;
        this.status = FileStatus.PENDING;
        this.updatedAt = now;
        registerEvent(new UploadCompleted(id(), digest, actualSize, now));
    }

    /**
     * A worker claims the file for scanning. Only a PENDING file can be claimed, so two workers cannot start the
     * same scan: the loser sees the transition refused.
     */
    public void startScan(Instant now) {
        if (status != FileStatus.PENDING) throw new InvalidFileTransitionException(status, FileStatus.SCANNING);

        this.status = FileStatus.SCANNING;
        this.updatedAt = now;
        registerEvent(new ScanStarted(id(), now));
    }

    public void markClean(Instant now) {
        requireScanning(FileStatus.CLEAN);

        this.status = FileStatus.CLEAN;
        this.updatedAt = now;
        registerEvent(new FileFoundClean(id(), now));
    }

    public void markInfected(String signatureName, Instant now) {
        requireScanning(FileStatus.INFECTED);
        if (signatureName == null || signatureName.isBlank()) {
            throw new IllegalArgumentException("Signature name is required");
        }

        this.infectionSignature = signatureName.trim();
        this.status = FileStatus.INFECTED;
        this.updatedAt = now;
        registerEvent(new FileFoundInfected(id(), infectionSignature, now));
    }

    /**
     * The scan could not be completed and will not be retried. The cause lives in the scan job; the decision to
     * give up (attempts exhausted) is the job's, not the file's.
     */
    public void failScan(Instant now) {
        requireScanning(FileStatus.SCAN_FAILED);

        this.status = FileStatus.SCAN_FAILED;
        this.updatedAt = now;
        registerEvent(new ScanFailed(id(), now));
    }

    /**
     * Hands the file back to the queue without a verdict: ClamAV unreachable, worker shutdown or expired lease.
     */
    public void requeueScan(Instant now) {
        requireScanning(FileStatus.PENDING);

        this.status = FileStatus.PENDING;
        this.updatedAt = now;
        registerEvent(new ScanRequeued(id(), now));
    }

    // Every way out of a scan (verdict, failure, requeue) starts from SCANNING; CLEAN and INFECTED stay final.
    private void requireScanning(FileStatus target) {
        if (status != FileStatus.SCANNING) throw new InvalidFileTransitionException(status, target);
    }

    /**
     * The upload will never finish (timeout, abort, storage error, wrong size). A late completion is then refused
     * by the state check, which is what lets the reaper fail an upload that was about to end.
     */
    public void failUpload(UploadFailureReason reason, Instant now) {
        if (status != FileStatus.UPLOADING) throw new InvalidFileTransitionException(status, FileStatus.UPLOAD_FAILED);
        if (reason == null) throw new IllegalArgumentException("Failure reason is required");

        this.failureReason = reason;
        this.status = FileStatus.UPLOAD_FAILED;
        this.updatedAt = now;
        registerEvent(new UploadFailed(id(), reason, now));
    }

    /**
     * Stored content is immutable: only a file whose upload is in progress may receive a body. Anything else would
     * let a caller overwrite a file that is already stored, scanned or published.
     */
    public void ensureUploadable() {
        if (status != FileStatus.UPLOADING) throw new FileNotUploadableException();
    }

    /**
     * The central safety rule of the service: only a file the scan cleared may leave the quarantine. Every other
     * status, including a failed scan, keeps the content locked.
     */
    public void ensureDownloadable() {
        if (status != FileStatus.CLEAN) throw new FileNotDownloadableException();
    }

    public Optional<Sha256> getSha256() {
        return Optional.ofNullable(sha256);
    }

    public Optional<UploadFailureReason> getFailureReason() {
        return Optional.ofNullable(failureReason);
    }

    public Optional<String> getInfectionSignature() {
        return Optional.ofNullable(infectionSignature);
    }
}