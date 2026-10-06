package com.msj.securefile.storage.application.command.processscan;

import com.msj.securefile.storage.application.command.failscan.FailScanCommand;
import com.msj.securefile.storage.application.command.failscan.FailScanCommandHandler;
import com.msj.securefile.storage.application.command.recordverdict.RecordScanVerdictCommand;
import com.msj.securefile.storage.application.command.recordverdict.RecordScanVerdictCommandHandler;
import com.msj.securefile.storage.application.command.recordverdict.ScanVerdict;
import com.msj.securefile.storage.application.command.releasescan.ReleaseScanCommand;
import com.msj.securefile.storage.application.command.releasescan.ReleaseScanCommandHandler;
import com.msj.securefile.storage.application.port.out.FileStorageException;
import com.msj.securefile.storage.application.port.out.FileStoragePort;
import com.msj.securefile.storage.application.port.out.ScanExecutionException;
import com.msj.securefile.storage.application.port.out.ScannerUnavailableException;
import com.msj.securefile.storage.application.port.out.StorageZone;
import com.msj.securefile.storage.application.port.out.VirusScanner;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

/**
 * Runs the scan of a job the worker has claimed. Not transactional on purpose: the scan can take long and must not hold
 * a database connection. Each state change goes through a handler that has its own transaction.
 * <p>
 * The quarantine is only cleared after the verdict is recorded and the clean copy is made first, so a worker that dies
 * at any point leaves the content where the next attempt will look for it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessScanCommandHandler {

    private final FileStoragePort fileStoragePort;
    private final VirusScanner virusScanner;
    private final RecordScanVerdictCommandHandler recordVerdict;
    private final FailScanCommandHandler failScan;
    private final ReleaseScanCommandHandler releaseScan;

    public void handle(ProcessScanCommand command) {
        ScanVerdict verdict;
        try {
            verdict = scan(command.fileId());
        } catch (ScannerUnavailableException _) {
            // Nothing is known about the file: hand the job back without consuming an attempt.
            releaseScan.handle(new ReleaseScanCommand(command.jobId(), command.worker(), command.unavailableDelay()));
            return;
        } catch (ScanExecutionException | FileStorageException e) {
            fail(command, e);
            return;
        }

        try {
            if (verdict instanceof ScanVerdict.Clean) {
                fileStoragePort.promote(command.fileId());
            }
        } catch (FileStorageException e) {
            // No verdict without the clean copy: a file marked CLEAN must be downloadable.
            fail(command, e);
            return;
        }

        recordVerdict.handle(new RecordScanVerdictCommand(command.jobId(), command.worker(), verdict));
        clearQuarantine(command.fileId());
    }

    private ScanVerdict scan(FileId fileId) {
        try (InputStream content = fileStoragePort.open(StorageZone.QUARANTINE, fileId)) {
            return virusScanner.scan(content);
        } catch (IOException e) {
            throw new FileStorageException(e);
        }
    }

    // A failed attempt, not a release: an object that is gone for good would otherwise be retried forever.
    private void fail(ProcessScanCommand command, RuntimeException cause) {
        failScan.handle(new FailScanCommand(command.jobId(), command.worker(), cause.getMessage()));
    }

    // Best effort: the verdict is already recorded and a leftover in quarantine is never served.
    private void clearQuarantine(FileId fileId) {
        try {
            fileStoragePort.delete(StorageZone.QUARANTINE, fileId);
        } catch (FileStorageException e) {
            log.warn("Could not remove the quarantined content of file {}", fileId.asString(), e);
        }
    }
}