package com.msj.securefile.storage.infrastructure.adapters.scanner;

import com.msj.securefile.storage.application.command.recordverdict.ScanVerdict;
import com.msj.securefile.storage.application.port.out.ScanExecutionException;
import com.msj.securefile.storage.application.port.out.ScannerUnavailableException;
import com.msj.securefile.support.FakeClamd;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClamAvVirusScannerTest {

    private static final int MAX_CHUNK_BYTES = 64 * 1024;

    private static ClamAvVirusScanner scannerFor(int port, Duration readTimeout) {
        return new ClamAvVirusScanner(new ClamAvSettings("localhost", port, Duration.ofSeconds(2), readTimeout));
    }

    private static ClamAvVirusScanner scannerFor(FakeClamd clamd) {
        return scannerFor(clamd.port(), Duration.ofSeconds(5));
    }

    private static InputStream body(byte[] content) {
        return new ByteArrayInputStream(content);
    }

    private static byte[] bytes(int size) {
        byte[] content = new byte[size];
        new Random(7).nextBytes(content);
        return content;
    }

    @Test
    void scan_returnsCleanWhenClamdAnswersOk() {
        try (FakeClamd clamd = FakeClamd.replying("stream: OK")) {
            ScanVerdict verdict = scannerFor(clamd).scan(body(bytes(1_000)));

            assertThat(verdict).isEqualTo(new ScanVerdict.Clean());
        }
    }

    @Test
    void scan_returnsInfectedWithTheSignatureClamdFound() {
        try (FakeClamd clamd = FakeClamd.replying("stream: Win.Test.EICAR_HDB-1 FOUND")) {
            ScanVerdict verdict = scannerFor(clamd).scan(body(bytes(1_000)));

            assertThat(verdict).isEqualTo(new ScanVerdict.Infected("Win.Test.EICAR_HDB-1"));
        }
    }

    @Test
    void scan_sendsTheInstreamCommandThenTheBodyUnchanged() {
        byte[] content = bytes(1_000);
        try (FakeClamd clamd = FakeClamd.replying("stream: OK")) {
            scannerFor(clamd).scan(body(content));

            assertThat(clamd.exchange().command()).isEqualTo("zINSTREAM");
            assertThat(clamd.exchange().body()).isEqualTo(content);
        }
    }

    @Test
    void scan_cutsALargeBodyIntoBoundedChunks() {
        byte[] content = bytes(200_000);
        try (FakeClamd clamd = FakeClamd.replying("stream: OK")) {
            scannerFor(clamd).scan(body(content));

            // Never a whole file in memory: the body goes out chunk by chunk, each one announced by its length.
            assertThat(clamd.exchange().chunkLengths()).hasSizeGreaterThan(1)
                    .allSatisfy(length -> assertThat(length).isBetween(1, MAX_CHUNK_BYTES));
            assertThat(clamd.exchange().body()).isEqualTo(content);
        }
    }

    @Test
    void scan_acceptsAnEmptyBody() {
        try (FakeClamd clamd = FakeClamd.replying("stream: OK")) {
            ScanVerdict verdict = scannerFor(clamd).scan(body(new byte[0]));

            assertThat(verdict).isEqualTo(new ScanVerdict.Clean());
            assertThat(clamd.exchange().chunkLengths()).isEmpty();
        }
    }

    @Test
    void scan_saysTheScannerIsUnavailableWhenNobodyListens() {
        ClamAvVirusScanner scanner = scannerFor(FakeClamd.closedPort(), Duration.ofSeconds(2));

        // Nothing is known about the file: this must not count against it.
        assertThatThrownBy(() -> scanner.scan(body(bytes(10)))).isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void scan_reportsAFailedScanWhenClamdAnswersWithAnError() {
        try (FakeClamd clamd = FakeClamd.replying("INSTREAM size limit exceeded. ERROR")) {
            assertThatThrownBy(() -> scannerFor(clamd).scan(body(bytes(1_000))))
                    .isInstanceOf(ScanExecutionException.class);
        }
    }

    @Test
    void scan_reportsAFailedScanWhenTheAnswerIsNotUnderstood() {
        try (FakeClamd clamd = FakeClamd.replying("something clamd never says")) {
            assertThatThrownBy(() -> scannerFor(clamd).scan(body(bytes(1_000))))
                    .isInstanceOf(ScanExecutionException.class);
        }
    }

    @Test
    void scan_reportsAFailedScanWhenClamdHangsUpWithoutAnswering() {
        try (FakeClamd clamd = FakeClamd.hangingUp()) {
            assertThatThrownBy(() -> scannerFor(clamd).scan(body(bytes(1_000))))
                    .isInstanceOf(ScanExecutionException.class);
        }
    }

    @Test
    void scan_givesUpWhenClamdDoesNotAnswerInTime() {
        try (FakeClamd clamd = FakeClamd.neverReplying()) {
            ClamAvVirusScanner scanner = scannerFor(clamd.port(), Duration.ofMillis(300));

            // The scanner was reached but the scan did not complete: it counts as an attempt, not as an outage.
            assertThatThrownBy(() -> scanner.scan(body(bytes(1_000)))).isInstanceOf(ScanExecutionException.class);
        }
    }

    @Test
    void scan_reportsAFailedScanWhenTheContentCannotBeRead() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection to the store lost");
            }
        };
        try (FakeClamd clamd = FakeClamd.replying("stream: OK")) {
            assertThatThrownBy(() -> scannerFor(clamd).scan(broken)).isInstanceOf(ScanExecutionException.class);
        }
    }
}
