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
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessScanCommandHandlerTest {

    private static final ScanJobId JOB_ID = ScanJobId.of(99L);
    private static final FileId FILE_ID = FileId.of(42L);
    private static final WorkerId WORKER = WorkerId.of("worker-1");
    private static final Duration UNAVAILABLE_DELAY = Duration.ofSeconds(15);
    private static final String SIGNATURE = "Win.Test.EICAR_HDB-1";
    private static final ProcessScanCommand COMMAND = new ProcessScanCommand(JOB_ID, FILE_ID, WORKER, UNAVAILABLE_DELAY);

    @Mock FileStoragePort fileStoragePort;
    @Mock VirusScanner virusScanner;
    @Mock RecordScanVerdictCommandHandler recordVerdict;
    @Mock FailScanCommandHandler failScan;
    @Mock ReleaseScanCommandHandler releaseScan;
    @Mock InputStream quarantined;

    private ProcessScanCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ProcessScanCommandHandler(fileStoragePort, virusScanner, recordVerdict, failScan, releaseScan);
    }

    private void givenTheQuarantinedContent() {
        when(fileStoragePort.open(StorageZone.QUARANTINE, FILE_ID)).thenReturn(quarantined);
    }

    @Test
    void handle_copiesACleanFileBeforeRecordingTheVerdictThenClearsTheQuarantine() {
        givenTheQuarantinedContent();
        when(virusScanner.scan(quarantined)).thenReturn(new ScanVerdict.Clean());

        handler.handle(COMMAND);

        // The copy comes first: a crash before the verdict leaves the quarantine intact for the next attempt.
        InOrder order = inOrder(fileStoragePort, recordVerdict);
        order.verify(fileStoragePort).promote(FILE_ID);
        order.verify(recordVerdict).handle(new RecordScanVerdictCommand(JOB_ID, WORKER, new ScanVerdict.Clean()));
        order.verify(fileStoragePort).delete(StorageZone.QUARANTINE, FILE_ID);
    }

    @Test
    void handle_copiesNothingForAnInfectedFileAndClearsTheQuarantineAfterTheVerdict() {
        givenTheQuarantinedContent();
        when(virusScanner.scan(quarantined)).thenReturn(new ScanVerdict.Infected(SIGNATURE));

        handler.handle(COMMAND);

        verify(fileStoragePort, never()).promote(any());
        InOrder order = inOrder(recordVerdict, fileStoragePort);
        order.verify(recordVerdict)
                .handle(new RecordScanVerdictCommand(JOB_ID, WORKER, new ScanVerdict.Infected(SIGNATURE)));
        order.verify(fileStoragePort).delete(StorageZone.QUARANTINE, FILE_ID);
    }

    @Test
    void handle_closesTheQuarantinedStreamAfterTheScan() throws IOException {
        givenTheQuarantinedContent();
        when(virusScanner.scan(quarantined)).thenReturn(new ScanVerdict.Clean());

        handler.handle(COMMAND);

        verify(quarantined).close();
    }

    @Test
    void handle_releasesTheJobWithoutAnAttemptWhenTheScannerIsUnavailable() {
        givenTheQuarantinedContent();
        when(virusScanner.scan(quarantined)).thenThrow(new ScannerUnavailableException(new RuntimeException("refused")));

        handler.handle(COMMAND);

        verify(releaseScan).handle(new ReleaseScanCommand(JOB_ID, WORKER, UNAVAILABLE_DELAY));
        verify(failScan, never()).handle(any());
        verify(recordVerdict, never()).handle(any());
        verify(fileStoragePort, never()).delete(any(), any());
    }

    @Test
    void handle_reportsAFailedScanAsAnAttempt() {
        givenTheQuarantinedContent();
        ScanExecutionException failure = new ScanExecutionException(new RuntimeException("timeout"));
        when(virusScanner.scan(quarantined)).thenThrow(failure);

        handler.handle(COMMAND);

        verify(failScan).handle(new FailScanCommand(JOB_ID, WORKER, failure.getMessage()));
        verify(releaseScan, never()).handle(any());
        verify(recordVerdict, never()).handle(any());
    }

    @Test
    void handle_countsAnUnreadableQuarantineAsAnAttemptSoItCannotLoopForever() {
        // Releasing would not consume an attempt: an object that is gone for good would be retried indefinitely.
        FileStorageException failure = new FileStorageException(new RuntimeException("missing object"));
        when(fileStoragePort.open(StorageZone.QUARANTINE, FILE_ID)).thenThrow(failure);

        handler.handle(COMMAND);

        verify(failScan).handle(new FailScanCommand(JOB_ID, WORKER, failure.getMessage()));
        verify(releaseScan, never()).handle(any());
    }

    @Test
    void handle_recordsNoVerdictWhenTheCopyFails() {
        givenTheQuarantinedContent();
        when(virusScanner.scan(quarantined)).thenReturn(new ScanVerdict.Clean());
        FileStorageException failure = new FileStorageException(new RuntimeException("copy failed"));
        doThrow(failure).when(fileStoragePort).promote(FILE_ID);

        handler.handle(COMMAND);

        verify(recordVerdict, never()).handle(any());
        verify(failScan).handle(new FailScanCommand(JOB_ID, WORKER, failure.getMessage()));
        verify(fileStoragePort, never()).delete(any(), any());
    }

    @Test
    void handle_keepsTheVerdictWhenTheQuarantineCleanupFails() {
        givenTheQuarantinedContent();
        when(virusScanner.scan(quarantined)).thenReturn(new ScanVerdict.Clean());
        doThrow(new FileStorageException(new RuntimeException("delete failed")))
                .when(fileStoragePort).delete(StorageZone.QUARANTINE, FILE_ID);

        assertThatCode(() -> handler.handle(COMMAND)).doesNotThrowAnyException();

        verify(recordVerdict).handle(new RecordScanVerdictCommand(JOB_ID, WORKER, new ScanVerdict.Clean()));
        verify(failScan, never()).handle(any());
    }
}