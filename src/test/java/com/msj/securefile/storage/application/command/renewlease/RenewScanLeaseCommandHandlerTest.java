package com.msj.securefile.storage.application.command.renewlease;

import com.msj.securefile.storage.application.port.out.ScanJobRepository;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.ScanJob;
import com.msj.securefile.storage.domain.scan.ScanQueue;
import com.msj.securefile.storage.domain.scan.exception.LeaseLostException;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RenewScanLeaseCommandHandlerTest {

    private static final Instant CLAIMED_AT = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant NOW = CLAIMED_AT.plusSeconds(10);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final ScanJobId JOB_ID = ScanJobId.of(99L);
    private static final WorkerId WORKER = WorkerId.of("worker-1");

    @Mock ScanJobRepository scanJobRepository;

    private RenewScanLeaseCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RenewScanLeaseCommandHandler(scanJobRepository, CLOCK);
    }

    private static ScanJob leasedTo(WorkerId worker) {
        ScanJob job = ScanJob.create(JOB_ID, FileId.of(42L), ScanQueue.SMALL, CLAIMED_AT);
        job.claim(worker, CLAIMED_AT, LEASE);
        return job;
    }

    @Test
    void handle_pushesTheExpiryForwardFromNowAndSavesTheJob() {
        when(scanJobRepository.findById(JOB_ID)).thenReturn(Optional.of(leasedTo(WORKER)));

        handler.handle(new RenewScanLeaseCommand(JOB_ID, WORKER, LEASE));

        ArgumentCaptor<ScanJob> saved = ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository).save(saved.capture());
        assertThat(saved.getValue().getLeaseExpiresAt()).contains(NOW.plus(LEASE));
        assertThat(saved.getValue().getLeaseOwner()).contains(WORKER);
    }

    @Test
    void handle_tellsTheWorkerItLostTheLeaseWhenAnotherWorkerHoldsIt() {
        when(scanJobRepository.findById(JOB_ID)).thenReturn(Optional.of(leasedTo(WorkerId.of("worker-2"))));

        assertThatThrownBy(() -> handler.handle(new RenewScanLeaseCommand(JOB_ID, WORKER, LEASE)))
                .isInstanceOf(LeaseLostException.class);

        verify(scanJobRepository, never()).save(any());
    }

    @Test
    void handle_tellsTheWorkerItLostTheLeaseWhenTheJobDoesNotExist() {
        // From the worker's side a missing job and a lost lease mean the same: stop scanning.
        when(scanJobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new RenewScanLeaseCommand(JOB_ID, WORKER, LEASE)))
                .isInstanceOf(LeaseLostException.class);

        verify(scanJobRepository, never()).save(any());
    }
}